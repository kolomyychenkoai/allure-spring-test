package io.github.kolomyychenkoai.allure.spring.internal;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Общий логгер для байткод-модулей инструментирования (ByteBuddy advice, Mockito и т.п.).
 * Используется java.util.logging, а НЕ SLF4J: advice-классы инлайнятся в чужой байткод,
 * где ссылка на SLF4J недоступна из-за ограничений загрузки классов.
 * <p>
 * Сбой инструментирования логируется на {@link Level#WARNING} — он виден по умолчанию
 * (JUL печатает WARNING в stderr), но тест НЕ роняет. Подробную трассу при отладке можно
 * включить через {@code logger().setLevel(Level.FINE)}.
 */
public final class AllureInstrumentationLogger {

    private static final Logger LOGGER = Logger.getLogger("io.github.kolomyychenkoai.allure.spring");

    private AllureInstrumentationLogger() {
    }

    /** Корневой JUL-логгер библиотеки — для тонкой настройки уровня/handler'ов. */
    public static Logger logger() {
        return LOGGER;
    }

    /**
     * Залогировать сбой инструментирования компонента {@code component} на
     * {@link Level#WARNING}: виден по умолчанию, но тест не роняет. Сообщение строится
     * лениво (supplier), сам {@code t} прикладывается — стек печатается даже если
     * {@code getMessage()} == {@code null}.
     */
    public static void warn(String component, Throwable t) {
        LOGGER.log(Level.WARNING, t, () -> "[Allure " + component + "] сбой инструментирования (тест не затронут)");
    }
}
