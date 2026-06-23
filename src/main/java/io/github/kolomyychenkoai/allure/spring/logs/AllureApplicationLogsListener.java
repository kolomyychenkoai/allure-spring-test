package io.github.kolomyychenkoai.allure.spring.logs;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import io.qameta.allure.Allure;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
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
 * {@code META-INF/spring.factories} — потребителю не нужно писать код. Выключить —
 * <p>
 * Вложение кладётся на уровень тест-кейса (а не отдельным шагом, как «Configuration»):
 * логи — это сквозной артефакт всего теста, а не дискретное действие.
 * <p>
 * {@code getOrder() = HIGHEST_PRECEDENCE}: {@code beforeTestMethod} срабатывает раньше
 * прочих листенеров, {@code afterTestMethod} (в обратном порядке) — позже, так захват
 * покрывает тело теста и колбэки листенеров с меньшим приоритетом. То, что логируется
 * ДО beforeTestMethod этого листенера (более ранние листенеры), в захват не попадёт.
 * <p>
 * ⚠️ <b>Параллелизм:</b> аппендер вешается на ОБЩИЙ root-логгер JVM. Модуль рассчитан на
 * ПОСЛЕДОВАТЕЛЬНЫЙ прогон (forked-JVM surefire — штатно). Под {@code @Execution(CONCURRENT)}
 * аппендеры разных тестов будут ловить логи всех параллельных потоков — содержимое
 * перемешается. Захват намеренно по всем потокам (а не только потоку теста), чтобы видеть
 * async-логи (брокеры/исполнители) — ценой несовместимости с параллельным запуском.
 */
public class AllureApplicationLogsListener implements TestExecutionListener, Ordered {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
            .withZone(ZoneId.systemDefault());

    private static final String APPENDER_KEY = AllureApplicationLogsListener.class.getName();

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public void beforeTestMethod(TestContext testContext) {
        CapturingAppender appender = new CapturingAppender();
        LoggerContext loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory();
        appender.setContext(loggerContext);
        appender.start();

        // атрибут СТАВИМ ДО addAppender: тогда afterTestMethod гарантированно найдёт и снимет
        // аппендер, даже если addAppender бросит (иначе аппендер «повис» бы на root навсегда)
        testContext.setAttribute(APPENDER_KEY, appender);
        loggerContext.getLogger(Logger.ROOT_LOGGER_NAME).addAppender(appender);
    }

    @Override
    public void afterTestMethod(TestContext testContext) {
        CapturingAppender appender = (CapturingAppender) testContext.getAttribute(APPENDER_KEY);
        if (appender == null) {
            return;
        }
        testContext.removeAttribute(APPENDER_KEY);

        try {
            LoggerContext loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory();
            Logger root = loggerContext.getLogger(Logger.ROOT_LOGGER_NAME);
            root.detachAppender(appender);
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
