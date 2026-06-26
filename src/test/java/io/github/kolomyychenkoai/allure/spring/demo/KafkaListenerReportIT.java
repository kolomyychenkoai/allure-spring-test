package io.github.kolomyychenkoai.allure.spring.demo;

import io.github.kolomyychenkoai.allure.spring.support.CurrentReport;
import io.github.kolomyychenkoai.allure.spring.support.KafkaListenerTestApp;
import io.github.kolomyychenkoai.allure.spring.support.KafkaListenerTestApp.RecordingKafkaListener;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
class KafkaListenerReportIT {

    @Autowired
    private KafkaTemplate<String, String> template;

    @Autowired
    private RecordingKafkaListener listener;

    @Test
    @DisplayName("@KafkaListener принимает сообщение на потоке контейнера")
    void listenerConsumesMessage() throws Exception {
        template.send("listener-events", "k1", "{\"id\":42}");
        assertTrue(listener.latch.await(20, TimeUnit.SECONDS),
                "@KafkaListener не получил сообщение из встроенного брокера");
        // consumer-шаг буферизуется на потоке контейнера и проигрывается в afterTestMethod —
        // в теле этого теста его ещё нет; проверяем в @AfterAll по записанному результату
    }

    @AfterAll
    @DisplayName("принятое @KafkaListener сообщение попало в отчёт")
    static void consumerStepWrittenToReport() {
        // Маркер ОБЯЗАН быть уникальным по всему прогону: шаг «Kafka: получено» и фрагменты
        // вложения пишет и KafkaReportIT (прямой poll по topic order-events) в ОБЩИЙ
        // allure-results. Привязываемся к consumer-вложению «Принятые сообщения» ИМЕННО этого
        // теста: его содержимое co-located в ОДНОМ файле-вложении и уникально — topic
        // listener-events + payload id:42 (producer-тест шлёт id:7 в order-events). Так ассерт
        // краснеет, если replay-путь @KafkaListener (буфер на потоке контейнера → flush в
        // afterTestMethod) сломан. (Имя шага «Kafka: получено» лежит в result.json, а topic/
        // payload — в отдельном файле-вложении, поэтому co-located маркеры берём из вложения.)
        assertTrue(CurrentReport.anyResultFileContainsAll("Topic: listener-events", "\"id\":42"),
                "вложение приёма @KafkaListener (topic listener-events + payload id:42) не попало в отчёт");
        // «Offset:» есть ТОЛЬКО в consumer-вложении (у producer его нет) — вместе с нашим topic
        // доказывает, что это ПРИЁМ, а не отправка, и привязан к listener-events
        assertTrue(CurrentReport.anyResultFileContainsAll("Offset:", "Topic: listener-events"),
                "consumer-вложение (Offset для topic listener-events) не попало в отчёт");
    }
}
