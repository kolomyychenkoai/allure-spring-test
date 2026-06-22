package io.github.kolomyychenkoai.allure.spring.kafka;

import io.github.kolomyychenkoai.allure.spring.support.InMemoryAllure;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.model.TestResult;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Уровень A: проверка содержимого отчёта для Kafka consumer без брокера (логика onPoll). */
@Epic("allure-spring-test")
@Feature("Kafka")
class AllureKafkaConsumerTest {

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
    @DisplayName("полученные сообщения дают шаг «Kafka: получено N сообщ.» с вложением")
    void logsReceivedRecords() {
        TopicPartition tp = new TopicPartition("order-events", 0);
        ConsumerRecord<String, String> record =
                new ConsumerRecord<>("order-events", 0, 5L, "k1", "{\"id\":7}");
        ConsumerRecords<String, String> records = new ConsumerRecords<>(Map.of(tp, List.of(record)));

        TestResult result = allure.run("poll", () ->
                AllureKafkaConsumerInstrumentation.onPoll(records));

        assertThat(allure.hasStep(result, "Kafka: получено 1 сообщ.")).isTrue();
        assertThat(allure.attachment(result, "Принятые сообщения").orElseThrow())
                .contains("Topic: order-events")
                .contains("Key: k1")
                .contains("id");
    }

    @Test
    @DisplayName("несколько сообщений: «получено N», обе записи во вложении с разделителем")
    void logsMultipleRecords() {
        TopicPartition tp = new TopicPartition("order-events", 0);
        ConsumerRecord<String, String> r1 = new ConsumerRecord<>("order-events", 0, 5L, "k1", "v1");
        ConsumerRecord<String, String> r2 = new ConsumerRecord<>("order-events", 0, 6L, "k2", "v2");
        ConsumerRecords<String, String> records = new ConsumerRecords<>(Map.of(tp, List.of(r1, r2)));

        TestResult result = allure.run("multi", () ->
                AllureKafkaConsumerInstrumentation.onPoll(records));

        assertThat(allure.hasStep(result, "Kafka: получено 2 сообщ.")).isTrue();
        assertThat(allure.attachment(result, "Принятые сообщения").orElseThrow())
                .contains("v1").contains("v2").contains("---");
    }

    @Test
    @DisplayName("пустой poll не создаёт шаг")
    void emptyPollNoStep() {
        TestResult result = allure.run("empty", () ->
                AllureKafkaConsumerInstrumentation.onPoll(ConsumerRecords.empty()));

        assertThat(allure.attachment(result, "Принятые сообщения")).isEmpty();
    }
}
