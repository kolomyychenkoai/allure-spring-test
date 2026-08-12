package io.github.kolomyychenkoai.allure.spring.support.jdbc;

import org.springframework.jdbc.datasource.AbstractDataSource;

import java.sql.Connection;

/**
 * Пул, у которого нельзя завести подкласс. Проверяет ступень деградации: обёртка обязана
 * вернуть исходный бин, а не бросить, — контекст потребителя дороже раздела отчёта.
 */
public final class FinalDataSource extends AbstractDataSource {

    @Override
    public Connection getConnection() {
        throw new UnsupportedOperationException("соединение у этой пустышки не спрашивают");
    }

    @Override
    public Connection getConnection(String username, String password) {
        return getConnection();
    }
}
