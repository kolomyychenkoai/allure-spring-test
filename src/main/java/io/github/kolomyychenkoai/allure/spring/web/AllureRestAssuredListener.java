package io.github.kolomyychenkoai.allure.spring.web;

import io.github.kolomyychenkoai.allure.spring.internal.AllureSpringSettings;
import io.restassured.RestAssured;
import io.restassured.filter.Filter;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.TestExecutionListener;

import java.util.List;

/**
 * Ставит {@link AllureRestAssuredFilter} в глобальные фильтры RestAssured —
 * чтобы все RestAssured-вызовы попадали в отчёт без кода в тестах. Выключить —
 * {@code allure.spring.web.enabled=false} (общий тумблер HTTP-модуля, с MockMvc).
 * <p>
 * Регистрация идёт в {@code beforeTestExecution} (а не {@code beforeTestClass}):
 * этот хук срабатывает ПОСЛЕ {@code @BeforeEach} потребителя, поэтому переживает
 * привычный {@code RestAssured.reset()}/{@code replaceFiltersWith(...)} в setUp теста.
 * Идемпотентно: добавляет фильтр, только если его ещё нет (без задвоения шагов).
 * Регистрируется через {@code META-INF/spring.factories}; если RestAssured нет на
 * classpath, Spring просто пропустит этот листенер.
 * <p>
 * ⚠️ <b>Параллелизм:</b> {@code RestAssured.filters} — это ГЛОБАЛЬНОЕ статическое
 * состояние самого RestAssured (не потокобезопасное). Наш {@code LOCK} синхронизирует
 * лишь нашу проверку-вставку; если код потребителя в другом потоке дёргает
 * {@code RestAssured.reset()/filters(...)} (напр. {@code @Execution(CONCURRENT)} на методах),
 * happens-before не образуется — это ограничение RestAssured. Модуль рассчитан на
 * ПОСЛЕДОВАТЕЛЬНУЮ конфигурацию RestAssured (forked-JVM surefire — штатно).
 */
public class AllureRestAssuredListener implements TestExecutionListener, Ordered {

    private static final Object LOCK = new Object();

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public void beforeTestExecution(TestContext testContext) {
        if (!AllureSpringSettings.enabled(environment(testContext), AllureSpringSettings.WEB_ENABLED)) {
            return;
        }
        synchronized (LOCK) {
            List<Filter> current = RestAssured.filters();
            boolean present = current.stream().anyMatch(AllureRestAssuredFilter.class::isInstance);
            if (!present) {
                RestAssured.filters(new AllureRestAssuredFilter());
            }
        }
    }

    private static Environment environment(TestContext testContext) {
        try {
            return testContext.getApplicationContext().getEnvironment();
        } catch (Throwable ignored) {
            return null;
        }
    }
}
