package io.github.kolomyychenkoai.allure.spring.support;

import io.github.kolomyychenkoai.allure.spring.support.jpa.Widget;
import io.github.kolomyychenkoai.allure.spring.support.jpa.WidgetRepository;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Минимальное JPA-приложение для живого БД-теста: H2 in-memory, репозиторий и сущность.
 * Docker не нужен (Spring Boot сам поднимает встроенную H2).
 * <p>
 * {@code @EntityScan} здесь НЕ нужен: {@link Widget} лежит в подпакете {@code support.jpa},
 * а сканирование по умолчанию идёт от пакета этой конфигурации и ниже. Аннотация была лишней
 * и при этом ПЕРЕЕХАЛА в Boot 4 ({@code autoconfigure.domain} → {@code persistence.autoconfigure}) —
 * без неё этот общий support-класс компилируется на обоих мажорах.
 */
@SpringBootConfiguration
@EnableAutoConfiguration
@EnableJpaRepositories(basePackageClasses = WidgetRepository.class)
@org.springframework.context.annotation.ComponentScan(basePackageClasses = WidgetRepository.class)
public class JpaTestApp {
}
