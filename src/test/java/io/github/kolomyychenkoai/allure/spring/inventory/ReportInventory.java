package io.github.kolomyychenkoai.allure.spring.inventory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * Инвентарь ВИДОВ шагов и вложений: скан {@code allure-results}, чтение/запись эталона, дифф.
 * Логика вынесена из теста ({@link ReportInventoryCheck}), чтобы тестироваться отдельно.
 * <p>
 * <b>Вид = пара «тест-класс | шаблон».</b> Одного имени шага мало: {@code HTTP GET … → 200}
 * пишут пять разных модулей (MockMvc/RestAssured/RestTemplate/RestClient/WebTestClient), и по
 * одному имени не понять, КТО отвалился. Каждый демо-{@code *IT} — витрина ровно одного модуля,
 * поэтому класс-владелец и есть указатель на модуль.
 * <p>
 * <b>Сканируем только пакет {@code demo}.</b> Юнит-тесты дают шаги НЕДЕТЕРМИНИРОВАННО: перехват
 * (напр. Jupiter-ассертов) мог встать от ранее прошедшего Spring-теста, а порядок классов случайный.
 * Включать их в инвентарь — сделать его флаки by design.
 */
public final class ReportInventory {

    /** Эталон лежит вне resources: одна копия, читается по пути от корня проекта. */
    public static final Path BASELINE = Path.of("src/test/inventory/report-inventory.txt");

    /** Владелец «*» — вид, чья привязка к классу плавает (кэш контекстов, порядок тестов). */
    public static final String ANY_OWNER = "*";

    private static final String DEMO_PACKAGE = ".demo.";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ReportInventory() {
    }

    /** Один вид: класс-владелец + шаблон. */
    public record Kind(String owner, String text) implements Comparable<Kind> {
        @Override
        public int compareTo(Kind other) {
            int byOwner = owner.compareTo(other.owner);
            return byOwner != 0 ? byOwner : text.compareTo(other.text);
        }

        @Override
        public String toString() {
            return owner + " | " + text;
        }
    }

    /** Что реально произвёл прогон. */
    public record Scan(Set<Kind> steps, Set<Kind> attachments, Set<String> owners, int resultFiles) {
    }

    /** Что записано в эталоне. */
    public record Baseline(Set<Kind> steps, Set<Kind> attachments, Set<Kind> optional,
                           Map<Kind, String> comments) {
        public Set<String> owners() {
            Set<String> owners = new TreeSet<>();
            Stream.concat(steps.stream(), attachments.stream())
                    .map(Kind::owner)
                    .filter(o -> !ANY_OWNER.equals(o))
                    .forEach(owners::add);
            return owners;
        }
    }

    // ─────────────────────────── скан результатов ───────────────────────────

    public static Scan scan(Path resultsDir) {
        Set<Kind> steps = new TreeSet<>();
        Set<Kind> attachments = new TreeSet<>();
        Set<String> owners = new TreeSet<>();
        int files = 0;
        if (!Files.isDirectory(resultsDir)) {
            return new Scan(steps, attachments, owners, 0);
        }
        try (Stream<Path> list = Files.list(resultsDir)) {
            List<Path> results = list.filter(p -> p.getFileName().toString().endsWith("-result.json")).toList();
            for (Path file : results) {
                JsonNode root = readTree(file);
                if (root == null) {
                    continue;
                }
                String testClass = label(root, "testClass");
                if (testClass == null || !testClass.contains(DEMO_PACKAGE)) {
                    continue;
                }
                files++;
                String owner = testClass.substring(testClass.lastIndexOf('.') + 1);
                owners.add(owner);
                collectAttachments(root, owner, attachments);
                collectSteps(root, owner, steps, attachments);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return new Scan(steps, attachments, owners, files);
    }

    private static void collectSteps(JsonNode node, String owner, Set<Kind> steps, Set<Kind> attachments) {
        for (JsonNode step : node.path("steps")) {
            steps.add(new Kind(owner, StepTemplates.step(step.path("name").asText(""))));
            collectAttachments(step, owner, attachments);
            collectSteps(step, owner, steps, attachments);
        }
    }

    private static void collectAttachments(JsonNode node, String owner, Set<Kind> attachments) {
        for (JsonNode att : node.path("attachments")) {
            attachments.add(new Kind(owner,
                    StepTemplates.attachment(att.path("name").asText(""), att.path("type").asText(""))));
        }
    }

    private static JsonNode readTree(Path file) {
        try {
            return MAPPER.readTree(file.toFile());
        } catch (IOException broken) {
            return null; // недописанный файл прогона — не повод падать
        }
    }

    private static String label(JsonNode root, String name) {
        for (JsonNode l : root.path("labels")) {
            if (name.equals(l.path("name").asText())) {
                return l.path("value").asText(null);
            }
        }
        return null;
    }

    // ─────────────────────────── эталон: чтение ───────────────────────────

    public static Baseline readBaseline(Path path) {
        Set<Kind> steps = new TreeSet<>();
        Set<Kind> attachments = new TreeSet<>();
        Set<Kind> optional = new TreeSet<>();
        Map<Kind, String> comments = new TreeMap<>();
        if (!Files.isRegularFile(path)) {
            return new Baseline(steps, attachments, optional, comments);
        }
        String section = "steps";
        for (String raw : readLines(path)) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            if (line.startsWith("[") && line.endsWith("]")) {
                section = line.substring(1, line.length() - 1);
                continue;
            }
            boolean isOptional = line.startsWith("? ");
            if (isOptional) {
                line = line.substring(2).strip();
            }
            String comment = null;
            int commentAt = indexOfComment(line);
            if (commentAt >= 0) {
                comment = line.substring(commentAt).replaceFirst("^\\s*#\\s*", "");
                line = line.substring(0, commentAt).strip();
            }
            int sep = line.indexOf(" | ");
            if (sep < 0) {
                continue;
            }
            Kind kind = new Kind(line.substring(0, sep).strip(), line.substring(sep + 3).strip());
            ("attachments".equals(section) ? attachments : steps).add(kind);
            if (isOptional) {
                optional.add(kind);
            }
            if (comment != null && !comment.isBlank()) {
                comments.put(kind, comment);
            }
        }
        return new Baseline(steps, attachments, optional, comments);
    }

    /** Комментарий-подпись отделяется ДВУМЯ пробелами перед «#» — «#» внутри имени шага не мешает. */
    private static int indexOfComment(String line) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\s{2,}#").matcher(line);
        return m.find() ? m.start() : -1;
    }

