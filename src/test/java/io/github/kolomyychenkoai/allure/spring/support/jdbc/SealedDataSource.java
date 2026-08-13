package io.github.kolomyychenkoai.allure.spring.support.jdbc;

import org.springframework.jdbc.datasource.AbstractDataSource;

import java.sql.Connection;

/**
 * Пул, у которого подкласс отбрасывает уже JVM: класс запечатан, наследовать его вправе
 * только перечисленные типы.
 * <p>
 * Нужен, чтобы стеречь ПОСЛЕДНЮЮ ступень обёртки — {@code catch (Throwable)} вокруг
 * построения прокси. Все прочие негодные пулы отсекаются предпроверкой раньше, до
 * {@code ProxyFactory}, поэтому без такой фикстуры сеть безопасности можно было снять при
 * зелёной сборке — проверено мутацией.
 */
public sealed class SealedDataSource extends AbstractDataSource permits SealedDataSource.Allowed {

    @Override
    public Connection getConnection() {
        throw new UnsupportedOperationException("соединение у этой пустышки не спрашивают");
    }

    @Override
    public Connection getConnection(String username, String password) {
        return getConnection();
    }

    /** Единственный разрешённый наследник: нужен, чтобы класс вообще был запечатанным. */
    public static final class Allowed extends SealedDataSource {
    }
}
