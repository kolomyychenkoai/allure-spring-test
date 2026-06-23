package io.github.kolomyychenkoai.allure.spring.internal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.context.TestContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Уровень A: гейты, на которых держится включение ВСЕХ фич библиотеки. Каждый тест
 * падает при регрессии соответствующей ветки `enabled`/`includePrefixes`/`environment`.
 */
class AllureSpringSettingsTest {

    private static final String KEY = "allure.spring.test.flag";

    @Test
    @DisplayName("enabled(env,key): дефолт включено, когда нигде нет значения")
    void enabledDefaultsTrue() {
        System.clearProperty(KEY);
        assertThat(AllureSpringSettings.enabled(new MockEnvironment(), KEY)).isTrue();
    }

    @Test
    @DisplayName("enabled(env,key): значение из Environment выключает")
    void enabledFromEnvironment() {
        MockEnvironment env = new MockEnvironment().withProperty(KEY, "false");
        assertThat(AllureSpringSettings.enabled(env, KEY)).isFalse();
    }

    @Test
    @DisplayName("enabled(env,key): фоллбэк на system property, когда в Environment нет")
    void enabledFallsBackToSystemProperty() {
        System.setProperty(KEY, "false");
        try {
            assertThat(AllureSpringSettings.enabled(new MockEnvironment(), KEY)).isFalse();
        } finally {
            System.clearProperty(KEY);
        }
    }

    @Test
    @DisplayName("enabled(env,key): Environment ПЕРЕБИВАЕТ system property")
    void environmentOverridesSystemProperty() {
        System.setProperty(KEY, "false");
        try {
            MockEnvironment env = new MockEnvironment().withProperty(KEY, "true");
            assertThat(AllureSpringSettings.enabled(env, KEY)).isTrue();
        } finally {
            System.clearProperty(KEY);
        }
    }

    @Test
    @DisplayName("enabled(String): только system property, дефолт включено")
    void enabledStringOnlySystemProperty() {
        System.clearProperty(KEY);
        assertThat(AllureSpringSettings.enabled(KEY)).isTrue();
        System.setProperty(KEY, "false");
        try {
            assertThat(AllureSpringSettings.enabled(KEY)).isFalse();
        } finally {
            System.clearProperty(KEY);
        }
    }

    @Test
    @DisplayName("includePrefixes: пусто/пробелы → дефолтные префиксы, иначе — заданные")
    void includePrefixesFallback() {
        assertThat(AllureSpringSettings.includePrefixes(new MockEnvironment()))
                .isEqualTo(AllureSpringSettings.DEFAULT_INCLUDE_PREFIXES);
        assertThat(AllureSpringSettings.includePrefixes(
                new MockEnvironment().withProperty(AllureSpringSettings.CONFIG_INCLUDE_PREFIXES, "   ")))
                .isEqualTo(AllureSpringSettings.DEFAULT_INCLUDE_PREFIXES);
        assertThat(AllureSpringSettings.includePrefixes(
                new MockEnvironment().withProperty(AllureSpringSettings.CONFIG_INCLUDE_PREFIXES, "custom.")))
                .isEqualTo("custom.");
    }

    @Test
    @DisplayName("environment(ctx): отдаёт Environment контекста, а при сбое — null (фича не падает)")
    void environmentFromContextOrNull() {
        MockEnvironment env = new MockEnvironment();
        TestContext ok = io.github.kolomyychenkoai.allure.spring.support.TestContexts.withEnvironment(env);
        assertThat(AllureSpringSettings.environment(ok)).isSameAs(env);

        TestContext noContext = mock(TestContext.class);
        when(noContext.getApplicationContext()).thenThrow(new IllegalStateException("контекст не поднят"));
        assertThat(AllureSpringSettings.environment(noContext)).isNull();
    }
}
