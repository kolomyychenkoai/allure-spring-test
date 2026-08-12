package io.github.kolomyychenkoai.allure.spring.support.jdbc;

import java.sql.Connection;
import java.util.Properties;

/**
 * Пул с чужими перегрузками {@code getConnection} — форма Oracle UCP, где
 * {@code getConnection(Properties)} принимает метки соединения.
 * <p>
 * Такой пул ловит две ошибки сразу, если перехват отбирает методы по ИМЕНИ, а аргументы
 * разбирает позиционно: перегрузка с одним аргументом падает приведением к {@code String}
 * в коде потребителя, а перегрузка с тремя молча теряет третий аргумент вместе со смыслом
 * вызова.
 */
public class ExtraOverloadDataSource extends FakeDataSource {

    public ExtraOverloadDataSource(String name) {
        super(name);
    }

    /** Метки соединения: сюда наш перехват лезть не должен. */
    public Connection getConnection(Properties labels) {
        return rawConnection();
    }

    /** Логин, пароль и метки: третий аргумент выбрасывать нельзя. */
    public Connection getConnection(String username, String password, Properties labels) {
        return rawConnection();
    }
}
