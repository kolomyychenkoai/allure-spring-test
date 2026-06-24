package io.github.kolomyychenkoai.allure.spring.internal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Канарейки ВЕРСИОННЫХ ДОПУЩЕНИЙ инструментирования. Матчеры байткода заданы СТРОКАМИ
 * ({@code named("send")} и т.п.) — компилятор их не проверяет, поэтому при апгрейде чужой
 * библиотеки переименование/смена сигнатуры метода ломает перехват МОЛЧА (матчер просто
 * перестаёт совпадать, шаг исчезает из отчёта).
 * <p>
 * Этот тест — ЕДИНЫЙ инвентарь «что мы предполагаем про API каждой инструментируемой
 * библиотеки». При апгрейде он краснеет ТОЧЕЧНО, с указанием, какой матчер в каком
 * {@code *Instrumentation} обновить — человеку не нужно реверсить причину по вагуэ-падению IT.
 * <p>
 * Внутренние ПОЛЯ Mockito (рефлексия по приватным полям, иной механизм) канареятся
 * отдельно — см. {@code mock.AllureMockitoTest#mockitoInternalFieldsExist}.
 * Решение по самому хрупкому узлу (AssertJ) описано в {@code docs/adr/0001-assertj-instrumentation.md}.
 */
@DisplayName("Канарейки версионных допущений матчеров (апгрейд библиотек ломает молча)")
class InstrumentationApiCanaryTest {

    /** Есть ли у класса метод с именем (и опц. арностью {@code paramCount>=0} / типом arg0). */
    private static boolean hasMethod(String className, String method, int paramCount, String firstParamType) {
        try {
            Class<?> c = Class.forName(className);
            for (Method m : c.getMethods()) {
                if (!m.getName().equals(method)) {
                    continue;
                }
                if (paramCount >= 0 && m.getParameterCount() != paramCount) {
                    continue;
                }
                if (firstParamType != null && (m.getParameterCount() == 0
                        || !m.getParameterTypes()[0].getName().equals(firstParamType))) {
                    continue;
                }
                return true;
            }
            return false;
        } catch (ClassNotFoundException e) {
            return false; // класс уехал → матчер тоже мёртв
        }
    }

    /** Есть ли класс на classpath (для канареек на сам класс, а не его метод). */
    private static boolean classPresent(String className) {
        try {
            Class.forName(className);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    @Test
    @DisplayName("Web: MockMvc.perform и RestTemplate.getInterceptors (матчеры web-модуля)")
    void webMatchers() {
        assertTrue(hasMethod("org.springframework.test.web.servlet.MockMvc", "perform", -1, null),
                "MockMvc.perform уехал → обнови матчер в AllureMockMvcInstrumentation");
        assertTrue(hasMethod("org.springframework.web.client.RestTemplate", "getInterceptors", 0, null),
                "RestTemplate.getInterceptors уехал → AllureRestTemplateInstrumentation вешает интерсептор через него");
    }

    @Test
    @DisplayName("WireMock сбросы: static resetAllRequests/resetScenario/resetAllScenarios + WireMockServer resetMappings/resetRequests/resetScenarios")
    void wireMockResetMatchers() {
        String stat = "com.github.tomakehurst.wiremock.client.WireMock";
        assertTrue(hasMethod(stat, "resetAllRequests", -1, null), "WireMock.resetAllRequests уехал → AllureWireMockVerifyInstrumentation");
        assertTrue(hasMethod(stat, "resetScenario", -1, null), "WireMock.resetScenario уехал → AllureWireMockVerifyInstrumentation");
        assertTrue(hasMethod(stat, "resetAllScenarios", -1, null), "WireMock.resetAllScenarios уехал → AllureWireMockVerifyInstrumentation");
        String server = "com.github.tomakehurst.wiremock.WireMockServer";
        assertTrue(hasMethod(server, "resetMappings", -1, null), "WireMockServer.resetMappings уехал → AllureWireMockVerifyInstrumentation");
        assertTrue(hasMethod(server, "resetRequests", -1, null), "WireMockServer.resetRequests уехал → AllureWireMockVerifyInstrumentation");
        assertTrue(hasMethod(server, "resetScenarios", -1, null), "WireMockServer.resetScenarios уехал → AllureWireMockVerifyInstrumentation");
    }

    @Test
    @DisplayName("WireMock near-miss/сценарии: API снятия журнала ДО сброса (AllureWireMockSteps)")
    void wireMockNearMissApi() {
        String server = "com.github.tomakehurst.wiremock.WireMockServer";
        assertTrue(hasMethod(server, "findNearMissesForAllUnmatchedRequests", -1, null),
                "WireMockServer.findNearMissesForAllUnmatchedRequests уехал → AllureWireMockSteps.nearMisses");
        assertTrue(hasMethod(server, "getAllScenarios", -1, null),
                "WireMockServer.getAllScenarios уехал → AllureWireMockSteps.scenarios");
        String nearMiss = "com.github.tomakehurst.wiremock.verification.NearMiss";
        assertTrue(hasMethod(nearMiss, "getDiff", -1, null), "NearMiss.getDiff уехал → AllureWireMockSteps");
        assertTrue(hasMethod(nearMiss, "getRequest", -1, null), "NearMiss.getRequest уехал → AllureWireMockSteps");
        assertTrue(hasMethod(nearMiss, "getStubMapping", -1, null), "NearMiss.getStubMapping уехал → AllureWireMockSteps");
        String scenario = "com.github.tomakehurst.wiremock.stubbing.Scenario";
        assertTrue(hasMethod(scenario, "getName", -1, null), "Scenario.getName уехал → AllureWireMockSteps.scenarios");
        assertTrue(hasMethod(scenario, "getState", -1, null), "Scenario.getState уехал → AllureWireMockSteps.scenarios");
    }

    @Test
    @DisplayName("datasource-proxy: ExecutionInfo/QueryInfo и DefaultQueryLogEntryCreator.getLogEntry(...5)")
    void dataSourceProxyApi() {
        assertTrue(classPresent("net.ttddyy.dsproxy.ExecutionInfo"), "datasource-proxy ExecutionInfo уехал → AllureDataSourceListener");
        assertTrue(classPresent("net.ttddyy.dsproxy.QueryInfo"), "datasource-proxy QueryInfo уехал → AllureDataSourceListener");
        assertTrue(hasMethod("net.ttddyy.dsproxy.listener.logging.DefaultQueryLogEntryCreator", "getLogEntry", 5, null),
                "DefaultQueryLogEntryCreator.getLogEntry(ExecutionInfo, List, boolean, boolean, boolean) уехал → AllureDataSourceListener");
    }

    @Test
    @DisplayName("Mockito MockMaker: InlineByteBuddyMockMaker (дефолтный inline-maker, который оборачиваем)")
    void mockitoMockMakerPresent() {
        assertTrue(classPresent("org.mockito.internal.creation.bytebuddy.InlineByteBuddyMockMaker"),
                "InlineByteBuddyMockMaker уехал → AllureMockitoMockMaker оборачивает именно его (иначе NoClassDefFound у всех моков потребителя)");
    }

    @Test
    @DisplayName("Kafka: KafkaProducer.send(ProducerRecord, Callback) и KafkaConsumer.poll(Duration)")
    void kafkaMatchers() {
        assertTrue(hasMethod("org.apache.kafka.clients.producer.KafkaProducer", "send", 2,
                        "org.apache.kafka.clients.producer.ProducerRecord"),
                "KafkaProducer.send(ProducerRecord, Callback) уехал → обнови матчер в AllureKafkaProducerInstrumentation");
        assertTrue(hasMethod("org.apache.kafka.clients.consumer.KafkaConsumer", "poll", 1, "java.time.Duration"),
                "KafkaConsumer.poll(Duration) уехал → обнови матчер в AllureKafkaConsumerInstrumentation");
    }

    @Test
    @DisplayName("WireMock: static WireMock.{verify,stubFor,reset} и WireMockServer.{verify,stubFor,resetAll}")
    void wireMockMatchers() {
        String stat = "com.github.tomakehurst.wiremock.client.WireMock";
        assertTrue(hasMethod(stat, "verify", -1, null), "WireMock.verify уехал → AllureWireMockVerifyInstrumentation");
        assertTrue(hasMethod(stat, "stubFor", -1, null), "WireMock.stubFor уехал → AllureWireMockVerifyInstrumentation");
        assertTrue(hasMethod(stat, "reset", -1, null), "WireMock.reset уехал → AllureWireMockVerifyInstrumentation");
        String server = "com.github.tomakehurst.wiremock.WireMockServer";
        assertTrue(hasMethod(server, "verify", -1, null), "WireMockServer.verify уехал → AllureWireMockVerifyInstrumentation");
        assertTrue(hasMethod(server, "stubFor", -1, null), "WireMockServer.stubFor уехал → AllureWireMockVerifyInstrumentation");
        assertTrue(hasMethod(server, "resetAll", -1, null), "WireMockServer.resetAll уехал → AllureWireMockVerifyInstrumentation");
    }

    @Test
    @DisplayName("Spring AssertionErrors: assertEquals/assertNotEquals(3), assertTrue/False/Null/NotNull(2)")
    void springAssertionMatchers() {
        String ae = "org.springframework.test.util.AssertionErrors";
        assertTrue(hasMethod(ae, "assertEquals", 3, null), "AssertionErrors.assertEquals(3-арг) уехал → AllureSpringAssertionsInstrumentation");
        assertTrue(hasMethod(ae, "assertNotEquals", 3, null), "AssertionErrors.assertNotEquals(3-арг) уехал → AllureSpringAssertionsInstrumentation");
        assertTrue(hasMethod(ae, "assertTrue", 2, null), "AssertionErrors.assertTrue(2-арг) уехал → AllureSpringAssertionsInstrumentation");
        assertTrue(hasMethod(ae, "assertFalse", 2, null), "AssertionErrors.assertFalse(2-арг) уехал → AllureSpringAssertionsInstrumentation");
        assertTrue(hasMethod(ae, "assertNull", 2, null), "AssertionErrors.assertNull(2-арг) уехал → AllureSpringAssertionsInstrumentation");
        assertTrue(hasMethod(ae, "assertNotNull", 2, null), "AssertionErrors.assertNotNull(2-арг) уехал → AllureSpringAssertionsInstrumentation");
    }

    @Test
    @DisplayName("Hamcrest: MatcherAssert.assertThat(reason, actual, matcher) — 3-арг")
    void hamcrestMatcher() {
        assertTrue(hasMethod("org.hamcrest.MatcherAssert", "assertThat", 3, null),
                "MatcherAssert.assertThat(3-арг) уехал → обнови матчер в AllureHamcrestInstrumentation");
    }

    @Test
    @DisplayName("AssertJ: иерархия AbstractAssert и ключевые проверки (isEqualTo/startsWith/contains)")
    void assertjHierarchy() {
        // матчер isSubTypeOf(AbstractAssert) + методы-проверки в абстрактных предках;
        // если уедут — перехват неполон (см. docs/adr/0001-assertj-instrumentation.md)
        assertTrue(hasMethod("org.assertj.core.api.AbstractAssert", "isEqualTo", 1, null),
                "AbstractAssert.isEqualTo уехал → пересмотри AllureAssertJInstrumentation");
        assertTrue(hasMethod("org.assertj.core.api.AbstractCharSequenceAssert", "startsWith", 1, null),
                "AbstractCharSequenceAssert.startsWith уехал → строковые ассерты выпадут из отчёта");
        assertTrue(hasMethod("org.assertj.core.api.AbstractIterableAssert", "contains", 1, null),
                "AbstractIterableAssert.contains уехал → коллекционные ассерты выпадут из отчёта");
    }
}
