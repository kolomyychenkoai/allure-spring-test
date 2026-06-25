package io.github.kolomyychenkoai.allure.spring.liquibase;

import io.github.kolomyychenkoai.allure.spring.internal.AllureInstrumentation;
import io.github.kolomyychenkoai.allure.spring.internal.ClassPresence;
import io.github.kolomyychenkoai.allure.spring.liquibase.internal.AllureLiquibaseInstrumentation;
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
 * Кроме установки, в {@code afterTestMethod} выкладывает снимок changeset'ов, применённых на
 * старте контекста (до теста) — на тест-потоке с активным Allure-кейсом, один раз
 * (см. {@link AllureLiquibaseInstrumentation#flushStartupSnapshot()}). Безопасно и без
 * Liquibase на classpath — буфер тогда просто пуст.
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
        if (!LIQUIBASE_PRESENT || !AllureInstrumentation.available()) {
            return;
        }
        AllureLiquibaseInstrumentation.install();
    }

    @Override
    public void afterTestMethod(TestContext testContext) {
        AllureLiquibaseInstrumentation.flushStartupSnapshot(); // снимок старта — на тест-потоке
    }
}
