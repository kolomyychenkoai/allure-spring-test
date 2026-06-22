package io.github.kolomyychenkoai.allure.spring.internal;

import org.springframework.core.env.Environment;

/**
 * Единый реестр ключей-тумблеров библиотеки и их чтение. Каждый модуль держит СВОЙ ключ
 * здесь (а не россыпью по коду), чтобы контракт свойств был в одном месте. Значения
 * берутся сначала из Spring {@link Environment} (application.yml/properties тестового
 * проекта), а если там нет — из system property с тем же ключом. По умолчанию всё включено.
 */
public final class AllureSpringSettings {

    /** Включить захват логов приложения (по умолчанию true). */
    public static final String LOGS_ENABLED = "allure.spring.logs.enabled";

    /** Включить снимок конфигурации (по умолчанию true). */
    public static final String CONFIG_ENABLED = "allure.spring.config.enabled";

    /** CSV-список префиксов свойств, попадающих в снимок конфигурации. */
    public static final String CONFIG_INCLUDE_PREFIXES = "allure.spring.config.include-prefixes";

    /** Префиксы по умолчанию — без доменной специфики. */
    public static final String DEFAULT_INCLUDE_PREFIXES = "spring.,server.,logging.,management.";

    private AllureSpringSettings() {
    }

    /** Булев тумблер с дефолтом «включено». */
    public static boolean enabled(Environment env, String key) {
        String value = property(env, key);
        return value == null || Boolean.parseBoolean(value);
    }

    /** Список префиксов для снимка конфигурации (с фоллбэком на дефолт). */
    public static String includePrefixes(Environment env) {
        String value = property(env, CONFIG_INCLUDE_PREFIXES);
        return (value == null || value.isBlank()) ? DEFAULT_INCLUDE_PREFIXES : value;
    }

    private static String property(Environment env, String key) {
        String value = (env != null) ? env.getProperty(key) : null;
        return (value != null) ? value : System.getProperty(key);
    }
}
