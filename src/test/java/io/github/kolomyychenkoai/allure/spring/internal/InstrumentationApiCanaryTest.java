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
