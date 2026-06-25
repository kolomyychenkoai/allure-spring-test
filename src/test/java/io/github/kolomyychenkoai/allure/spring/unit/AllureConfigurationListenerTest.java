package io.github.kolomyychenkoai.allure.spring.unit;
import io.github.kolomyychenkoai.allure.spring.config.AllureConfigurationListener;

import io.github.kolomyychenkoai.allure.spring.support.InMemoryAllure;
import io.github.kolomyychenkoai.allure.spring.support.TestContexts;
import io.qameta.allure.model.TestResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.mock.env.MockPropertySource;
import org.springframework.test.context.TestContext;

import java.util.HashMap;
import java.util.Map;

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

    @Test
    @DisplayName("свойства приложения попадают в шаг Configuration (маскирования нет)")
    void attachesApplicationProperties() {
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().addFirst(new MockPropertySource()
                .withProperty("spring.application.name", "demo")
                .withProperty("custom.foo", "bar")
                .withProperty("spring.datasource.password", "hunter2"));
        TestContext ctx = TestContexts.withEnvironment(env);

        TestResult result = allure.run("config", () -> listener.beforeTestMethod(ctx));

        assertThat(allure.hasStep(result, "Configuration")).isTrue();
        String props = allure.attachment(result, "Properties").orElseThrow();
        assertThat(props)
                .contains("spring.application.name=demo")
                .contains("custom.foo=bar")
                .contains("spring.datasource.password=hunter2");
    }

    @Test
    @DisplayName("известный ключ со значением null → «<unset>» (не двусмысленное key=null)")
    void unsetValueRendered() {
        Map<String, Object> map = new HashMap<>();
        map.put("custom.nullable", null);
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().addFirst(new MapPropertySource("test", map));
        TestContext ctx = TestContexts.withEnvironment(env);

        TestResult result = allure.run("config-unset", () -> listener.beforeTestMethod(ctx));

        assertThat(allure.attachment(result, "Properties").orElseThrow())
                .contains("custom.nullable=<unset>");
    }

    @Test
    @DisplayName("неразрешимый плейсхолдер ${...} → «<unresolved>», тест не падает")
    void unresolvedPlaceholderRendered() {
        Map<String, Object> map = new HashMap<>();
        map.put("custom.bad", "${definitely.missing.placeholder}");
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().addFirst(new MapPropertySource("test", map));
        TestContext ctx = TestContexts.withEnvironment(env);

        TestResult result = allure.run("config-unresolved", () -> listener.beforeTestMethod(ctx));

        assertThat(allure.attachment(result, "Properties").orElseThrow())
                .contains("custom.bad=<unresolved>");
    }
}
