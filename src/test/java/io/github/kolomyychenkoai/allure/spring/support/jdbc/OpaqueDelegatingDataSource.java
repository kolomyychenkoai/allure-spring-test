package io.github.kolomyychenkoai.allure.spring.support.jdbc;

import org.springframework.jdbc.datasource.AbstractDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * Декоратор, чью цель НЕЛЬЗЯ найти нашим списком акцессоров: метод называется
 * {@code getDelegate}, а не {@code getTargetDataSource}. Форма распространённая — так делают
 * самописные обёртки и часть сторонних библиотек.
 * <p>
 * Ради него в обёртке живёт второй механизм, счётчик глубины: структурная проверка тут слепа
 * по построению, а перечислить все имена, которыми чужой код называет свою цель, нельзя.
 * Делегирует СИНХРОННО — именно на такой делегации счётчик и работает.
 */
public class OpaqueDelegatingDataSource extends AbstractDataSource {

    private final DataSource delegate;

    public OpaqueDelegatingDataSource(DataSource delegate) {
        this.delegate = delegate;
    }

    public DataSource getDelegate() {
        return delegate;
    }

    @Override
    public Connection getConnection() throws SQLException {
        return delegate.getConnection();
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return delegate.getConnection(username, password);
    }

    /** Своё имя вместо {@code Класс@хэш}: identity-хэш плавает между прогонами и запрещён
     * правилом гигиены имён (см. {@code docs/acceptance-report-standard.md}). */
    @Override
    public String toString() {
        return "декоратор с чужим акцессором";
    }

}
