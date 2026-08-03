package io.github.kolomyychenkoai.allure.spring.inventory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Инвентарь ВИДОВ шагов и вложений: скан {@code allure-results}, чтение/запись эталона, вердикт.
 * Вся логика ПРИНЯТИЯ РЕШЕНИЯ — чистые функции ({@link #verdict}, {@link #updateVerdict}),
 * тест {@link ReportInventoryCheck} остаётся тонким шеллом «посчитать → показать → упасть».
 * Так гейты детектора сами покрыты тестами: сломанный гейт = вечно-зелёный детектор,
 * худший из возможных исходов.
 * <p>
 * <b>Вид = пара «тест-класс | шаблон».</b> Одного имени шага мало: {@code HTTP GET … → 200}
 * пишут пять разных модулей, и по имени не понять, КТО отвалился. Каждый демо-{@code *IT} —
 * витрина ровно одного модуля.
 * <p>
 * <b>Вложенность — часть вида.</b> Шаг записывается как {@code родитель ▸ шаг} (один уровень
 * вверх). Без этого регрессия «SQL перестал вкладываться в шаг репозитория и вылез наверх»
 * даёт ТОТ ЖЕ набор видов — а для ручной приёмки это заметная деградация отчёта.
 * <p>
 * <b>Сканируем только пакет {@code demo}.</b> Юнит-тесты дают шаги НЕДЕТЕРМИНИРОВАННО: перехват
 * мог встать от ранее прошедшего Spring-теста, а порядок классов случайный.
 */
public final class ReportInventory {

    /** Эталон лежит вне resources: одна копия, читается по пути от корня проекта. */
    public static final Path BASELINE = Path.of("src/test/inventory/report-inventory.txt");

    /** Владелец «*» — вид, чья привязка к классу плавает (кэш контекстов, порядок тестов). */
    public static final String ANY_OWNER = "*";

    /** Разделитель «родитель ▸ вложенный шаг». */
    public static final String NESTED = " ▸ ";

    private static final String DEMO_PACKAGE = ".demo.";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** Комментарий-подпись отделяется ДВУМЯ пробелами перед «#» — «#» внутри имени шага не мешает. */
    private static final Pattern COMMENT = Pattern.compile("\\s{2,}#");
    /**
     * Маркер ожидаемой кратности в конце строки эталона: «  ×1», «  ×≥2».
     * Два пробела обязательны: «×» встречается ВНУТРИ имён видов («(ожидали ×<N>)»).
     */
    private static final Pattern COUNT_MARKER = Pattern.compile("\\s{2,}×(≥)?(\\d+)\\s*$");
    /** Маркер статусного вида: « [BROKEN]», « [FAILED]», « [SKIPPED]». */
    private static final Pattern STATUS_MARKER = Pattern.compile(" \\[[A-Z]+]$");

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
    public record Scan(Set<Kind> steps, Set<Kind> attachments, Set<String> owners, int resultFiles,
                       List<String> unreadable, List<String> missingFiles, List<String> dirtyNames,
                       Map<Kind, Range> perCase) {
    }

    /** Состояние содержимого вложения. */
    public enum Content {
        /** Файл на месте и непустой (либо источник не указан — судить не о чем). */
        PRESENT,
        /** Файл на месте, но пустой — деградация «пишем, а нечего показать». */
        EMPTY,
        /** Файла НЕТ: вложение объявлено, байты не записаны. Отчёт сломан, а не «стал другим». */
        MISSING
    }

    /** Ожидаемая кратность вида: «ровно N в каждом кейсе» либо «не меньше N». */
    public record Count(int value, boolean atLeast) {
        @Override
        public String toString() {
            return "×" + (atLeast ? "≥" : "") + value;
        }
    }

    /** Сколько раз вид встречался в одном тест-кейсе: минимум и максимум по кейсам. */
    public record Range(int min, int max) {
        Range with(int n) {
            return new Range(Math.min(min, n), Math.max(max, n));
        }

        @Override
        public String toString() {
            return min == max ? String.valueOf(min) : min + ".." + max;
        }
    }

    /** Кратность разъехалась: ждали одно, увидели другое. */
    public record CountMismatch(Kind kind, Count expected, Range seen) {
    }

    /** Что записано в эталоне. */
    public record Baseline(Set<Kind> steps, Set<Kind> attachments, Set<Kind> optional,
                           Map<Kind, String> comments, Map<Kind, Count> counts) {

        public Baseline(Set<Kind> steps, Set<Kind> attachments, Set<Kind> optional, Map<Kind, String> comments) {
            this(steps, attachments, optional, comments, Map.of());
        }

        public Set<String> owners() {
            Set<String> owners = new TreeSet<>();
            Stream.concat(steps.stream(), attachments.stream())
                    .map(Kind::owner)
                    .filter(owner -> !ANY_OWNER.equals(owner))
                    .forEach(owners::add);
            return owners;
        }
    }

    /**
     * Вердикт сверки. {@code noData} — прогон не дал данных (проверка НЕДЕЙСТВИТЕЛЬНА, а не
     * успешна); {@code silentOwners} — класс-витрина потерял ВСЕ свои виды («пропал тест», не
     * «отвалилась инструментация»); {@code unknownOwners} — появился класс-витрина, которого нет
     * в эталоне (модуль вне сетки — его никто не стережёт).
     */
    public record Verdict(boolean noData, List<Kind> missingSteps, List<Kind> missingAttachments,
                          List<Kind> extraSteps, List<Kind> extraAttachments,
                          List<String> silentOwners, List<String> unknownOwners,
                          List<CountMismatch> countMismatches) {

        public Verdict(boolean noData, List<Kind> missingSteps, List<Kind> missingAttachments,
                       List<Kind> extraSteps, List<Kind> extraAttachments,
                       List<String> silentOwners, List<String> unknownOwners) {
            this(noData, missingSteps, missingAttachments, extraSteps, extraAttachments,
                    silentOwners, unknownOwners, List.of());
        }


        /** Появился НОВЫЙ вид со статусом — библиотека начала фабриковать сбои там, где их не было. */
        public boolean newFailures() {
            return Stream.concat(extraSteps.stream(), extraAttachments.stream())
                    .anyMatch(kind -> STATUS_MARKER.matcher(kind.text()).find());
        }

        public boolean failed(boolean strict) {
            // silentOwners — самостоятельная причина падения, а не «следствие пропавших видов»:
            // если ВСЕ виды класса помечены «?» (а «?» ставят как раз против флаки), класс мог бы
            // исчезнуть целиком при зелёной сборке.
            return noData || !missingSteps.isEmpty() || !missingAttachments.isEmpty()
                    || !unknownOwners.isEmpty() || !silentOwners.isEmpty()
                    // новый ШАГ — это обогащение отчёта (норма), новый НЕ-PASSED статус — поломка:
                    // правило библиотеки «упавшую проверку шагом не логируем» перестало соблюдаться
                    || newFailures() || !countMismatches.isEmpty()
                    || (strict && (!extraSteps.isEmpty() || !extraAttachments.isEmpty()));
        }

        public int missingCount() {
            return missingSteps.size() + missingAttachments.size();
        }

        public List<Kind> extras() {
            List<Kind> all = new ArrayList<>(extraSteps);
            all.addAll(extraAttachments);
            return all;
        }
    }

    /** Можно ли обновлять эталон этим прогоном и удаляет ли обновление виды. */
    public record UpdateVerdict(List<String> lostOwners, List<Kind> removed) {
        public boolean partialRun() {
            return !lostOwners.isEmpty();
        }
    }

    // ─────────────────────────── скан результатов ───────────────────────────

    public static Scan scan(Path resultsDir) {
        Set<Kind> steps = new TreeSet<>();
        Set<Kind> attachments = new TreeSet<>();
        Set<String> owners = new TreeSet<>();
        int files = 0;
        if (!Files.isDirectory(resultsDir)) {
            return new Scan(steps, attachments, owners, 0, List.of(), List.of(), List.of(), Map.of());
        }
        List<String> unreadable = new ArrayList<>();
        List<String> missingFiles = new ArrayList<>();
        List<String> dirtyNames = new ArrayList<>();
        Map<Kind, Range> perCase = new TreeMap<>();
        try (Stream<Path> list = Files.list(resultsDir)) {
            List<Path> results = list.filter(p -> p.getFileName().toString().endsWith("-result.json")).toList();
            for (Path file : results) {
                JsonNode root = readTree(file, unreadable);
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
                // Кратность считаем ЗА ТЕСТ-КЕЙС, а не за класс: иначе добавление @Test двигало бы
                // эталон по причине, не связанной с отчётом. Инвариант библиотеки — «один вызов =
                // один шаг», и он про кейс.
                Map<Kind, Integer> local = new HashMap<>();
                collectAttachments(root, owner, attachments, resultsDir, missingFiles, dirtyNames, local);
                collectSteps(root, owner, null, steps, attachments, resultsDir, missingFiles, dirtyNames, local);
                local.forEach((kind, n) -> perCase.merge(kind, new Range(n, n), (a, b) -> a.with(b.max())));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return new Scan(steps, attachments, owners, files, unreadable, missingFiles, dirtyNames, perCase);
    }

    /** {@code parent} — шаблон НЕПОСРЕДСТВЕННОГО родителя (null для верхнего уровня). */
    private static void collectSteps(JsonNode node, String owner, String parent, Set<Kind> steps,
                                     Set<Kind> attachments, Path resultsDir,
                                     List<String> missingFiles, List<String> dirtyNames,
                                     Map<Kind, Integer> local) {
        for (JsonNode step : node.path("steps")) {
            String raw = step.path("name").asText("");
            hygiene(owner, raw, dirtyNames);
            String template = StepTemplates.step(raw);
            // Родительский префикс берём БЕЗ статус-суффикса: иначе один сломавшийся родитель
            // переименовал бы все дочерние виды и дал каскад «пропало+появилось» вместо диагноза.
            Kind kind = new Kind(owner,
                    (parent == null ? template : parent + NESTED + template) + statusSuffix(step));
            steps.add(kind);
            local.merge(kind, 1, Integer::sum);
            collectAttachments(step, owner, attachments, resultsDir, missingFiles, dirtyNames, local);
            collectSteps(step, owner, template, steps, attachments, resultsDir, missingFiles, dirtyNames, local);
        }
    }

    /**
     * Суффикс статуса — ТОЛЬКО для не-passed. Сегодня это ровно один вид на весь отчёт
     * (BROKEN у ошибки репозитория), то есть эталон не раздувается, а регрессия
     * «BROKEN стал PASSED» становится видимой: вид просто исчезает.
     */
    static String statusSuffix(JsonNode step) {
        String status = step.path("status").asText("passed");
        return status.isBlank() || "passed".equals(status) ? "" : " [" + status.toUpperCase(Locale.ROOT) + "]";
    }

    /** Гигиена по СЫРОМУ имени: нормализация маскирует хэши, то есть прячет ровно этот мусор. */
    private static void hygiene(String owner, String rawName, List<String> dirtyNames) {
        StepNameHygiene.defect(rawName)
                .ifPresent(diagnosis -> dirtyNames.add(owner + " | " + rawName + "  ← " + diagnosis));
    }

    private static void collectAttachments(JsonNode node, String owner, Set<Kind> attachments, Path resultsDir,
                                           List<String> missingFiles, List<String> dirtyNames,
                                     Map<Kind, Integer> local) {
        for (JsonNode att : node.path("attachments")) {
            String name = att.path("name").asText("");
            hygiene(owner, name, dirtyNames);
            String source = att.path("source").asText(null);
            Content content = content(resultsDir, source);
            if (content == Content.MISSING) {
                // НЕ вид: «вложение объявлено, а байтов нет» — это не «отчёт стал другим», а
                // «отчёт сломан», и лечится оно не обновлением эталона. Отдельный безусловный гейт.
                missingFiles.add(owner + " | " + name + " → нет файла " + source);
            }
            Kind kind = new Kind(owner, StepTemplates.attachment(
                    name, att.path("type").asText(""), content != Content.EMPTY));
            attachments.add(kind);
            local.merge(kind, 1, Integer::sum);
        }
    }

    /**
     * Состояние содержимого вложения. Признак «пусто» входит в ВИД: типичная тихая деградация —
     * «вложение пишется, но пустое» (напр. отвалился разбор полей сущности) — иначе прошла бы мимо,
     * ведь имя и mime на месте.
     * <p>
     * ОТСУТСТВИЕ файла-источника раньше читалось как «содержимое есть» — то есть самая тяжёлая
     * деградация выглядела здоровой. Инвентарь гоняется ВТОРЫМ прогоном, когда всё уже сброшено
     * на диск, так что «файла нет» — однозначный дефект, а не гонка.
     */
    static Content content(Path resultsDir, String source) {
        if (source == null || source.isBlank()) {
            return Content.PRESENT; // источник не указан — судить не о чем, не выдумываем деградацию
        }
        Path file = resultsDir.resolve(source);
        if (!Files.isRegularFile(file)) {
            return Content.MISSING;
        }
        try {
            return Files.size(file) > 0 ? Content.PRESENT : Content.EMPTY;
        } catch (IOException unreadable) {
            return Content.MISSING;
        }
    }

    private static JsonNode readTree(Path file, List<String> unreadable) {
        try {
            return MAPPER.readTree(file.toFile());
        } catch (IOException broken) {
            // Диагноз копим и отдаём наружу (печатает вызывающий): молча пропущенный файл выглядел
            // бы как «отвалился модуль», и человек пошёл бы искать поломку инструментации вместо
            // недописанного файла. В stderr отсюда не пишем — юнит-тесты скана не должны сорить
            // в лог основного прогона жалобами на свои временные каталоги.
            unreadable.add(file + " — " + broken);
            return null;
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

    // ─────────────────────────── вердикт (чистые функции) ───────────────────────────

    public static Verdict verdict(Scan scan, Baseline baseline) {
        if (scan.owners().isEmpty()) {
            return new Verdict(true, List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        }
        List<String> silent = baseline.owners().stream()
                .filter(owner -> !scan.owners().contains(owner))
                .toList();
        // Новый класс-витрина = модуль ВНЕ сетки: его виды никто не стережёт, а «новые виды»
        // сами по себе не роняют сборку. Событие разовое (раз в жизни модуля) — шума не даёт.
        List<String> unknown = scan.owners().stream()
                .filter(owner -> !baseline.owners().contains(owner))
                .toList();
        return new Verdict(false,
                missing(baseline.steps(), baseline.optional(), scan.steps()),
                missing(baseline.attachments(), baseline.optional(), scan.attachments()),
                extra(baseline.steps(), scan.steps()),
                extra(baseline.attachments(), scan.attachments()),
                silent, unknown, counts(baseline.counts(), scan.perCase()));
    }

    /**
     * Что произойдёт при обновлении эталона этим прогоном.
     * <p>
     * «Что БУДЕТ удалено» считается ровно тем же {@link #merge}, которым потом пишется файл:
     * два независимых вычисления одного факта — это дублирование не кода, а ИСТИНЫ, и их
     * рассинхрон тих. Иначе {@code ?}-вид, не пришедший в этом прогоне, попадал бы в «удаляемые»,
     * хотя {@code write} его сохраняет — флаг требовался бы ради удаления, которого не будет.
     */
    public static UpdateVerdict updateVerdict(Scan scan, Baseline baseline) {
        List<String> lost = baseline.owners().stream()
                .filter(owner -> !scan.owners().contains(owner))
                .toList();
        Set<Kind> keptSteps = merge(scan.steps(), baseline.steps(), baseline);
        Set<Kind> keptAttachments = merge(scan.attachments(), baseline.attachments(), baseline);
        List<Kind> removed = new ArrayList<>();
        baseline.steps().stream().filter(kind -> !keptSteps.contains(kind)).forEach(removed::add);
        baseline.attachments().stream().filter(kind -> !keptAttachments.contains(kind)).forEach(removed::add);
        return new UpdateVerdict(lost, removed);
    }

    /**
     * Виды, чья кратность разъехалась с ожидаемой. Считается только для видов, ПОМЕЧЕННЫХ в эталоне
     * (маркер «×N»): белый список живёт в данных, а не в коде. Виды, которых прогон вовсе не дал,
     * тут пропускаем — это уже {@code missing}, второй раз тот же факт не диагностируем.
     * <p>
     * Семантика по умолчанию — «ровно N в КАЖДОМ кейсе»: главный класс регрессии при апгрейде —
     * ЗАДВОЕНИЕ (сломался граф само-делегации, отвалился CAS-гард), а «не меньше N» его не видит.
     */
    public static List<CountMismatch> counts(Map<Kind, Count> expected, Map<Kind, Range> seen) {
        List<CountMismatch> mismatches = new ArrayList<>();
        expected.forEach((kind, count) -> {
            Range range = seen.get(kind);
            if (range == null) {
                return;
            }
            boolean ok = count.atLeast()
                    ? range.min() >= count.value()
                    : range.min() == count.value() && range.max() == count.value();
            if (!ok) {
                mismatches.add(new CountMismatch(kind, count, range));
            }
        });
        return mismatches;
    }

    /** Виды из эталона, которых прогон не дал. С учётом «*» (где угодно) и «?» (необязательные). */
    public static List<Kind> missing(Set<Kind> baseline, Set<Kind> optional, Set<Kind> seen) {
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

    /** Виды, которых в эталоне нет (новые). Сами по себе не падение — печатаются отдельно. */
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
     * Спец-диагноз: вложение с таким именем ЕСТЬ, но с другим mime/содержимым. Иначе человек
     * будет искать «исчезнувшее» вложение, которого не исчезало (типичная деградация притти-JSON).
     */
    public static Map<Kind, String> mimeDrift(List<Kind> missingAttachments, Scan scan) {
        Map<Kind, String> drift = new LinkedHashMap<>();
        for (Kind gone : missingAttachments) {
            int cut = gone.text().lastIndexOf(" | ");
            if (cut < 0) {
                continue; // не вид вложения — сравнивать не с чем
            }
            String namePart = gone.text().substring(0, cut + 3);
            scan.attachments().stream()
                    .filter(seen -> seen.owner().equals(gone.owner()) || ANY_OWNER.equals(gone.owner()))
                    .filter(seen -> seen.text().startsWith(namePart))
                    .findFirst()
                    .ifPresent(seen -> drift.put(gone, seen.text().substring(namePart.length())));
        }
        return drift;
    }

    // ─────────────────────────── эталон: чтение ───────────────────────────

    public static Baseline readBaseline(Path path) {
        Set<Kind> steps = new TreeSet<>();
        Set<Kind> attachments = new TreeSet<>();
        Set<Kind> optional = new TreeSet<>();
        Map<Kind, String> comments = new TreeMap<>();
        Map<Kind, Count> counts = new TreeMap<>();
        if (!Files.isRegularFile(path)) {
            return new Baseline(steps, attachments, optional, comments, counts);
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
            Matcher commentAt = COMMENT.matcher(line);
            if (commentAt.find()) {
                comment = line.substring(commentAt.start()).replaceFirst("^\\s*#\\s*", "");
                line = line.substring(0, commentAt.start()).strip();
            }
            Count count = null;
            Matcher countAt = COUNT_MARKER.matcher(line);
            if (countAt.find()) {
                count = new Count(Integer.parseInt(countAt.group(2)), countAt.group(1) != null);
                line = line.substring(0, countAt.start()).strip();
            }
            int sep = line.indexOf(" | ");
            if (sep < 0) {
                continue;
            }
            Kind kind = new Kind(line.substring(0, sep).strip(), line.substring(sep + 3).strip());
            if (count != null) {
                counts.put(kind, count);
            }
            ("attachments".equals(section) ? attachments : steps).add(kind);
            if (isOptional) {
                optional.add(kind);
            }
            if (comment != null && !comment.isBlank()) {
                comments.put(kind, comment);
            }
        }
        return new Baseline(steps, attachments, optional, comments, counts);
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
     * Перезаписать эталон по результатам прогона.
     * <p>
     * Ручная работа НЕ теряется: подписи-комментарии переносятся, а виды с владельцем {@code *}
     * и необязательные {@code ?} СОХРАНЯЮТСЯ, даже если прогон их не дал или дал с конкретным
     * владельцем — иначе первое же обновление тихо сняло бы флако-толерантность, ради которой
     * их и заводили.
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
                # УДАЛЕНИЕ видов требует отдельного -Dinventory.remove=true: чтобы красную сборку
                # нельзя было «починить» обновлением эталона, не заметив, что сетка распустилась.
                #
                # Формат:  <ТестКласс> | <шаблон>  # <кто отвечает>
                #   «▸» — вложенность шага в родительский (регрессия «шаг вылез наверх» видна).
                #   «*» вместо класса — вид, чей класс-владелец плавает (кэш контекстов).
                #   «?» в начале строки — вид необязательный (может не появиться в прогоне).
                #   «| пусто» у вложения — содержимое пустое (тоже вид: деградация видна).
                """);
        appendSection(out, "steps", merge(scan.steps(), previous.steps(), previous), previous);
        appendSection(out, "attachments", merge(scan.attachments(), previous.attachments(), previous), previous);
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, out.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Разовый посев маркеров кратности ПО ЗАМЕРУ: помечаем «×N» только те виды, чья кратность
     * ОДИНАКОВА во всех кейсах прогона ({@code min == max}). Виды с плавающим числом (их решает
     * Hibernate/Liquibase/сеть) остаются без маркера — и, значит, кратностью не стерегутся.
     * <p>
     * Так белый список получается из ФАКТА, а не из предположения «наверное, один раз».
     */
    public static Map<Kind, Count> seedCounts(Scan scan, Baseline previous) {
        Map<Kind, Count> counts = new TreeMap<>(previous.counts());
        scan.perCase().forEach((kind, range) -> {
            if (range.min() == range.max()) {
                counts.put(kind, new Count(range.min(), false));
            } else {
                counts.remove(kind); // стало плавать — маркер снимаем, иначе будет флак
            }
        });
        return counts;
    }

    /** К увиденному добавляем «аварийные клапаны» эталона: {@code *}-виды и {@code ?}-виды. */
    private static Set<Kind> merge(Set<Kind> seen, Set<Kind> previous, Baseline baseline) {
        Set<Kind> result = new TreeSet<>(seen);
        Set<String> anyOwnerTexts = new TreeSet<>();
        previous.stream().filter(k -> ANY_OWNER.equals(k.owner())).forEach(k -> {
            result.add(k);
            anyOwnerTexts.add(k.text());
        });
        previous.stream().filter(baseline.optional()::contains).forEach(result::add);
        // конкретный владелец не должен вытеснять «*»-запись того же вида
        result.removeIf(k -> !ANY_OWNER.equals(k.owner()) && anyOwnerTexts.contains(k.text()));
        return result;
    }

    private static void appendSection(StringBuilder out, String name, Set<Kind> kinds, Baseline previous) {
        out.append('\n').append('[').append(name).append(']').append('\n');
        for (Kind kind : kinds) {
            boolean optional = previous.optional().contains(kind);
            String line = (optional ? "? " : "") + kind;
            Count count = previous.counts().get(kind);
            if (count != null) {
                line = line + "  " + count; // маркер переносится: белый список живёт в эталоне
            }
            String comment = previous.comments().get(kind);
            if (comment != null) {
                line = line + "  # " + comment;
            }
            out.append(line).append('\n');
        }
    }
}
