package io.github.kolomyychenkoai.allure.spring.support.jdbc;

import org.springframework.jdbc.datasource.AbstractDataSource;

import java.sql.Connection;

/**
 * Пул с {@code final}-методом. Подкласс завести можно, а перехватить такой метод нельзя: он
 * выполнился бы на экземпляре без конструктора и тихо вернул бы {@code null} вместо значения.
 * Поэтому такой класс не проксируется вовсе.
 */
public class FinalMethodDataSource extends AbstractDataSource {

    private final String stamp;

    public FinalMethodDataSource() {
        // Значение из конструктора, а НЕ инициализатор поля константой: константу javac
        // подставляет прямо в тело метода, и чтения поля в байткоде не остаётся — проверка
        // «на пустом подклассе вернулся бы null» стала бы зелёной по неверной причине.
        this.stamp = "настоящее".concat(" значение");
    }

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
