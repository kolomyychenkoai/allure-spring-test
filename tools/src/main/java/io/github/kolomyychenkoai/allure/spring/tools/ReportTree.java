package io.github.kolomyychenkoai.allure.spring.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Дерево витринных тестов: класс → тест → шаги с вложенностью и вложениями.
 * <p>
 * Инструмент прохода 2.6 из {@code docs/review-playbook.md}: отчёт положено пройти глазами
 * так, как читает ручной тестировщик, — не открывая код тестов. Серии одинаковых шагов
 * подряд подсвечиваются отдельно: в них тонет смысловой шаг. Это лупа, а не гейт —
 * читаемость машиной не проверяется, решает человек.
 */
final class ReportTree {

    private static final String INTERNAL = "Внутренние проверки библиотеки";

    /** Серия одинаковых шагов подряд, с которой начинается шум. */
    private static final int RUN = 5;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ReportTree() {
    }

    /** Один тест отчёта: эпик и класс вынуты из меток, остальное лежит в узле. */
    private record Case(String epic, String testClass, JsonNode node) {
        String name() {
            return node.path("name").asText("");
        }
    }

    /** Строка дерева: сам текст, хвост со статусом и вложениями, и голое имя шага. */
    private record Line(String text, String tail, String stepName) {
    }

    /** Счётчики обхода: живут ровно один запуск, поэтому передаются, а не лежат в статике. */
    private static final class Totals {
        private int steps;
        private int attachments;
    }

    static int run(String[] args) throws IOException {
        // Аргументы разбираем строго: неизвестный флаг раньше проглатывался молча, и
        // инструмент печатал витрину вместо запрошенного. Тихо делать не то, о чём попросили,
        // хуже, чем отказаться.
        String results = null;
        boolean showAll = false;
        for (String arg : args) {
            if ("--all".equals(arg)) {
                showAll = true;
            } else if (arg.startsWith("-")) {
                System.err.println("неизвестный аргумент: " + arg + " (ожидается --all)");
                return 2;
            } else if (results == null) {
                results = arg;
            } else {
                System.err.println("каталог результатов указан дважды: " + results + " и " + arg);
                return 2;
            }
        }
        Path resultsDir = Path.of(results == null ? "target/allure-results" : results);
        if (!Files.isDirectory(resultsDir)) {
            System.err.println("нет каталога результатов: " + resultsDir);
            return 2;
        }

        Totals totals = new Totals();
        List<Case> tests = read(resultsDir);
        StringBuilder out = new StringBuilder();

        out.append("=".repeat(100)).append('\n');
        out.append("ОТЧЁТ: %d тестов".formatted(tests.size())).append('\n');
        // Порядок эпиков: по убыванию числа тестов, равные — в порядке первой встречи.
        Map<String, Integer> byEpic = new LinkedHashMap<>();
        for (Case c : tests) {
            byEpic.merge(c.epic(), 1, Integer::sum);
        }
        byEpic.entrySet().stream()
                .sorted(Comparator.comparingInt(Map.Entry<String, Integer>::getValue).reversed())
                .forEach(e -> out.append("   %5d  %s%s".formatted(e.getValue(), e.getKey(),
                        INTERNAL.equals(e.getKey()) ? "" : "   ← витрина, её и читает тестировщик")).append('\n'));

        List<String> noisy = new ArrayList<>();
        boolean withInternal = showAll;
        List<Case> shown = tests.stream()
                .filter(c -> withInternal || !INTERNAL.equals(c.epic()))
                .sorted(Comparator.comparing(Case::testClass).thenComparing(Case::name))
                .toList();

        String currentClass = null;
        for (Case c : shown) {
            if (!c.testClass().equals(currentClass)) {
                currentClass = c.testClass();
                out.append('\n').append("=".repeat(100)).append('\n');
                out.append(currentClass).append('\n');
            }
            out.append("  ТЕСТ: %s%s".formatted(c.name(), braces(names(c.node().path("attachments")))))
                    .append('\n');

            List<Line> lines = new ArrayList<>();
            walk(c.node(), 2, lines, totals);
            for (Line line : lines) {
                out.append(line.text()).append(line.tail()).append('\n');
            }
            collectNoisy(lines, currentClass, c.name(), noisy);
        }

        out.append('\n').append("=".repeat(100)).append('\n');
        out.append("ИТОГО: шагов %d, вложений %d".formatted(totals.steps, totals.attachments)).append('\n');
        if (noisy.isEmpty()) {
            out.append("\nСерий одинаковых шагов от %d подряд нет.".formatted(RUN)).append('\n');
        } else {
            out.append("\nСЮДА СМОТРЕТЬ — серии одинаковых шагов от %d подряд (смысловой шаг тонет):"
                    .formatted(RUN)).append('\n');
            noisy.forEach(n -> out.append(n).append('\n'));
            out.append("   Решает человек: это может быть служебная кухня инструмента (тогда вопрос —").append('\n');
            out.append("   увидит ли такое потребитель) либо реальный дефект имён.").append('\n');
        }
        out.append("""

                Что проверять глазами (docs/acceptance-report-standard.md):
                  · понятно ли ПО ИМЕНАМ, что проверялось, — не открывая код теста;
                  · верна ли вложенность (SQL внутри вызова репозитория, тела внутри HTTP-шага);
                  · нет ли технического мусора: Класс@хэш, [B@…, сырой toString;
                  · нет ли шагов, чьё имя не отвечает «что именно проверили».""").append('\n');

        System.out.print(out);
        return 0;
    }

