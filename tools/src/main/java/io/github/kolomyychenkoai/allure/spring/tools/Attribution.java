package io.github.kolomyychenkoai.allure.spring.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Проверка АТРИБУЦИИ шагов: шаг с меткой теста обязан лежать в кейсе ИМЕННО этого теста.
 * <p>
 * Что доказывает. README обещает, что под forked-JVM и под {@code @Execution(CONCURRENT)}
 * (для перечисленных технологий) шаги не уезжают в соседний тест-кейс. A/B-дифф это НЕ
 * проверяет — он видит только «тесты не упали». Здесь проверяется само обещание.
 * <p>
 * Как. Каждый тест витрины метит свои шаги строкой {@code attr-<n>}. Собираем «маркер →
 * множество тест-кейсов, в чьих шагах он встретился». Маркер в двух кейсах = шаг уехал.
 */
final class Attribution {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Attribution() {
    }

    static int run(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Использование: attribution <каталог-сервиса> [ожидаемое-число-маркеров] [префикс]");
            return 2;
        }
        Path root = Path.of(args[0]);
        int expected = 0;
        if (args.length > 1) {
            try {
                expected = Integer.parseInt(args[1]);
            } catch (NumberFormatException notANumber) {
                // Трасса тут была бы худшим из ответов: гейт зовут из скрипта, и по коду 1
                // его не отличить от настоящего провала проверки.
                System.err.println("ожидаемое число маркеров должно быть числом, а не «" + args[1] + "»");
                return 2;
            }
        }
        String prefix = args.length > 2 ? args[2] : "attr-";

        Path results = root.resolve("target").resolve("allure-results");
        if (!Files.isDirectory(results)) {
            System.out.println("✗ нет каталога результатов: " + results);
            return 1;
        }

        // [\w-]*\d+, а не \d+: маркеры бывают составными (attr-rest-1). Узкая регулярка молча
        // пропускала бы целый канал, а чекер при этом рапортовал бы «✅».
        // UNICODE_CHARACTER_CLASS обязателен: без него \w в Java только ASCII, и маркер
        // рядом с кириллицей разбирался бы иначе, чем разбирал питон.
        Pattern marker = Pattern.compile(Pattern.quote(prefix) + "[\\w-]*\\d+",
                Pattern.UNICODE_CHARACTER_CLASS);

        Map<String, Set<String>> owners = new TreeMap<>();
        int cases = 0;
        List<String> unreadable = new java.util.ArrayList<>();

        List<Path> files;
        try (Stream<Path> s = Files.list(results)) {
            files = s.filter(p -> p.getFileName().toString().endsWith("-result.json")).sorted().toList();
        }
        for (Path path : files) {
            JsonNode data;
            try {
                data = MAPPER.readTree(Files.readString(path, StandardCharsets.UTF_8));
            } catch (Exception broken) {
                // Молча пропустить нельзя: непрочитанный кейс — это НЕпроверенный кейс, а вывод
                // «атрибуция цела» читался бы как доказательство по всем. Ловилось так: файл
                // с вложенностью глубже лимита Jackson отбрасывался, и маркер, лежавший в двух
                // кейсах, считался лежащим в одном — нарушение исчезало вместе с файлом.
                unreadable.add(path.getFileName() + ": " + broken.getMessage());
                continue;
            }
            String name = data.path("fullName").asText("");
            if (name.isEmpty()) {
                name = data.path("name").asText("");
            }
            String testCase = name.isEmpty() ? path.getFileName().toString() : name;
            cases++;
            for (String step : stepNames(data.path("steps"))) {
                Matcher found = marker.matcher(step);
                while (found.find()) {
                    owners.computeIfAbsent(found.group(), any -> new LinkedHashSet<>()).add(testCase);
                }
            }
        }

        if (!unreadable.isEmpty()) {
            System.out.printf("✗ не разобрано файлов: %d — проверять по неполным данным нельзя%n",
                    unreadable.size());
            unreadable.forEach(file -> System.out.println("       └ " + file));
            return 1;
        }
        if (owners.isEmpty()) {
            // Пустой результат читался бы как «нарушений нет» — а это может значить «сбор
            // сломался» либо «шаги вообще не пишутся». Оба случая обязаны быть красными.
            System.out.printf("✗ ни одного маркера %sN в шагах (%d кейсов) — сбор сломан либо шагов нет%n",
                    prefix, cases);
            return 1;
        }

        int leaked = 0;
        for (Map.Entry<String, Set<String>> entry : owners.entrySet()) {
            boolean spread = entry.getValue().size() > 1;
            System.out.printf("  %s %s: кейсов %d%n", spread ? "❌" : "✅", entry.getKey(), entry.getValue().size());
            if (spread) {
                leaked++;
                entry.getValue().stream().sorted().forEach(c -> System.out.println("       └ " + c));
            }
        }

        if (leaked > 0) {
            System.out.printf("%n❌ АТРИБУЦИЯ НАРУШЕНА: %d маркер(ов) в чужих кейсах — шаги уехали%n", leaked);
            return 1;
        }
        if (expected != 0 && owners.size() != expected) {
            System.out.printf("%n❌ маркеров %d, а ждали %d — канал потерян, «✅» было бы ложью%n",
                    owners.size(), expected);
            return 1;
        }
        System.out.printf("%n✅ атрибуция цела: %d маркеров, каждый ровно в своём кейсе (%d кейсов)%n",
                owners.size(), cases);
        return 0;
    }

    /** Плоский список имён шагов, включая вложенные. */
    private static List<String> stepNames(JsonNode steps) {
        List<String> names = new java.util.ArrayList<>();
        for (JsonNode step : steps) {
            names.add(step.path("name").asText(""));
            names.addAll(stepNames(step.path("steps")));
        }
        return names;
    }
}
