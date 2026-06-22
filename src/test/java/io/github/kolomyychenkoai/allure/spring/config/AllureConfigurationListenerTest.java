package io.github.kolomyychenkoai.allure.spring.config;

import io.github.kolomyychenkoai.allure.spring.support.InMemoryAllure;
import io.github.kolomyychenkoai.allure.spring.support.TestContexts;
import io.qameta.allure.model.TestResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.mock.env.MockPropertySource;
import org.springframework.test.context.TestContext;

import static org.assertj.core.api.Assertions.assertThat;

/** Уровень A: детерминированная проверка содержимого отчёта для снимка конфигурации. */
class AllureConfigurationListenerTest {

    private final AllureConfigurationListener listener = new AllureConfigurationListener();
    private InMemoryAllure allure;

    @BeforeEach
    void setUp() {
        allure = new InMemoryAllure().install();
    }

    @AfterEach
    void tearDown() {
        allure.uninstall();
    }

    private TestContext contextWith(MockPropertySource props) {
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().addFirst(props);
        return TestContexts.withEnvironment(env);
    }

    @Test
    @DisplayName("свойства по дефолтным префиксам попадают в шаг Configuration (маскирования нет)")
    void attachesPropertiesByPrefix() {
        TestContext ctx = contextWith(new MockPropertySource()
                .withProperty("spring.application.name", "demo")
                .withProperty("server.port", "8080")
                .withProperty("spring.datasource.password", "hunter2")
                .withProperty("custom.foo", "bar"));

        TestResult result = allure.run("config", () -> listener.beforeTestMethod(ctx));

        assertThat(allure.hasStep(result, "Configuration")).isTrue();
        String props = allure.attachment(result, "Properties").orElseThrow();
        assertThat(props)
                .contains("spring.application.name=demo")
                .contains("server.port=8080")
                .contains("spring.datasource.password=hunter2") // маскирования нет — значение видно
                .doesNotContain("custom.foo");                  // не входит в дефолтные префиксы
    }

    @Test
    @DisplayName("список префиксов настраивается через allure.spring.config.include-prefixes")
    void honoursCustomPrefixes() {
        TestContext ctx = contextWith(new MockPropertySource()
                .withProperty("allure.spring.config.include-prefixes", "custom.")
                .withProperty("custom.foo", "bar")
                .withProperty("spring.application.name", "demo"));

        TestResult result = allure.run("config-prefixes", () -> listener.beforeTestMethod(ctx));

        String props = allure.attachment(result, "Properties").orElseThrow();
        assertThat(props)
                .contains("custom.foo=bar")
                .doesNotContain("spring.application.name");
    }

    @Test
    @DisplayName("при allure.spring.config.enabled=false шаг не создаётся")
    void disabledByProperty() {
        TestContext ctx = contextWith(new MockPropertySource()
                .withProperty("allure.spring.config.enabled", "false")
                .withProperty("spring.application.name", "demo"));

        TestResult result = allure.run("config-disabled", () -> listener.beforeTestMethod(ctx));

        assertThat(allure.hasStep(result, "Configuration")).isFalse();
        assertThat(allure.attachment(result, "Properties")).isEmpty();
    }
}
