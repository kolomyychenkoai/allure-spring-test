package io.github.kolomyychenkoai.allure.spring.kafka;

import io.github.kolomyychenkoai.allure.spring.support.InMemoryAllure;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.model.TestResult;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Уровень A: проверка содержимого отчёта для Kafka producer без брокера (логика onSend). */
@Epic("allure-spring-test")
@Feature("Kafka")
class AllureKafkaProducerTest {

    private InMemoryAllure allure;

    @BeforeEach
    void setUp() {
        allure = new InMemoryAllure().install();
    }

    @AfterEach
    void tearDown() {
        allure.uninstall();
    }

    @Test
    @DisplayName("отправка даёт шаг «Kafka: отправлено → topic [key]» с вложением")
    void logsSentRecord() {
        ProducerRecord<String, String> record =
                new ProducerRecord<>("order-events", "k1", "{\"id\":7}");

        TestResult result = allure.run("send", () ->
                AllureKafkaProducerInstrumentation.onSend(record));

        assertThat(allure.hasStep(result, "Kafka: отправлено → order-events [k1]")).isTrue();
        assertThat(allure.attachment(result, "Отправленное сообщение").orElseThrow())
                .contains("Topic: order-events")
                .contains("Key: k1")
                .contains("id");
    }

    @Test
    @DisplayName("отправка без ключа: имя шага без [key], в теле Key: null")
    void logsSentRecordWithoutKey() {
        ProducerRecord<String, String> record =
                new ProducerRecord<>("order-events", "{\"id\":8}");

        TestResult result = allure.run("nokey", () ->
                AllureKafkaProducerInstrumentation.onSend(record));

        assertThat(allure.hasStep(result, "Kafka: отправлено → order-events")).isTrue();
        assertThat(allure.attachment(result, "Отправленное сообщение").orElseThrow())
                .contains("Key: null");
    }

    @Test
    @DisplayName("упавший send (синхронно): шаг НЕ создаётся (падение покажет Allure)")
    void failedSendProducesNoStep() {
        ProducerRecord<String, String> record =
                new ProducerRecord<>("order-events", "k1", "{\"id\":9}");

        TestResult result = allure.run("send-fail", () ->
                AllureKafkaProducerInstrumentation.onSend(record, new RuntimeException("брокер недоступен")));

        assertThat(result.getSteps().stream()
                .noneMatch(s -> s.getName().startsWith("Kafka: отправлено"))).isTrue();
    }

    @Test
    @DisplayName("партиция записи попадает в тело вложения")
    void logsPartition() {
        ProducerRecord<String, String> record =
                new ProducerRecord<>("order-events", 0, "k1", "{\"id\":1}");

        TestResult result = allure.run("part", () ->
                AllureKafkaProducerInstrumentation.onSend(record));

        assertThat(allure.attachment(result, "Отправленное сообщение").orElseThrow())
                .contains("Partition: 0");
    }

    @Test
    @DisplayName("null record: шаг не создаётся, не бросает")
    void nullRecordNoStep() {
        ProducerRecord<String, String> none = null;
        TestResult result = allure.run("null", () ->
                AllureKafkaProducerInstrumentation.onSend(none));

        assertThat(allure.attachment(result, "Отправленное сообщение")).isEmpty();
    }

    @Test
    @DisplayName("без активного тест-кейса send НИЧЕГО не пишет в отчёт")
    void noStepWithoutActiveCase() {
        // setUp установил InMemoryAllure, но allure.run не вызывали → активного кейса нет
        ProducerRecord<String, String> record = new ProducerRecord<>("order-events", "k1", "v");

        AllureKafkaProducerInstrumentation.onSend(record);

        // убери гейт активного кейса → Allure.step/addAttachment запишут байты вложения → покраснеет
        assertThat(allure.wroteNothing()).isTrue();
    }
}
