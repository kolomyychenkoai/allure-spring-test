package io.github.kolomyychenkoai.allure.spring.demo;

import io.github.kolomyychenkoai.allure.spring.support.CurrentReport;
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

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Уровень B: «живой» прогон со встроенным Kafka-брокером через РЕАЛЬНЫЙ байткод-перехват
 * (spring.factories → инструментирование KafkaProducer/KafkaConsumer). send/poll пишут шаги
 * в настоящий отчёт (showcase); тест читает их через {@link CurrentReport}. Краснеет, если
 * перехват send/poll сломан или имена шагов съехали.
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
        Map<String, Object> props = KafkaTestUtils.consumerProps("allure-group", "true", broker);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

        // повторный install() — CAS-гард обязан сделать no-op; если CAS убрать, advice навесится
        // второй раз и одна отправка даст ДВА шага (см. assert «ровно один» ниже)
        io.github.kolomyychenkoai.allure.spring.kafka.internal.AllureKafkaProducerInstrumentation.install();

        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of("order-events"));
            template.send("order-events", "k1", "{\"id\":7}").get(10, TimeUnit.SECONDS);
            ConsumerRecords<String, String> records = pollUntilReceived(consumer);
            assertTrue(records.count() > 0, "сообщение не получено из брокера");
        }

        List<String> steps = CurrentReport.stepNames();
        assertTrue(steps.stream().anyMatch(n -> n.startsWith("Kafka: отправлено → order-events") && n.contains("k1")),
                () -> "" + steps);
        assertTrue(steps.stream().anyMatch(n -> n.startsWith("Kafka: получено")), () -> "" + steps);
        // одна отправка = РОВНО один шаг (CAS-дедуп install не задвоил advice)
        long sentCount = steps.stream().filter(n -> n.startsWith("Kafka: отправлено → order-events")).count();
        assertTrue(sentCount == 1, () -> "ожидался ровно один шаг отправки (CAS), а есть " + sentCount + ": " + steps);

        // содержимое вложений (topic/key/value) через реальную цепочку
        String sent = CurrentReport.attachmentContent("Отправленное сообщение").orElse("");
        assertTrue(sent.contains("Topic: order-events") && sent.contains("\"id\":7"), () -> "sent: " + sent);
        String got = CurrentReport.attachmentContent("Принятые сообщения").orElse("");
        assertTrue(got.contains("\"id\":7"), () -> "received: " + got);
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
