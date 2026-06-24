package io.github.kolomyychenkoai.allure.spring.e2e;

import io.github.kolomyychenkoai.allure.spring.support.CurrentReport;
import io.github.kolomyychenkoai.allure.spring.support.KafkaListenerTestApp;
import io.github.kolomyychenkoai.allure.spring.support.KafkaListenerTestApp.RecordingKafkaListener;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.TestPropertySource;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Уровень B: приём через {@code @KafkaListener}. Контейнер крутит {@code poll} на СВОЁМ
 * потоке (активного Allure-кейса там нет) — записи буферизуются и проигрываются на
 * тест-потоке в {@code afterTestMethod} (см. {@code AllureKafkaConsumerInstrumentation} +
 * {@code AllureKafkaListener}). Контент, добавленный в {@code afterTestMethod}, из тела
 * теста не виден — проверяем в следующем упорядоченном тесте по записанному на диск файлу.
 */
@SpringBootTest(classes = KafkaListenerTestApp.class)
@EmbeddedKafka(partitions = 1, topics = "listener-events")
@TestPropertySource(properties = {
        "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
        "spring.kafka.consumer.auto-offset-reset=earliest",
        "spring.kafka.consumer.key-deserializer=org.apache.kafka.common.serialization.StringDeserializer",
        "spring.kafka.consumer.value-deserializer=org.apache.kafka.common.serialization.StringDeserializer",
        "spring.kafka.producer.key-serializer=org.apache.kafka.common.serialization.StringSerializer",
        "spring.kafka.producer.value-serializer=org.apache.kafka.common.serialization.StringSerializer"
})
@Epic("allure-spring-test")
@Feature("Kafka (@KafkaListener)")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class KafkaListenerReportIT {

    @Autowired
    private KafkaTemplate<String, String> template;

    @Autowired
    private RecordingKafkaListener listener;

    @Test
    @Order(1)
    @DisplayName("@KafkaListener принимает сообщение на потоке контейнера")
    void listenerConsumesMessage() throws Exception {
        template.send("listener-events", "k1", "{\"id\":42}");
        assertTrue(listener.latch.await(20, TimeUnit.SECONDS),
                "@KafkaListener не получил сообщение из встроенного брокера");
        // consumer-шаг буферизуется на потоке контейнера и проигрывается в afterTestMethod —
        // в теле этого теста его ещё нет; проверяем в @Order(2) по записанному результату
    }

    @Test
    @Order(2)
    @DisplayName("принятое @KafkaListener сообщение попало в отчёт предыдущего теста")
    void consumerStepWrittenToReport() {
        assertTrue(CurrentReport.anyResultFileContains("Kafka: получено"),
                "шаг приёма @KafkaListener не попал в отчёт");
        // «Offset:» есть ТОЛЬКО в consumer-вложении «Принятые сообщения» (у producer его нет),
        // вместе со значением — доказывает, что приём, а не отправка, записал id:42
        assertTrue(CurrentReport.anyResultFileContainsAll("Offset:", "\"id\":42"),
                "вложение приёма @KafkaListener (Offset + значение) не попало в отчёт");
    }
}
