package io.github.kolomyychenkoai.allure.spring.logs;

import io.github.kolomyychenkoai.allure.spring.support.InMemoryAllure;
import io.github.kolomyychenkoai.allure.spring.support.TestContexts;
import io.qameta.allure.model.TestResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.test.context.TestContext;

import static org.assertj.core.api.Assertions.assertThat;

/** Уровень A: детерминированная проверка содержимого отчёта для захвата логов. */
class AllureApplicationLogsListenerTest {

    private static final Logger log = LoggerFactory.getLogger(AllureApplicationLogsListenerTest.class);

    private final AllureApplicationLogsListener listener = new AllureApplicationLogsListener();
    private InMemoryAllure allure;

    @BeforeEach
    void setUp() {
        allure = new InMemoryAllure().install();
    }

    @AfterEach
    void tearDown() {
        allure.uninstall();
    }

    @Test
    @DisplayName("логи, залогированные во время теста, попадают во вложение Application Logs")
    void capturesLogsIntoAttachment() {
        TestContext ctx = TestContexts.withEnvironment(new StandardEnvironment());

        TestResult result = allure.run("capturesLogs", () -> {
            listener.beforeTestMethod(ctx);
            log.info("hello from unit test");
            log.warn("value={}", 42);
            listener.afterTestMethod(ctx);
        });

        String logs = allure.attachment(result, "Application Logs").orElseThrow();
        assertThat(logs)
                .contains("hello from unit test")
                .contains("value=42")
                .contains("INFO")
                .contains("WARN");
    }

    @Test
    @DisplayName("без логов во время теста → вложение «No logs captured»")
    void emptyBufferRendersPlaceholder() {
        TestContext ctx = TestContexts.withEnvironment(new StandardEnvironment());

        // before и after подряд, без логирования между ними
        TestResult result = allure.run("empty-logs", () -> {
            listener.beforeTestMethod(ctx);
            listener.afterTestMethod(ctx);
        });

        assertThat(allure.attachment(result, "Application Logs").orElseThrow())
                .isEqualTo("No logs captured");
    }

    @Test
    @DisplayName("аппендер снимается с root-логгера — нет утечки между тестами")
    void appenderDetachedFromRootNoLeak() {
        ch.qos.logback.classic.Logger root = (ch.qos.logback.classic.Logger)
                LoggerFactory.getLogger(ch.qos.logback.classic.Logger.ROOT_LOGGER_NAME);
        long before = appenderCount(root);
        TestContext ctx = TestContexts.withEnvironment(new StandardEnvironment());

        allure.run("leak-check", () -> {
            listener.beforeTestMethod(ctx);
            log.info("во время теста");
            listener.afterTestMethod(ctx);
        });

        // сломай detachAppender — счётчик вырастет, тест покраснеет
        assertThat(appenderCount(root)).isEqualTo(before);
    }

    private static long appenderCount(ch.qos.logback.classic.Logger root) {
        long count = 0;
        var it = root.iteratorForAppenders();
        while (it.hasNext()) {
            it.next();
            count++;
        }
        return count;
    }
}
