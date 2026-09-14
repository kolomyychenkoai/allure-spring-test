package io.github.kolomyychenkoai.allure.spring.unit;

import io.github.kolomyychenkoai.allure.spring.internal.ActivationDiagnostics;
import io.github.kolomyychenkoai.allure.spring.internal.DiagnosticsReset;
import io.github.kolomyychenkoai.allure.spring.support.LibraryLog;
import io.qameta.allure.Epic;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.LogRecord;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Список модулей с общим буфером живёт в трёх местах: в коде (маркеры classpath), в тексте
 * новости и в README. Гейт держит их вместе.
 * <p>
 * <b>Почему не простое равенство.</b> README перечисляет не технологии, а СПОСОБЫ
 * использования: у WebTestClient небезопасен только статус-онли путь, у Kafka — только
 * consumer, а глобальный фильтр RestAssured вообще ломает не атрибуцию, а конфигурацию.
 * Присутствие класса этих различий не видит и видеть не может. Поэтому проверяются три
 * разные вещи, а исключения перечислены явно — иначе следующий редактор «починит» гейт,
 * вырезав их из README.
 */
@Epic("Внутренние проверки библиотеки")
class ParallelRunDocTest {

    private static final Path README = Path.of("README.md");

    /** Маркер classpath → как этот модуль назван человеку. Ключи обязаны совпасть с кодом. */
    private static final Map<String, String> HUMAN_NAMES = new LinkedHashMap<>(Map.of(
            "org.apache.kafka.clients.consumer.KafkaConsumer", "Kafka consumer",
            "com.github.tomakehurst.wiremock.WireMockServer", "WireMock",
            "org.springframework.test.web.reactive.server.WebTestClient", "WebTestClient",
            "ch.qos.logback.classic.LoggerContext", "Application Logs"));

    /**
     * Пункты README, которых детектор НЕ проверяет, и почему. Гейт знает о них, чтобы счёт
     * сходился, а не подгонялся вырезанием строк из документа.
     */
    private static final List<String> NOT_DETECTED = List.of("REST Assured");

    @Test
    @DisplayName("маркеры кода и человеческие имена гейта не разъехались")
    void маркерыКодаНазваныВГейте() {
        // Без этого добавленный в код маркер не потребовал бы ни строки в README, ни слова
        // в новости: гейт ниже просто не знал бы о нём.
        assertThat(HUMAN_NAMES.keySet())
                .as("в коде появился модуль с общим буфером, о котором гейт не знает")
                .isEqualTo(Set.copyOf(ActivationDiagnostics.sharedBufferMarkers()));
    }

    @Test
    @DisplayName("каждый модуль с общим буфером назван в README, и счёт пунктов сходится")
    void readmeНазываетТеЖеМодули() throws IOException {
        String block = notOkBlock();

        for (String human : HUMAN_NAMES.values()) {
            assertThat(block)
                    .as("модуль «%s» перемешивает данные под параллелью, а README о нём молчит", human)
                    .contains(human);
        }
        for (String known : NOT_DETECTED) {
            assertThat(block)
                    .as("пункт «%s» пропал из README — гейт подогнали под код вместо правды", known)
                    .contains(known);
        }

        long bullets = block.lines().filter(line -> line.strip().startsWith("- ")).count();
        assertThat(bullets)
                .as("пунктов в README %d, а известно гейту %d — список разъехался с кодом",
                        bullets, HUMAN_NAMES.size() + NOT_DETECTED.size())
                .isEqualTo(HUMAN_NAMES.size() + NOT_DETECTED.size());
    }

    @Test
    @DisplayName("новость называет те же модули, что и код")
    void новостьНазываетТеЖеМодули() {
        // noteOnce дедуплицируется НА JVM, а порядок тест-классов случайный: без сброса
        // новость мог сказать сосед, и гейт проверил бы пустоту.
        DiagnosticsReset.forget();
        String marker = ActivationDiagnostics.sharedBufferMarkers().get(0);
        List<LogRecord> said = LibraryLog.capture(
                () -> ActivationDiagnostics.noteConcurrentRunOnce(Set.of(marker)::contains, true));
        String message = said.stream()
                .filter(record -> record.getLevel() == Level.WARNING)
                .map(LogRecord::getMessage)
                .findFirst()
                .orElseThrow(() -> new AssertionError("новость не сказана — проверять нечего"));

        for (String human : HUMAN_NAMES.values()) {
            assertThat(message)
                    .as("модуль «%s» перемешивает данные, а новость о нём не говорит", human)
                    .contains(human);
        }
    }

    /** Врезка README со списком «что НЕ ОК под потоковой параллелью». */
    private static String notOkBlock() throws IOException {
        String readme = Files.readString(README, StandardCharsets.UTF_8);
        int from = readme.indexOf("*Что НЕ ОК под потоковой параллелью");
        int to = readme.indexOf("*Как запускать:*", from + 1);
        assertThat(from).as("врезка про параллель пропала из README — гейт проверяет пустоту").isPositive();
        assertThat(to).as("конец врезки не найден — гейт проверяет пустоту").isGreaterThan(from);
        return readme.substring(from, to);
    }
}
