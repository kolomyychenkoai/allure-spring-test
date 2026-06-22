package io.github.kolomyychenkoai.allure.spring.logs;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import io.github.kolomyychenkoai.allure.spring.internal.AllureSpringSettings;
import io.qameta.allure.Allure;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.TestExecutionListener;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Захватывает логи приложения (через Logback) на время каждого теста и прикрепляет их
 * к Allure-отчёту как вложение «Application Logs». Активируется автоматически через
 * {@code META-INF/spring.factories} — потребителю не нужно писать код.
 */
public class AllureApplicationLogsListener implements TestExecutionListener, Ordered {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
            .withZone(ZoneId.systemDefault());

    private static final String APPENDER_KEY = AllureApplicationLogsListener.class.getName();

    @Override
    public int getOrder() {
        // beforeTestMethod пораньше, afterTestMethod (в обратном порядке) — попозже:
        // так захват логов покрывает весь тест.
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public void beforeTestMethod(TestContext testContext) {
        if (!AllureSpringSettings.enabled(environment(testContext), AllureSpringSettings.LOGS_ENABLED)) {
            return;
        }

        CapturingAppender appender = new CapturingAppender();
        LoggerContext loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory();
        appender.setContext(loggerContext);
        appender.start();

        Logger root = loggerContext.getLogger(Logger.ROOT_LOGGER_NAME);
        root.addAppender(appender);

        testContext.setAttribute(APPENDER_KEY, appender);
    }

    @Override
    public void afterTestMethod(TestContext testContext) {
        CapturingAppender appender = (CapturingAppender) testContext.getAttribute(APPENDER_KEY);
        if (appender == null) {
            return;
        }
        testContext.removeAttribute(APPENDER_KEY);

        LoggerContext loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory();
        Logger root = loggerContext.getLogger(Logger.ROOT_LOGGER_NAME);
        root.detachAppender(appender);
        appender.stop();

        List<String> lines = appender.getLines();
        Allure.addAttachment("Application Logs", "text/plain",
                lines.isEmpty() ? "No logs captured" : String.join("\n", lines));
    }

    private static Environment environment(TestContext testContext) {
        try {
            return testContext.getApplicationContext().getEnvironment();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static final class CapturingAppender extends AppenderBase<ILoggingEvent> {

        private final List<String> lines = Collections.synchronizedList(new ArrayList<>());

        @Override
        protected void append(ILoggingEvent event) {
            String line = FMT.format(Instant.ofEpochMilli(event.getTimeStamp()))
                    + " " + String.format("%-5s", event.getLevel())
                    + " [" + event.getThreadName() + "] "
                    + event.getLoggerName()
                    + " - " + event.getFormattedMessage();
            lines.add(line);
        }

        List<String> getLines() {
            return List.copyOf(lines);
        }
    }
}
