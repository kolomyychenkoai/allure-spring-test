package io.github.kolomyychenkoai.allure.spring.support;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.annotation.KafkaListener;

import java.util.concurrent.CountDownLatch;

/**
 * Приложение для живого теста приёма через {@code @KafkaListener}: обработчик ловит
 * сообщение на потоке контейнера-слушателя (НЕ на тест-потоке) — это путь, который
 * раньше не давал ни одного consumer-шага. Брокер — встроенный ({@code @EmbeddedKafka}).
 */
@SpringBootConfiguration
@EnableAutoConfiguration
@EnableKafka
public class KafkaListenerTestApp {

    @Bean
    public RecordingKafkaListener recordingKafkaListener() {
        return new RecordingKafkaListener();
    }

    /** Обработчик {@code @KafkaListener}: считает latch, чтобы тест мог дождаться приёма. */
    public static class RecordingKafkaListener {

        public final CountDownLatch latch = new CountDownLatch(1);
        public volatile String lastValue;

        @KafkaListener(topics = "listener-events", groupId = "allure-listener-group")
        public void onMessage(String value) {
            this.lastValue = value;
            latch.countDown();
        }
    }
}
