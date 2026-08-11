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

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("всего тест-классов", "%d тест-классов".formatted(all));
        expected.put("живые ReportIT", "%d `*IT`".formatted(integration));
        expected.put("обычные Test", "%d `*Test`".formatted(unitTests));
        expected.put("вспомогательные", "%d вспомогательных".formatted(all - integration - unitTests));
        expected.put("демо-классы", "%d живых `*ReportIT`".formatted(classesIn("demo")));
        expected.put("строк эталона инвентаря",
                "эталон на %d строки".formatted(Files.readAllLines(
                        Path.of("src/test/inventory/report-inventory.txt"), StandardCharsets.UTF_8).size()));
        // Сверяем по ячейке таблицы: там число стоит рядом со словом, которое не склоняется
        // от его величины. Изменится число так, что поедет падеж, — тест скажет поправить текст.
        expected.put("канарейки матчеров",
                "%d проверок про матчеры".formatted(testMethods("canary/InstrumentationApiCanaryTest")));
        expected.put("канарейки Allure", "%d про сам Allure".formatted(testMethods("canary/AllureApiCanaryTest")));

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

    /** Сколько тест-методов в классе: по аннотациям, потому что нас интересует объём проверок. */
    private static long testMethods(String relative) throws IOException {
        String source = Files.readString(TESTS.resolve(relative + ".java"), StandardCharsets.UTF_8);
        return Pattern.compile("@Test\\b").matcher(source).results().count();
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
