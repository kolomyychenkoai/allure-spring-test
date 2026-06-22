package io.github.kolomyychenkoai.allure.spring.internal;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Общий логгер для байткод-модулей инструментирования (ByteBuddy advice, Mockito и т.п.).
 * Используется java.util.logging, а НЕ SLF4J: advice-классы инлайнятся в чужой байткод,
 * где ссылка на SLF4J недоступна из-за ограничений загрузки классов.
 * <p>
 * Чтобы увидеть отладку инструментирования: {@code logger().setLevel(Level.FINE)}.
 */
public final class AllureInstrumentationLogger {

    private static final Logger LOGGER = Logger.getLogger("io.github.kolomyychenkoai.allure.spring");

    private AllureInstrumentationLogger() {
    }

    public static Logger logger() {
        return LOGGER;
    }

    public static void warn(String component, Throwable t) {
        LOGGER.log(Level.FINE, "[Allure " + component + "] " + t.getMessage(), t);
    }
}
