package io.github.kolomyychenkoai.allure.spring.unit;

import io.github.kolomyychenkoai.allure.spring.support.HiddenClassLoader;
import io.github.kolomyychenkoai.allure.spring.support.ListenerLifecycle;
import io.qameta.allure.Epic;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Инвариант «нет библиотеки → нет падения» для КАЖДОГО листенера из {@code spring.factories}.
 * <p>
 * Почему это важнее, чем кажется. Листенеры регистрируются у потребителя ВСЕГДА — он их не
 * подключает и не может отключить. Значит любое обращение к классу отсутствующей библиотеки
 * даёт {@link NoClassDefFoundError} прямо из хука жизненного цикла, и падает не «наша фича»,
 * а ВЕСЬ его тест-класс. Это худший вид дефекта: библиотека для отчётов роняет чужие тесты.
 * <p>
 * Раньше такие тесты были у 4 модулей из 15 — и в непокрытых нашлись три настоящих дефекта
 * (AssertJ без гейта, WebTestClient без гейта, Kafka с гейтом только в одном хуке из трёх).
 * <p>
 * Две оси: нет ПРОФИЛЬНОЙ библиотеки модуля и нет byte-buddy (он опционален для всех).
 * Хуки дёргаются ВСЕ семь — гейт в одном хуке и дыра в соседнем это ровно то, что тут ловится.
 *
 * @see HiddenClassLoader почему не FilteredClassLoader из Spring Boot
 */
@Epic("Внутренние проверки библиотеки")
class ListenerDegradationTest {

    private static final String PACKAGE = "io.github.kolomyychenkoai.allure.spring.";

    /** Сценарий: у потребителя нет классов {@code hidden}, листенер обязан промолчать. */
    private record Scenario(String listener, String about, List<String> hidden) {
        @Override
        public String toString() {
            return listener.substring(listener.lastIndexOf('.') + 1) + ": " + about;
        }
    }

    private static Scenario scenario(String listener, String about, String... hidden) {
        return new Scenario(PACKAGE + listener, about, List.of(hidden));
    }

    /** По одному ряду на профильную библиотеку модуля. Ассерты — по ряду на каждую из четырёх. */
    private static List<Scenario> libraryAbsent() {
        return List.of(
                scenario("logs.AllureApplicationLogsListener", "нет Logback", "ch.qos.logback."),
                scenario("rest.AllureRestAssuredListener", "нет RestAssured", "io.restassured."),
                scenario("rest.AllureMockMvcListener", "нет MockMvc", "org.springframework.test.web.servlet."),
                scenario("rest.AllureRestTemplateListener", "нет RestTemplate", "org.springframework.web.client.RestTemplate"),
                scenario("rest.AllureRestClientListener", "нет RestClient", "org.springframework.web.client.RestClient"),
                scenario("rest.AllureWebTestClientListener", "нет WebTestClient", "org.springframework.test.web.reactive."),
                scenario("wiremock.AllureWireMockTestListener", "нет WireMock", "com.github.tomakehurst."),
                scenario("assertion.AllureAssertionsListener", "нет AssertJ", "org.assertj.core."),
                scenario("assertion.AllureAssertionsListener", "нет Hamcrest", "org.hamcrest."),
                scenario("assertion.AllureAssertionsListener", "нет Jupiter Assertions", "org.junit.jupiter.api.Assertions"),
                scenario("assertion.AllureAssertionsListener", "нет Spring-ассертов", "org.springframework.test.util.AssertionErrors"),
                scenario("kafka.AllureKafkaListener", "нет Kafka", "org.apache.kafka.", "org.springframework.kafka."),
                scenario("data.AllureJdbcListener", "нет spring-jdbc", "org.springframework.jdbc."),
                scenario("liquibase.AllureLiquibaseListener", "нет Liquibase", "liquibase."),
                scenario("awaitility.AllureAwaitilityListener", "нет Awaitility", "org.awaitility."));
    }

    /** byte-buddy опционален для ВСЕХ листенеров — ось общая, поэтому берётся из spring.factories. */
    private static List<Scenario> byteBuddyAbsent() {
        return registeredListeners().stream()
                .map(listener -> new Scenario(listener, "нет byte-buddy", List.of("net.bytebuddy.")))
                .toList();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("libraryAbsent")
    @DisplayName("нет профильной библиотеки — листенер молчит, а не роняет тест потребителя")
    void survivesWithoutLibrary(Scenario scenario) {
        assertNoThrow(scenario);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("byteBuddyAbsent")
    @DisplayName("нет byte-buddy — листенер молчит во ВСЕХ хуках, а не только в beforeTestClass")
    void survivesWithoutByteBuddy(Scenario scenario) {
        assertNoThrow(scenario);
    }

    /**
     * Загрузчик СВОЙ на сценарий и закрывается сразу. Общий кэшированный загрузчик пробовали —
     * вышло вдвое медленнее (16 с против 9,6 с): в нём копятся классы всех листенеров сразу,
     * тогда как одноразовый грузит только то, что нужно одному, и тут же отдаёт память.
     */
    private static void assertNoThrow(Scenario scenario) {
        assertThatCode(() -> {
            try (HiddenClassLoader loader = HiddenClassLoader.hiding(scenario.hidden().toArray(String[]::new))) {
                ListenerLifecycle.runAllHooks(loader, scenario.listener());
            }
        }).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("каждый листенер из spring.factories покрыт сценарием «библиотеки нет»")
    void everyListenerCovered() {
        // Страж от повторения истории: дефекты дожили до третьего круга ровно потому, что
        // покрытие было выборочным. Новый листенер без сценария — красная сборка, а не «забыли».
        Set<String> covered = libraryAbsent().stream().map(Scenario::listener).collect(Collectors.toSet());
        // config-листенер профильной библиотеки не имеет (только Spring и Allure) — его ось
        // единственная, byte-buddy, и она покрыта вторым тестом.
        covered.add(PACKAGE + "config.AllureConfigurationListener");

        assertThat(registeredListeners())
                .as("листенеры из META-INF/spring.factories без теста «библиотеки нет»")
                .allSatisfy(listener -> assertThat(covered).contains(listener));
    }

    /** Листенеры из настоящего {@code spring.factories} — источник правды один (живёт в support). */
    private static Set<String> registeredListeners() {
        Set<String> listeners = ListenerLifecycle.registeredListeners(
                ListenerDegradationTest.class.getClassLoader());
        assertThat(listeners).as("META-INF/spring.factories должен быть на classpath").isNotEmpty();
        return listeners;
    }
}
