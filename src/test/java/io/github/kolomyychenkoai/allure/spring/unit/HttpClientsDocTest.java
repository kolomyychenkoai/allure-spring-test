package io.github.kolomyychenkoai.allure.spring.unit;

import io.qameta.allure.Epic;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * README перечисляет клиентов, для которых пишется HTTP-шаг со стороны клиента. Список обязан
 * совпадать с тем, кто эти шаги реально пишет.
 * <p>
 * Источник правды — эталон отчёта {@code src/test/inventory/report-inventory.txt}: он пересевается
 * из настоящего прогона и называет автора каждого вида шага. Поэтому гейт не тавтологичен:
 * справа от сравнения стоит факт прогона, а не вторая копия списка.
 * <p>
 * Появится модуль перехвата ещё одного клиента (Feign) — эталон пополнится, карта имён внизу
 * потребует правки, а вместе с ней и README. Без этого список в документе разошёлся бы молча:
 * ровно тот класс дефекта, ради которого эталон и заведён.
 */
@Epic("Внутренние проверки библиотеки")
class HttpClientsDocTest {

    private static final Path README = Path.of("README.md");
    private static final Path INVENTORY = Path.of("src/test/inventory/report-inventory.txt");

    /** Автор шага в эталоне → как этот клиент назван человеку в README. */
    private static final Map<String, String> CLIENT_NAMES = new LinkedHashMap<>(Map.of(
            "AllureMockMvcInstrumentation", "MockMvc",
            "AllureMockMvcResultHandler", "MockMvc",
            "AllureRestAssuredFilter", "REST Assured",
            "AllureRestClientInstrumentation", "RestClient",
            "AllureRestTemplateInterceptor", "RestTemplate",
            "AllureWebTestClientLogger", "WebTestClient"));

    @Test
    @DisplayName("авторы HTTP-шагов из эталона известны гейту")
    void авторыШаговИзвестныГейту() {
        // Без этого новый модуль перехвата не потребовал бы ни строки в README: гейт ниже
        // просто не знал бы, как называть его клиента.
        assertThat(stepAuthors())
                .as("в эталоне появился автор HTTP-шагов, о котором гейт не знает")
                .isEqualTo(CLIENT_NAMES.keySet());
    }

    @Test
    @DisplayName("README называет ровно тех клиентов, что пишут HTTP-шаги в эталоне")
    void readmeНазываетТехЖеКлиентов() {
        String limits = limitsEntry();

        for (String author : stepAuthors()) {
            String client = CLIENT_NAMES.get(author);
            assertThat(limits)
                    .as("для клиента «%s» шаг пишется (автор %s), а README о нём в этом месте молчит",
                            client, author)
                    .contains(client);
        }
    }

    /** Кто пишет виды шагов «HTTP …» в эталоне отчёта. */
    private static Set<String> stepAuthors() {
        Set<String> authors = new TreeSet<>();
        for (String line : read(INVENTORY).lines().toList()) {
            int marker = line.indexOf("# ");
            if (line.contains("| HTTP ") && marker > 0) {
                authors.add(line.substring(marker + 2).split("[\\s(]")[0].trim());
            }
        }
        assertThat(authors)
                .as("в эталоне не нашлось ни одного автора HTTP-шага — гейт проверял бы пустоту")
                .isNotEmpty();
        return authors;
    }

    /** Врезка README про то, каким клиентом ходит тест. */
    private static String limitsEntry() {
        String readme = read(README);
        int from = readme.indexOf("- **Пусто в разделе исходящих HTTP?");
        assertThat(from).as("врезка про исходящий HTTP пропала из README — гейт проверял бы пустоту")
                .isPositive();
        int to = readme.indexOf("\n- **", from + 1);
        assertThat(to).as("конец врезки не найден — гейт проверял бы пустоту").isGreaterThan(from);
        return readme.substring(from, to);
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            throw new UncheckedIOException(unreadable);
        }
    }
}
