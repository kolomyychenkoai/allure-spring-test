package io.github.kolomyychenkoai.allure.spring.support;

import io.github.kolomyychenkoai.allure.spring.internal.AllureInstrumentationLogger;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * Что библиотека сказала в свой логгер, пока шло действие.
 * <p>
 * Наружу диагностика видна ТОЛЬКО строкой в логе, поэтому её перехват — общий инструмент
 * сразу трёх тест-классов. Жил тремя дословными копиями, пока копий было две; на третьей
 * стало ясно, что это фикстура.
 * <p>
 * Уровень коллектора — {@code ALL}: иначе тест зависел бы от настроек логирования вокруг,
 * а проверять надо в том числе САМ уровень записи.
 */
public final class LibraryLog {

    private LibraryLog() {
    }

    public static List<LogRecord> capture(Runnable action) {
        List<LogRecord> records = new ArrayList<>();
        Handler collector = new Handler() {
            @Override
            public void publish(LogRecord record) {
                records.add(record);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        collector.setLevel(Level.ALL);
        Logger logger = AllureInstrumentationLogger.logger();
        logger.addHandler(collector);
        try {
            action.run();
        } finally {
            logger.removeHandler(collector);
        }
        return records;
    }
}
