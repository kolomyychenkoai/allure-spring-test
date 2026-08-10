package io.github.kolomyychenkoai.allure.spring.unit;

import io.qameta.allure.Epic;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Страж карты модулей в {@code docs/architecture.md}: документ обещает ПОЛНЫЙ список точек
 * входа, и обещание проверяется в обе стороны.
 * <p>
 * Архитектурный обзор устаревает молча и тем быстрее, чем он полезнее: добавили модуль —
 * карта соврала, а узнает об этом следующий ревьюер. Точки входа берём из тех же файлов,
 * из которых их берёт Spring, поэтому разъехаться карта и код не могут.
 */
@Epic("Внутренние проверки библиотеки")
class ArchitectureDocTest {

    private static final Path DOC = Path.of("docs/architecture.md");
    private static final Path FACTORIES = Path.of("src/main/resources/META-INF/spring.factories");
    private static final Path IMPORTS = Path.of(
            "src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports");

    /** Файлы `src/main`, из которых считаются размеры документа. */
    private static List<Path> mainSources() throws IOException {
        try (var files = Files.walk(Path.of("src/main/java"))) {
            return files.filter(p -> p.toString().endsWith(".java")).toList();
        }
    }

    private static long lines(Path file) throws IOException {
        return Files.readAllLines(file, StandardCharsets.UTF_8).size();
    }

    /** Сколько файлов `src/main` содержат подстроку. */
    private static long filesContaining(String needle) throws IOException {
        long n = 0;
        for (Path p : mainSources()) {
            if (Files.readString(p, StandardCharsets.UTF_8).contains(needle)) {
                n++;
            }
        }
        return n;
    }

    /** Простые имена классов, объявленных точками входа в ресурсах Spring. */
    private static Set<String> entryPoints(Path resource) throws IOException {
        return Arrays.stream(Files.readString(resource, StandardCharsets.UTF_8).split("[,\\\\\\s]+"))
                .filter(s -> s.startsWith("io.github.kolomyychenkoai"))
                .map(s -> s.substring(s.lastIndexOf('.') + 1))
                .collect(Collectors.toCollection(TreeSet::new));
    }

    @Test
    @DisplayName("каждый TestExecutionListener и автоконфиг назван в архитектурном обзоре")
    void everyEntryPointIsOnTheMap() throws IOException {
        String doc = Files.readString(DOC, StandardCharsets.UTF_8);
        Set<String> declared = new TreeSet<>(entryPoints(FACTORIES));
        declared.addAll(entryPoints(IMPORTS));

        assertThat(declared).as("сбор точек входа сломался — проверять нечего").hasSizeGreaterThan(10);
        Set<String> missing = declared.stream().filter(c -> !doc.contains(c))
                .collect(Collectors.toCollection(TreeSet::new));
        assertThat(missing)
                .as("точка входа есть в ресурсах Spring, но не названа в docs/architecture.md — "
                        + "карта модулей врёт ровно тому, для кого написана")
                .isEmpty();
    }

    @Test
    @DisplayName("маршрут чтения ведёт в существующие классы")
    void readingRoutePointsAtRealClasses() throws IOException {
        String doc = Files.readString(DOC, StandardCharsets.UTF_8);
        // Мёртвая ссылка в маршруте дороже опечатки в тексте: ревьюер идёт по нему первым делом.
        List<String> route = List.of("AllureInstrumentation", "AllureAdviceSupport",
                "AllureAssertionsListener", "AllureMockMvcAutoConfiguration",
                "MovedCustomizerRegistrar", "AllureRepositoryAspect", "InstrumentationDiagnostics");

        for (String type : route) {
            assertThat(doc).as("класс «%s» пропал из маршрута чтения", type).contains(type);
            try (var files = Files.walk(Path.of("src/main/java"))) {
                assertThat(files.anyMatch(p -> p.getFileName().toString().equals(type + ".java")))
                        .as("маршрут ведёт в несуществующий класс «%s»", type)
                        .isTrue();
            }
        }
    }

    @Test
    @DisplayName("числа обзора пересчитываются из исходников и совпадают с текстом")
    void numbersMatchRepository() throws IOException {
        // Дрейф чисел — самый частый способ документа соврать: сборку не ломает, при чтении
        // не виден. За время работы над обзором число протухало дважды (487 → 489 тестов через
        // час после написания; объём маршрута был взят из головы). Считаем заново из исходников
        // и требуем, чтобы текст содержал именно это значение — в той фразе, где его увидит читатель.
        String doc = Files.readString(DOC, StandardCharsets.UTF_8);
        long mainLines = 0;
        for (Path p : mainSources()) {
            mainLines += lines(p);
        }
        long testClasses;
        try (var files = Files.walk(Path.of("src/test/java"))) {
            testClasses = files.filter(p -> p.toString().endsWith(".java")).count();
        }
        long internalClasses;
        try (var files = Files.list(Path.of("src/main/java/io/github/kolomyychenkoai/allure/spring/internal"))) {
            internalClasses = files.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !p.getFileName().toString().equals("package-info.java")).count();
        }

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("классы и строки src/main",
                "**%d классов / %d строк**".formatted(mainSources().size(), mainLines));
        expected.put("тест-классы", "**%d класса**".formatted(testClasses));
        expected.put("листенеры", "%d листенеров".formatted(entryPoints(FACTORIES).size()));
        expected.put("автоконфиги", "%d автоконфига".formatted(entryPoints(IMPORTS).size()));
        expected.put("классы internal", "(%d классов + `package-info`)".formatted(internalClasses));
        expected.put("объём маршрута", "Итого %d строка".formatted(routeLines()));
        expected.put("файлы со строковым матчером", "%d файлов".formatted(filesContaining("named(\"")));

        List<String> stale = expected.entrySet().stream()
                .filter(e -> !doc.contains(e.getValue()))
                .map(e -> e.getKey() + ": в тексте нет «" + e.getValue() + "»")
                .toList();
        assertThat(stale)
                .as("число в docs/architecture.md разошлось с исходниками — документ врёт молча, "
                        + "и первым это заметит читатель, а не сборка")
                .isEmpty();
    }

    /** Суммарный объём файлов «маршрута чтения»: документ обещает его одним числом. */
    private static long routeLines() throws IOException {
        long total = 0;
        for (String rel : List.of("internal/AllureInstrumentation", "internal/AllureAdviceSupport",
                "assertion/AllureAssertionsListener", "assertion/internal/AllureAssertJInstrumentation",
                "rest/AllureMockMvcAutoConfiguration", "internal/MovedCustomizerRegistrar",
                "data/internal/AllureRepositoryAspect", "internal/InstrumentationDiagnostics")) {
            total += lines(Path.of("src/main/java/io/github/kolomyychenkoai/allure/spring/" + rel + ".java"));
        }
        return total;
    }
}
