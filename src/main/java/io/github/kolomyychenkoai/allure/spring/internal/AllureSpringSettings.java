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

    /**
     * Включить инструментирование ассертов (AssertJ/Hamcrest/Spring), по умолчанию true.
     * Это ГЛОБАЛЬНАЯ на JVM фича (байткод ставится один раз до контекста), поэтому
     * читается из system property, а не из per-test Spring Environment.
     */
    public static final String ASSERTION_ENABLED = "allure.spring.assertion.enabled";

    /**
     * Включить логирование взаимодействий с моками (Mockito), по умолчанию true. Тоже
     * ГЛОБАЛЬНАЯ на JVM фича (кастомный MockMaker) — читается из system property.
     */
    public static final String MOCK_ENABLED = "allure.spring.mock.enabled";

    /**
     * Включить инструментирование Kafka (producer.send/consumer.poll), по умолчанию true.
     * ГЛОБАЛЬНАЯ на JVM фича (байткод) — читается из system property.
     */
    public static final String KAFKA_ENABLED = "allure.spring.kafka.enabled";

    /**
     * Включить инструментирование WireMock (stubFor/verify/resetAll + запросы),
     * по умолчанию true. Глобальная на JVM фича (байткод) — читается из system property.
     */
    public static final String WIREMOCK_ENABLED = "allure.spring.wiremock.enabled";

    /** Префиксы по умолчанию — без доменной специфики. */
    public static final String DEFAULT_INCLUDE_PREFIXES = "spring.,server.,logging.,management.";

    private AllureSpringSettings() {
    }

    /** Булев тумблер с дефолтом «включено» (Spring Environment → system property). */
    public static boolean enabled(Environment env, String key) {
        String value = property(env, key);
        return value == null || Boolean.parseBoolean(value);
    }

    /**
     * Булев тумблер только из system property (дефолт «включено») — для глобальных, на
     * JVM, фич, которые ставятся до Spring-контекста (напр. байткод-инструментирование).
     */
    public static boolean enabled(String key) {
        String value = System.getProperty(key);
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
