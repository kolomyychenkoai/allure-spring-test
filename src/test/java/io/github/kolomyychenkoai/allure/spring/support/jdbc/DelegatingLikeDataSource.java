package io.github.kolomyychenkoai.allure.spring.support.jdbc;

import org.springframework.jdbc.datasource.AbstractDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;

/**
 * Обёртка над другим пулом в форме {@code DelegatingDataSource} — так устроены
 * {@code LazyConnectionDataSourceProxy} и подобные бины у потребителя.
 * <p>
 * Важен только акцессор {@link #getTargetDataSource()}: по нему обёртка узнаёт свой прокси
 * внутри чужой цепочки и не вешает второй слой. Второй слой писал бы каждый запрос в отчёт
 * дважды.
 */
public class DelegatingLikeDataSource extends AbstractDataSource {

    private final DataSource target;

    public DelegatingLikeDataSource(DataSource target) {
        this.target = target;
    }

    public DataSource getTargetDataSource() {
        return target;
    }

    @Override
    public Connection getConnection() throws SQLException {
        return target.getConnection();
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return target.getConnection(username, password);
    }
}