    private static List<String> readLines(Path path) {
        try {
            return Files.readAllLines(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ─────────────────────────── эталон: запись ───────────────────────────

    /**
     * Перезаписать эталон по результатам прогона. Комментарии-подписи («за это отвечает такой-то
     * класс») и пометки «?» ПЕРЕНОСЯТСЯ для сохранившихся видов — ручная работа не теряется.
     */
    public static void write(Path path, Scan scan, Baseline previous) {
        StringBuilder out = new StringBuilder();
        out.append("""
                # Инвентарь ВИДОВ шагов и вложений Allure-отчёта — эталон.
                #
                # Зачем: библиотека ломается ТИХО (перехват отвалился → шаг исчез → тесты зелёные).
                # Этот файл фиксирует, что отчёт УМЕЛ показывать. Пропажа вида = красный
                # ReportInventoryCheck с именем модуля.
                #
                # Обновление — ОСОЗНАННОЕ действие:  mvn clean test -Dinventory.update=true
                # (файл перезапишется, прогон всё равно упадёт — проверь git diff и закоммить).
                #
                # Формат:  <ТестКласс> | <шаблон>  # <кто отвечает>
                #   «*» вместо класса — вид, чей класс-владелец плавает (кэш контекстов).
                #   «?» в начале строки — вид необязательный (может не появиться в прогоне).
                """);
        appendSection(out, "steps", scan.steps(), previous);
        appendSection(out, "attachments", scan.attachments(), previous);
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, out.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void appendSection(StringBuilder out, String name, Set<Kind> kinds, Baseline previous) {
        out.append('\n').append('[').append(name).append(']').append('\n');
        // ширина колонки — чтобы подписи выстроились в столбик и файл читался глазами
        int width = kinds.stream().mapToInt(k -> k.toString().length()).max().orElse(0);
        for (Kind kind : kinds) {
            Kind carried = previous.optional().contains(kind) ? kind : null;
            String line = (carried != null ? "? " : "") + kind;
            String comment = previous.comments().get(kind);
            if (comment != null) {
                line = padRight(line, width + 2) + "  # " + comment;
            }
            out.append(line).append('\n');
        }
    }

    private static String padRight(String s, int width) {
        return s.length() >= width ? s : s + " ".repeat(width - s.length());
    }

    // ─────────────────────────── дифф ───────────────────────────

    /** Виды из эталона, которых прогон не дал. С учётом «*» (где угодно) и «?» (необязательные). */
    public static List<Kind> missing(Set<Kind> baseline, Set<Kind> optional, Scan scan, boolean attachments) {
        Set<Kind> seen = attachments ? scan.attachments() : scan.steps();
        Set<String> seenAnywhere = new TreeSet<>();
        seen.forEach(k -> seenAnywhere.add(k.text()));
        List<Kind> gone = new ArrayList<>();
        for (Kind kind : baseline) {
            if (optional.contains(kind)) {
                continue;
            }
            boolean present = ANY_OWNER.equals(kind.owner())
                    ? seenAnywhere.contains(kind.text())
                    : seen.contains(kind);
            if (!present) {
                gone.add(kind);
            }
        }
        return gone;
    }

    /** Виды, которых в эталоне нет (новые). Не падение — печатаются отдельно. */
    public static List<Kind> extra(Set<Kind> baseline, Set<Kind> seen) {
        Set<String> anywhere = new TreeSet<>();
        baseline.stream().filter(k -> ANY_OWNER.equals(k.owner())).forEach(k -> anywhere.add(k.text()));
        List<Kind> fresh = new ArrayList<>();
        for (Kind kind : seen) {
            if (!baseline.contains(kind) && !anywhere.contains(kind.text())) {
                fresh.add(kind);
            }
        }
        return fresh;
    }

    /**
     * Спец-диагноз: вложение с таким именем ЕСТЬ, но с другим mime. Иначе разработчик будет
     * искать «исчезнувшее» вложение, которого не исчезало (типичная деградация притти-JSON).
     */
    public static Map<Kind, String> mimeDrift(List<Kind> missingAttachments, Scan scan) {
        Map<Kind, String> drift = new LinkedHashMap<>();
        for (Kind gone : missingAttachments) {
            String namePart = gone.text().substring(0, gone.text().lastIndexOf(" | ") + 3);
            scan.attachments().stream()
                    .filter(seen -> seen.owner().equals(gone.owner()) || ANY_OWNER.equals(gone.owner()))
                    .filter(seen -> seen.text().startsWith(namePart))
                    .findFirst()
                    .ifPresent(seen -> drift.put(gone, seen.text().substring(namePart.length())));
        }
        return drift;
    }
}
