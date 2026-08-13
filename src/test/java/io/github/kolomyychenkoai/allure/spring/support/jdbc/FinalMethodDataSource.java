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

    /**
     * Второй {@code final}-метод, и по алфавиту он ПОЗЖЕ первого. Нужен, чтобы проверить
     * выбор наименьшего по имени: порядок {@code getDeclaredMethods()} не определён, и без
     * сортировки предупреждение называло бы разные методы на разных прогонах и JDK.
     */
    public final String zzzAnotherFinal() {
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

    /** Своё имя вместо {@code Класс@хэш}: identity-хэш плавает между прогонами и запрещён
     * правилом гигиены имён (см. {@code docs/acceptance-report-standard.md}). */
    @Override
    public String toString() {
        return "пул с final-методом";
    }

}
