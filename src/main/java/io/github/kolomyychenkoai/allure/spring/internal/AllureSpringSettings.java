package io.github.kolomyychenkoai.allure.spring.internal;

import org.springframework.core.env.Environment;
import org.springframework.test.context.TestContext;

/**
 * Настраиваемые свойства библиотеки. Значения берутся из Spring {@link Environment}
 * (application.yml/properties тестового проекта), а если там нет — из system property.
 */
public final class AllureSpringSettings {

    /** CSV-список префиксов свойств, попадающих в снимок конфигурации. */
    public static final String CONFIG_INCLUDE_PREFIXES = "allure.spring.config.include-prefixes";

    /** Префиксы по умолчанию — без доменной специфики. */
    public static final String DEFAULT_INCLUDE_PREFIXES = "spring.,server.,logging.,management.";

    private AllureSpringSettings() {
    }

    /** Список префиксов для снимка конфигурации (с фоллбэком на дефолт). */
    public static String includePrefixes(Environment env) {
        String value = property(env, CONFIG_INCLUDE_PREFIXES);
        return (value == null || value.isBlank()) ? DEFAULT_INCLUDE_PREFIXES : value;
    }

    /**
     * Spring {@link Environment} текущего теста, или {@code null}, если контекст ещё не поднят.
     * Общий хелпер для per-test листенеров (logs/config) — без копипасты.
     */
    public static Environment environment(TestContext testContext) {
        try {
            return testContext.getApplicationContext().getEnvironment();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String property(Environment env, String key) {
        String value = (env != null) ? env.getProperty(key) : null;
        return (value != null) ? value : System.getProperty(key);
    }
}
