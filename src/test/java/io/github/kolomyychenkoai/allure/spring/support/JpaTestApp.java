package io.github.kolomyychenkoai.allure.spring.support;

import io.github.kolomyychenkoai.allure.spring.support.jpa.Widget;
import io.github.kolomyychenkoai.allure.spring.support.jpa.WidgetRepository;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Минимальное JPA-приложение для живого БД-теста: H2 in-memory, репозиторий и сущность.
 * Docker не нужен (Spring Boot сам поднимает встроенную H2).
 */
@SpringBootConfiguration
@EnableAutoConfiguration
@EntityScan(basePackageClasses = Widget.class)
@EnableJpaRepositories(basePackageClasses = WidgetRepository.class)
public class JpaTestApp {
}
