package io.github.kolomyychenkoai.allure.spring.internal;

import io.github.kolomyychenkoai.allure.spring.support.InMemoryAllure;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.context.TestContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Уровень A: проверка вспомогательных методов AllureSpringSettings.
 */
class AllureSpringSettingsTest {

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
        // под InMemoryAllure без активного кейса: вызовы Mockito-моков (getApplicationContext)
        // не логируются нашим MockMaker'ом — иначе в отчёт утёк бы шаг с рандомным hashCode мока
        InMemoryAllure allure = new InMemoryAllure().install();
        try {
            MockEnvironment env = new MockEnvironment();
            TestContext ok = io.github.kolomyychenkoai.allure.spring.support.TestContexts.withEnvironment(env);
            assertThat(AllureSpringSettings.environment(ok)).isSameAs(env);

            TestContext noContext = mock(TestContext.class);
            when(noContext.getApplicationContext()).thenThrow(new IllegalStateException("контекст не поднят"));
            assertThat(AllureSpringSettings.environment(noContext)).isNull();
        } finally {
            allure.uninstall();
        }
    }
}
