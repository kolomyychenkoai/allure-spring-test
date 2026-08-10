package io.github.kolomyychenkoai.allure.spring.unit;

import io.qameta.allure.Epic;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
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
}
