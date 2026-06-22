package io.github.kolomyychenkoai.allure.spring.e2e;

import io.github.kolomyychenkoai.allure.spring.support.KafkaTestApp;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Уровень B: «живой» прогон со встроенным Kafka-брокером (без Docker). Обычные
 * KafkaTemplate.send и KafkaConsumer.poll — НИКАКОГО Allure.step; шаги «Kafka: …»
 * рождаются сами через байткод-инструментирование. Смотреть: mvn allure:serve.
 */
@SpringBootTest(classes = KafkaTestApp.class)
@EmbeddedKafka(partitions = 1, topics = "order-events")
@org.springframework.test.context.TestPropertySource(
        properties = "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}")
@Epic("allure-spring-test")
@Feature("Kafka")
class KafkaReportIT {

    @Autowired
    private KafkaTemplate<String, String> template;

    @Autowired
    private EmbeddedKafkaBroker broker;

    @Test
    @DisplayName("отправка и приём Kafka автоматически попадают в отчёт")
    void kafkaExchangeAppearsInReport() throws Exception {
        template.send("order-events", "k1", "{\"id\":7}").get(10, TimeUnit.SECONDS);

        Map<String, Object> props = KafkaTestUtils.consumerProps("allure-group", "true", broker);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of("order-events"));
            ConsumerRecords<String, String> records = pollUntilReceived(consumer);
            assertThat(records.count()).isGreaterThan(0);
        }
    }

    private ConsumerRecords<String, String> pollUntilReceived(KafkaConsumer<String, String> consumer) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            ConsumerRecords<String, String> records = consumer.poll(Duration.ofSeconds(1));
            if (!records.isEmpty()) {
                return records;
            }
        }
        return ConsumerRecords.empty();
    }
}
