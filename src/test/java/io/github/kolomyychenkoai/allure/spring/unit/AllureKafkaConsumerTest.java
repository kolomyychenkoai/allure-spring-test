package io.github.kolomyychenkoai.allure.spring.unit;

import io.github.kolomyychenkoai.allure.spring.kafka.internal.AllureKafkaConsumerInstrumentation;
import io.github.kolomyychenkoai.allure.spring.support.InMemoryAllure;
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
                .contains("\"id\":7"); // значение payload, а не короткий токен «id»
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

    @Test
    @DisplayName("null records: шаг не создаётся, не бросает")
    void nullRecordsNoStep() {
        TestResult result = allure.run("null", () ->
                AllureKafkaConsumerInstrumentation.onPoll(null));

        assertThat(allure.attachment(result, "Принятые сообщения")).isEmpty();
    }

    @Test
    @DisplayName("без активного тест-кейса poll НИЧЕГО не пишет в отчёт")
    void noStepWithoutActiveCase() {
        // setUp установил InMemoryAllure, но allure.run не вызывали → активного кейса нет
        TopicPartition tp = new TopicPartition("order-events", 0);
        ConsumerRecords<String, String> records = new ConsumerRecords<>(Map.of(tp,
                List.of(new ConsumerRecord<>("order-events", 0, 5L, "k", "v"))));

        AllureKafkaConsumerInstrumentation.onPoll(records);

        // убери гейт активного кейса → Allure.step/addAttachment запишут байты вложения → покраснеет
        assertThat(allure.wroteNothing()).isTrue();
    }

    @Test
    @DisplayName("flush БЕЗ активного кейса чистит буфер — запись @KafkaListener не утекает в следующий тест")
    void bufferedRecordDoesNotLeakWhenFlushedWithoutActiveCase() {
        // общий статический буфер изолируем от чужих записей прогона
        AllureKafkaConsumerInstrumentation.clear();
        try {
            TopicPartition tp = new TopicPartition("listener-events", 0);
            ConsumerRecord<String, String> record =
                    new ConsumerRecord<>("listener-events", 0, 9L, "k", "LEAK-MARKER");
            ConsumerRecords<String, String> records = new ConsumerRecords<>(Map.of(tp, List.of(record)));

            // приём на «чужом» потоке (активного кейса нет) — запись уходит в буфер, в отчёт пока ничего
            AllureKafkaConsumerInstrumentation.onPoll(records);
            assertThat(allure.wroteNothing()).isTrue();

            // flush БЕЗ активного кейса ОБЯЗАН очистить буфер (привязать запись не к чему).
            // Мутация: убери BUFFER.clear() в ветке «нет кейса» — запись доживёт до след. теста.
            AllureKafkaConsumerInstrumentation.flush();

            // следующий тест-кейс: буфер уже пуст → ни шага приёма, ни маркера во вложении
            TestResult next = allure.run("next-test", AllureKafkaConsumerInstrumentation::flush);
            assertThat(allure.hasStep(next, "Kafka: получено 1 сообщ."))
                    .as("запись из буфера не должна утечь в следующий тест")
                    .isFalse();
            assertThat(allure.attachment(next, "Принятые сообщения")).isEmpty();
        } finally {
            AllureKafkaConsumerInstrumentation.clear();
        }
    }
}
