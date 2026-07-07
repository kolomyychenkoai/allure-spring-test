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
            CurrentReport.check(records.count() > 0, () -> "сообщение не получено из брокера");
        }

        List<String> steps = CurrentReport.stepNames();
        CurrentReport.check(steps.stream().anyMatch(n -> n.startsWith("Kafka: отправлено → order-events") && n.contains("k1")),
                () -> "" + steps);
        CurrentReport.check(steps.stream().anyMatch(n -> n.startsWith("Kafka: получено")), () -> "" + steps);
        // одна отправка = РОВНО один шаг (CAS-дедуп install не задвоил advice)
        long sentCount = steps.stream().filter(n -> n.startsWith("Kafka: отправлено → order-events")).count();
        CurrentReport.check(sentCount == 1, () -> "ожидался ровно один шаг отправки (CAS), а есть " + sentCount + ": " + steps);

        // содержимое вложений (topic/key/value) через реальную цепочку
        // метаданные (topic/key) — в «Отправленное сообщение», а само значение переехало
        // в отдельное вложение «Значение сообщения» (application/json — Allure форматирует)
        String sent = CurrentReport.attachmentContent("Отправленное сообщение").orElse("");
        CurrentReport.check(sent.contains("Topic: order-events"), () -> "sent meta: " + sent);
        String sentValue = CurrentReport.attachmentContent("Значение сообщения").orElse("");
        CurrentReport.check(sentValue.contains("\"id\": 7"), () -> "sent value: " + sentValue); // развёрнуто
        String sentValueType = CurrentReport.attachmentType("Значение сообщения").orElse("");
        CurrentReport.check(sentValueType.equals("application/json"),
                () -> "«Значение сообщения» должно быть application/json, а было: " + sentValueType);
        String got = CurrentReport.attachmentContent("Принятые сообщения").orElse("");
        CurrentReport.check(got.contains("Topic: order-events"), () -> "received meta: " + got);
        // value ПРИНЯТОГО тоже вынесен отдельным «Значение сообщения» (как sent) → таких вложений ДВА
        long valueAtt = CurrentReport.attachmentNames().stream().filter("Значение сообщения"::equals).count();
        CurrentReport.check(valueAtt == 2,
                () -> "ожидалось 2 «Значение сообщения» (sent+received): " + CurrentReport.attachmentNames());
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
