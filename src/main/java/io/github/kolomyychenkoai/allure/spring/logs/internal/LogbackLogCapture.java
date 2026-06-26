package io.github.kolomyychenkoai.allure.spring.logs.internal;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import io.qameta.allure.Allure;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Вся работа с Logback вынесена в этот класс отдельно — чтобы листенер
 * {@code AllureApplicationLogsListener} НЕ линковал типы {@code ch.qos.logback.*} в своих
 * сигнатурах/полях и трогал их только ПОСЛЕ гейта присутствия. Этот класс загружается
 * (и тянет {@code ch.qos.logback.*}) лишь когда активный SLF4J-бэкенд — Logback. На
 * потребителе с Log4j2/JUL (Logback исключён, напр. {@code spring-boot-starter-log4j2})
 * листенер сюда не заходит → ни {@link NoClassDefFoundError}, ни {@link ClassCastException},
 * тест не падает. НЕ публичный API.
 */
public final class LogbackLogCapture {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
            .withZone(ZoneId.systemDefault());

    private final LoggerContext loggerContext;
    private final CapturingAppender appender;

    private LogbackLogCapture(LoggerContext loggerContext) {
        this.loggerContext = loggerContext;
        this.appender = new CapturingAppender();
        this.appender.setContext(loggerContext);
        this.appender.start();
    }

    /**
     * Готовит захват (создаёт аппендер, но ещё НЕ вешает на root) при активном Logback-бэкенде,
     * иначе {@code null}. Проверка через {@code instanceof}, а не голый каст — на Log4j2/JUL
     * {@code getILoggerFactory()} вернёт чужую фабрику, и мы тихо вернём {@code null}.
     * Вешать аппендер — отдельным {@link #attach()} (вызывающий успевает сохранить хэндл ДО
     * возможного исключения из {@code addAppender} — тогда снятие в afterTestMethod не потеряется).
     */
    public static LogbackLogCapture createIfLogback() {
        if (!(LoggerFactory.getILoggerFactory() instanceof LoggerContext ctx)) {
            return null;
        }
        return new LogbackLogCapture(ctx);
    }

    /** Вешает аппендер на root-логгер. */
    public void attach() {
        loggerContext.getLogger(Logger.ROOT_LOGGER_NAME).addAppender(appender);
    }

    /** Снимает аппендер и кладёт вложение «Application Logs» (даже если detach/stop бросил). */
    public void detachAndWriteAttachment() {
        try {
            loggerContext.getLogger(Logger.ROOT_LOGGER_NAME).detachAppender(appender);
            appender.stop();
        } finally {
            // вложение пишем даже если detach/stop бросили — и снятие, и аттач не теряются
            List<String> lines = appender.getLines();
            Allure.addAttachment("Application Logs", "text/plain",
                    lines.isEmpty() ? "No logs captured" : String.join("\n", lines));
        }
    }

    private static final class CapturingAppender extends AppenderBase<ILoggingEvent> {

        private final List<String> lines = Collections.synchronizedList(new ArrayList<>());

        @Override
        protected void append(ILoggingEvent event) {
            try {
                String thread = event.getThreadName();
                String line = FMT.format(Instant.ofEpochMilli(event.getTimeStamp()))
                        + " " + String.format("%-5s", event.getLevel())
                        + " [" + (thread != null ? thread : "?") + "] "
                        + event.getLoggerName()
                        + " - " + event.getFormattedMessage();
                lines.add(line);
            } catch (Exception e) {
                // сбой захвата строки виден в статусах logback, но НЕ роняет логирование приложения
                addError("Allure log capture failed", e);
            }
        }

        List<String> getLines() {
            synchronized (lines) { // итерация по synchronizedList — под его же монитором
                return List.copyOf(lines);
            }
        }
    }
}
