package io.github.kolomyychenkoai.allure.spring.web;

import io.restassured.RestAssured;
import io.restassured.filter.Filter;
import org.springframework.core.Ordered;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.TestExecutionListener;

import java.util.List;

/**
 * Ставит {@link AllureRestAssuredFilter} в глобальные фильтры RestAssured —
 * чтобы все RestAssured-вызовы попадали в отчёт без кода в тестах.
 * <p>
 * Регистрация идёт в {@code beforeTestExecution} (а не {@code beforeTestClass}):
 * этот хук срабатывает ПОСЛЕ {@code @BeforeEach} потребителя, поэтому переживает
 * привычный {@code RestAssured.reset()}/{@code replaceFiltersWith(...)} в setUp теста.
 * Идемпотентно: добавляет фильтр, только если его ещё нет (без задвоения шагов).
 * Проверка-вставка синхронизирована — безопасно при параллельном запуске классов.
 * Регистрируется через {@code META-INF/spring.factories}; если RestAssured нет на
 * classpath, Spring просто пропустит этот листенер.
 */
public class AllureRestAssuredListener implements TestExecutionListener, Ordered {

    private static final Object LOCK = new Object();

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public void beforeTestExecution(TestContext testContext) {
        synchronized (LOCK) {
            List<Filter> current = RestAssured.filters();
            boolean present = current.stream().anyMatch(AllureRestAssuredFilter.class::isInstance);
            if (!present) {
                RestAssured.filters(new AllureRestAssuredFilter());
            }
        }
    }
}