    private static List<Case> read(Path results) throws IOException {
        List<Path> files;
        try (Stream<Path> s = Files.list(results)) {
            files = s.filter(p -> p.getFileName().toString().endsWith("-result.json")).sorted().toList();
        }
        List<Case> tests = new ArrayList<>();
        for (Path p : files) {
            JsonNode node = MAPPER.readTree(Files.readString(p, StandardCharsets.UTF_8));
            Map<String, String> labels = new LinkedHashMap<>();
            for (JsonNode label : node.path("labels")) {
                labels.put(label.path("name").asText(), label.path("value").asText());
            }
            String epic = labels.getOrDefault("epic", "");
            String cls = labels.getOrDefault("testClass", "?");
            tests.add(new Case(epic.isEmpty() ? "—" : epic,
                    cls.substring(cls.lastIndexOf('.') + 1), node));
        }
        return tests;
    }

    private static void walk(JsonNode node, int depth, List<Line> out, Totals totals) {
        for (JsonNode step : node.path("steps")) {
            totals.steps++;
            List<String> files = names(step.path("attachments"));
            totals.attachments += files.size();
            String status = "passed".equals(step.path("status").asText(""))
                    ? ""
                    : "  [%s]".formatted(statusOf(step).toUpperCase());
            String name = step.path("name").asText("");
            out.add(new Line("    ".repeat(depth) + "• " + name, status + braces(files), name));
            walk(step, depth + 1, out, totals);
        }
    }

    private static String statusOf(JsonNode step) {
        String status = step.path("status").asText("");
        return status.isEmpty() ? "?" : status;
    }

    private static List<String> names(JsonNode attachments) {
        List<String> names = new ArrayList<>();
        for (JsonNode a : attachments) {
            names.add(a.path("name").asText("?"));
        }
        return names;
    }

    private static String braces(List<String> names) {
        return names.isEmpty() ? "" : "   {%s}".formatted(String.join(", ", names));
    }

    /** Серия одинаковых имён подряд отмечается ОДИН раз — на пятом совпадении. */
    private static void collectNoisy(List<Line> lines, String cls, String test, List<String> noisy) {
        String previous = null;
        int run = 0;
        for (Line line : lines) {
            if (line.stepName().equals(previous)) {
                run++;
                if (run + 1 == RUN) {
                    noisy.add("   %s :: %s → «%s»".formatted(cls, cut(test, 50), cut(line.stepName(), 60)));
                }
            } else {
                previous = line.stepName();
                run = 0;
            }
        }
    }

    /**
     * Обрезка по КОДОВЫМ ТОЧКАМ, а не по символам UTF-16.
     * <p>
     * `substring` разрезал бы суррогатную пару пополам, и в вывод попала бы её половина —
     * ровно то, на чём падал прежний питон. Плюс питон резал по кодовым точкам, а расхождение
     * в длине сломало бы побайтовую сверку старого и нового вывода.
     */
    private static String cut(String value, int limit) {
        return value.codePointCount(0, value.length()) <= limit
                ? value
                : value.substring(0, value.offsetByCodePoints(0, limit));
    }
}
