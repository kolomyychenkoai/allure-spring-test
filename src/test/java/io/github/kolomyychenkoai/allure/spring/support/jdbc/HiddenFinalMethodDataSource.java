package io.github.kolomyychenkoai.allure.spring.support.jdbc;

import org.springframework.jdbc.datasource.AbstractDataSource;

import java.sql.Connection;

/**
 * Пул с НЕпубличным {@code final}-методом. CGLIB не переопределит и его, а значит он тоже
 * выполнится на экземпляре без конструктора и вернёт пустоту вместо значения.
 * <p>
 * Отдельная фикстура нужна потому, что {@code Class.getMethods()} непубличные методы не
 * показывает: предпроверка, написанная через него, такой пул пропустит и молча заведёт
 * подкласс-ловушку.
 */
public class HiddenFinalMethodDataSource extends AbstractDataSource {

    private final String stamp;

    public HiddenFinalMethodDataSource() {
        this.stamp = "настоящее".concat(" значение");
    }

    /** Пакетно-приватный final: снаружи невидим, для CGLIB так же непреодолим. */
    final String stamp() {
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
