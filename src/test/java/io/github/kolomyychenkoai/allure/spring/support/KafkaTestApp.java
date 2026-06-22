package io.github.kolomyychenkoai.allure.spring.support;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;

/**
 * Минимальное приложение для живого Kafka-теста: автоконфигурация поднимает
 * KafkaTemplate, брокер — встроенный (@EmbeddedKafka), Docker не нужен.
 */
@SpringBootConfiguration
@EnableAutoConfiguration
public class KafkaTestApp {
}
