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
import org.springframework.mock.env.MockPropertySource;
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
    @DisplayName("при allure.spring.logs.enabled=false вложение не создаётся")
    void disabledByProperty() {
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().addFirst(
                new MockPropertySource().withProperty("allure.spring.logs.enabled", "false"));
        TestContext ctx = TestContexts.withEnvironment(env);

        TestResult result = allure.run("disabled", () -> {
            listener.beforeTestMethod(ctx);
            log.info("this should not be captured");
            listener.afterTestMethod(ctx);
        });

        assertThat(allure.attachment(result, "Application Logs")).isEmpty();
    }
}
