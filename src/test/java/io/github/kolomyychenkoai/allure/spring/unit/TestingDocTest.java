package io.github.kolomyychenkoai.allure.spring.unit;

import io.qameta.allure.Epic;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Страж чисел и ссылок в {@code docs/testing.md}.
 * <p>
 * Документ описывает набор тестов, то есть ровно ту часть репозитория, которая меняется чаще
 * всего. Без пересчёта он устареет с первым же новым тест-классом, причём молча: сборку это
 * не ломает, а при чтении незаметно. Так уже случилось с обзором архитектуры — число тестов
 * в нём разошлось с реальностью за день.
 */
@Epic("Внутренние проверки библиотеки")
class TestingDocTest {

    private static final Path DOC = Path.of("docs/testing.md");
    private static final Path TESTS = Path.of("src/test/java/io/github/kolomyychenkoai/allure/spring");

    private static String doc() throws IOException {
        return Files.readString(DOC, StandardCharsets.UTF_8);
    }

    /** Сколько .java-файлов лежит в пакете тестов (без вложенных подпакетов). */
    private static long classesIn(String pkg) throws IOException {
        try (Stream<Path> files = Files.list(TESTS.resolve(pkg))) {
            return files.filter(p -> p.toString().endsWith(".java")).count();
        }
    }

    private static long classesMatching(String suffix) throws IOException {
        try (Stream<Path> files = Files.walk(TESTS)) {
            return files.filter(p -> p.getFileName().toString().endsWith(suffix)).count();
        }
    }

    @Test
    @DisplayName("числа документа пересчитываются из репозитория и совпадают с текстом")
    void numbersMatchRepository() throws IOException {
        long all = classesMatching(".java");
        long integration = classesMatching("IT.java");
        long unitTests = classesMatching("Test.java");

        long checks = classesMatching("Check.java");
        long tests = integration + unitTests + checks;

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("файлов в src/test", "%d файлов".formatted(all));
        expected.put("тестовых классов", "%d тестовых классов".formatted(tests));
        expected.put("вспомогательных", "%d вспомогательных".formatted(all - tests));
        expected.put("суффикс IT", "%d с суффиксом `IT`".formatted(integration));
        expected.put("суффикс Test", "%d с суффиксом `Test`".formatted(unitTests));
        expected.put("суффикс Check", "%d класса с суффиксом `Check`".formatted(checks));
        // Считаем именно *ReportIT, а не все файлы demo: раньше сюда подставлялось число
        // файлов, и документ утверждал «19 живых *ReportIT», хотя их 18 плюс ReportSmokeIT.
        // Гейт закреплял собственную ошибку.
        expected.put("демо-классы", "%d классов `*ReportIT`".formatted(reportIT()));
        expected.put("строк эталона инвентаря",
                "%d строки, в гите".formatted(Files.readAllLines(
                        Path.of("src/test/inventory/report-inventory.txt"), StandardCharsets.UTF_8).size()));
        // Сверяем по ячейке таблицы: там число стоит рядом со словом, которое не склоняется
        // от его величины. Изменится число так, что поедет падеж, — тест скажет поправить текст.
        expected.put("канарейки внешнего API",
                "%d в `InstrumentationApiCanaryTest`".formatted(testMethods("canary/InstrumentationApiCanaryTest")));
        expected.put("канарейки Allure",
                "%d в `AllureApiCanaryTest`".formatted(testMethods("canary/AllureApiCanaryTest")));

        List<String> stale = expected.entrySet().stream()
                .filter(e -> {
                    try {
                        return !doc().contains(e.getValue());
                    } catch (IOException unreadable) {
                        return true;
                    }
                })
                .map(e -> e.getKey() + ": в тексте нет «" + e.getValue() + "»")
                .toList();
        assertThat(stale)
                .as("число в docs/testing.md разошлось с репозиторием — документ про тесты "
                        + "устаревает быстрее всех прочих, потому что тесты и меняются чаще всего")
                .isEmpty();
    }

    /** Сколько в `demo` именно классов `*ReportIT` — остальные файлы там тоже есть. */
    private static long reportIT() throws IOException {
        try (Stream<Path> files = Files.list(TESTS.resolve("demo"))) {
            return files.filter(p -> p.getFileName().toString().endsWith("ReportIT.java")).count();
        }
    }

    /** Сколько тест-методов в классе: по аннотациям, потому что нас интересует объём проверок. */
    private static long testMethods(String relative) throws IOException {
        String source = Files.readString(TESTS.resolve(relative + ".java"), StandardCharsets.UTF_8);
        return Pattern.compile("@Test\\b").matcher(source).results().count();
    }

    @Test
    @DisplayName("документ называет ключи пересева снапшота — без них он бесполезен мейнтейнеру")
    void inventoryFlagsAreDocumented() throws IOException {
        // Мейнтейнер приходит в документ ровно тогда, когда сверка покраснела. Не найдя ключей,
        // он пойдёт править снапшот руками — а он собирается прогоном.
        String doc = doc();
        List<String> missing = List.of("inventory.update", "inventory.strict", "inventory.counts",
                        "inventory.remove", "inventory.shapes", "inventory.compare").stream()
                .filter(flag -> !doc.contains(flag))
                .toList();
        assertThat(missing)
                .as("в docs/testing.md не названы ключи, которыми чинят упавшую сверку")
                .isEmpty();
    }

    @Test
    @DisplayName("всё, на что документ ссылается, существует: классы, пакеты, файлы, разделы")
    void everyReferenceResolves() throws IOException {
        String doc = doc();
        List<String> broken = new ArrayList<>();

        // Ссылки вида `unit/ListenerDegradationTest` и `support/InMemoryAllure`: пакет плюс класс.
        Matcher classes = Pattern.compile("`([a-z]+(?:/[a-z]+)*)/([A-Z][A-Za-z]+)`").matcher(doc);
        while (classes.find()) {
            Path file = TESTS.resolve(classes.group(1)).resolve(classes.group(2) + ".java");
            if (!Files.exists(file)) {
                broken.add("класс «" + classes.group(1) + "/" + classes.group(2) + "» упомянут, но файла нет");
            }
        }

        Matcher paths = Pattern.compile("`((?:docs|scripts|src)/[A-Za-z0-9_./*-]+)`").matcher(doc);
        while (paths.find()) {
            String path = paths.group(1);
            if (!path.contains("*") && !Files.exists(Path.of(path))) {
                broken.add("путь «" + path + "» упомянут, но его нет");
            }
        }

        Matcher sections = Pattern.compile("§(\\d+)").matcher(doc);
        while (sections.find()) {
            if (!doc.contains("## " + sections.group(1) + ". ")) {
                broken.add("ссылка на §" + sections.group(1) + ", а такого раздела нет");
            }
        }

        assertThat(broken)
                .as("документ ссылается в пустоту — читатель пойдёт по ссылке и не найдёт ничего")
                .isEmpty();
    }
}
