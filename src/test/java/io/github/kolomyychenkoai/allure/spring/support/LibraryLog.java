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
 * Два входа, и путать их нельзя. {@link #capture(Runnable)} уровень логгера НЕ трогает: он
 * отдаёт то, что видно при текущей настройке, и на нём стоят два десятка чужих ассертов вида
 * «библиотека не шумит». Тест, которому нужен {@code FINE}, ставит уровень сам и получает
 * его через этот же вход.
 * <p>
 * {@link #captureAll(Runnable)} поднимает уровень САМОГО логгера до {@code ALL} и возвращает
 * прежний в {@code finally}. Нужен там, где проверяется сам уровень записи: JUL отсекает
 * запись по уровню логгера ДО хендлеров, и без этого тест «ушло на FINE» зеленеет вхолостую.
 * Менять ради него {@code capture} нельзя — чужие ассерты «не шумит» молча стали бы читаться
 * как «не сказала ничего».
 */
public final class LibraryLog {

    private LibraryLog() {
    }

    /** Что видно при текущей настройке логгера; сам уровень логгера не меняется. */
    public static List<LogRecord> capture(Runnable action) {
        return capture(action, false);
    }

    /** Всё, включая {@code FINE}: только для проверок самого уровня записи. */
    public static List<LogRecord> captureAll(Runnable action) {
        return capture(action, true);
    }

    private static List<LogRecord> capture(Runnable action, boolean raiseLogger) {
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
        Level was = logger.getLevel();
        if (raiseLogger) {
            logger.setLevel(Level.ALL);
        }
        // ⚠️ Родительские хендлеры на время замера отключаем: тест потолка делает 70 настоящих
        // noteOnce, и они уходили бы в лог сборки, разбавляя настоящие строки библиотеки в 24
        // раза. Канал ценен ровно своей читаемостью — на ней стоит весь класс диагностики.
        boolean parents = logger.getUseParentHandlers();
        logger.setUseParentHandlers(false);
        try {
            action.run();
        } finally {
            if (raiseLogger) {
                logger.setLevel(was);
            }
            logger.setUseParentHandlers(parents);
            logger.removeHandler(collector);
        }
        return records;
    }
}
