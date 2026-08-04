package io.github.kolomyychenkoai.allure.spring.liquibase;

import io.github.kolomyychenkoai.allure.spring.internal.AllureInstrumentation;
import io.github.kolomyychenkoai.allure.spring.internal.ByteBuddyPresence;
import io.github.kolomyychenkoai.allure.spring.internal.AllureInstrumentationLogger;
import io.github.kolomyychenkoai.allure.spring.internal.ClassPresence;
import io.github.kolomyychenkoai.allure.spring.liquibase.internal.AllureLiquibaseInstrumentation;
import liquibase.integration.spring.SpringLiquibase;
import org.springframework.context.ApplicationContext;
import org.springframework.core.Ordered;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.TestExecutionListener;

/**
 * Ставит байткод-инструментирование Liquibase один раз перед первым тест-классом — чтобы
 * применённые миграции попадали в отчёт. Регистрируется через {@code META-INF/spring.factories}.
 * <p>
 * Гейты: нет Liquibase на classpath — нечего инструментировать; нет byte-buddy — тихий no-op
 * (типы matcher/advice не линкуются).
 * <p>
 * Кроме установки, в {@code beforeTestMethod} рисует снимок стартовой схемы БД — в НАЧАЛЕ каждого
 * теста, чтобы любой тест был самодостаточен (см.
 * {@link AllureLiquibaseInstrumentation#emitStartupSnapshot()}). Кейс уже активен: платформенный
 * слушатель Allure {@code AllureJunitPlatform.executionStarted} стартует кейс до фазы {@code before}
 * узла JUnit-Platform.
 * <p>
 * Снимок рисуем ТОЛЬКО если контекст ИМЕННО ЭТОГО теста реально применял стартовые миграции —
 * проверяем наличие бина {@link SpringLiquibase} ({@link #contextRanLiquibase}). Иначе JVM-широкий
 * снимок «протекал» бы в тесты, где Liquibase не поднимался (Kafka/HTTP-only). Безопасно и без
 * Liquibase на classpath — гейт {@code LIQUIBASE_PRESENT} не даёт линковать liquibase-типы.
 * <p>
 * <b>Граница сигнала:</b> сигнал «БД реально поднималась» = Spring-Boot-бин {@link SpringLiquibase}.
 * {@code MultiTenantSpringLiquibase} (он НЕ наследует {@code SpringLiquibase}) и не-Spring стартовые
 * миграции (ручной {@code liquibase.update()} в init бина, кастомный мигратор) в снимок НЕ попадут.
 * Live-путь ({@code liquibase.update()} во время теста) работает всегда, независимо от этого.
 * <p>
 * ⚠️ Допущение об ordering JUnit-Platform (Allure-кейс активен уже в {@code beforeTestMethod} —
 * {@code AllureJunitPlatform.executionStarted} стартует его до фазы {@code before} узла) — его страж
 * end-to-end {@code LiquibaseReportIT} (уровень B). Перепроверить при апгрейде junit-platform/allure-junit5.
 */
public class AllureLiquibaseListener implements TestExecutionListener, Ordered {

    private static final boolean LIQUIBASE_PRESENT =
            ClassPresence.isPresent("liquibase.changelog.ChangeSet");

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public void beforeTestClass(TestContext testContext) {
        if (!LIQUIBASE_PRESENT || !ByteBuddyPresence.available()) {
            return;
        }
        AllureLiquibaseInstrumentation.install();
    }

    @Override
    public void beforeTestMethod(TestContext testContext) {
        if (!LIQUIBASE_PRESENT) {
            return; // нет liquibase на classpath — не трогаем liquibase-типы
        }
        // снимок стартовой схемы — в НАЧАЛЕ каждого теста, но только там, где БД реально поднималась;
        // кейс уже активен (AllureJunitPlatform.executionStarted стартует его до фазы before узла)
        if (!contextRanLiquibase(testContext.getApplicationContext())) {
            return;
        }
        AllureLiquibaseInstrumentation.emitStartupSnapshot();
    }

    /**
     * true, если в контексте теста есть бин {@link SpringLiquibase} — т.е. стартовые миграции
     * реально применялись для этого теста (а не «протекли» из JVM-широкого снимка другого контекста).
     */
    static boolean contextRanLiquibase(ApplicationContext ctx) {
        if (ctx == null) {
            return false;
        }
        try {
            // false,false — дешёвый зонд без eager-init FactoryBean'ов
            return ctx.getBeanNamesForType(SpringLiquibase.class, false, false).length > 0;
        } catch (Throwable t) {
            AllureInstrumentationLogger.warn("Liquibase", t); // не глушим молча: дефект контекста должен быть виден
            return false;
        }
    }
}
