package io.github.kolomyychenkoai.allure.spring.support;

import org.springframework.boot.SpringBootConfiguration;

/**
 * Минимальная конфигурация для {@code @SpringBootTest}: ровно столько, чтобы поднялся
 * Spring-контекст с Environment. Ни веба, ни БД, ни Kafka — Docker не нужен.
 */
@SpringBootConfiguration
public class TestApp {
}
