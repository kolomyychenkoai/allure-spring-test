package io.github.kolomyychenkoai.allure.spring.support.jdbc;

import org.springframework.jdbc.datasource.AbstractDataSource;

import java.sql.Connection;

/**
 * Пул с {@code final}-методом. Подкласс завести можно, а перехватить такой метод нельзя: он
 * выполнился бы на экземпляре без конструктора и тихо вернул бы {@code null} вместо значения.
 * Поэтому такой класс не проксируется вовсе.
 */
public class FinalMethodDataSource extends AbstractDataSource {

    private final String stamp = "настоящее значение";

    /** Метод, который CGLIB не переопределит: читает поле, поэтому подмена была бы заметна. */
    public final String stamp() {
        return stamp;
    }

    @Override
    public Connection getConnection() {
        throw new UnsupportedOperationException("соединение у этой пустышки не спрашивают");
    }

    @Override
    public Connection getConnection(String username, String password) {
        return getConnection();
    }
}
