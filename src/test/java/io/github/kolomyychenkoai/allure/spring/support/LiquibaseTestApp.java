package io.github.kolomyychenkoai.allure.spring.support;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;

/**
 * Минимальное приложение для живого теста Liquibase: H2 in-memory + Liquibase на старте.
 * Сам прогон миграций включается точечно через {@code @TestPropertySource}
 * ({@code spring.liquibase.enabled=true} + путь к changelog) — глобально в тестах Liquibase
 * выключен (см. {@code application.yml}), чтобы не запускался в остальных Boot-контекстах.
 */
@SpringBootConfiguration
@EnableAutoConfiguration
public class LiquibaseTestApp {
}
