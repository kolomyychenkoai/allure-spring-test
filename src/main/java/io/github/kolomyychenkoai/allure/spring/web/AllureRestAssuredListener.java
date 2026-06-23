package io.github.kolomyychenkoai.allure.spring.web;

import io.github.kolomyychenkoai.allure.spring.internal.ClassPresence;
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
 * Регистрируется через {@code META-INF/spring.factories}; если RestAssured нет на
 * classpath, Spring просто пропустит этот листенер.
 * <p>
 * Ограничение: ловится только ГЛОБАЛЬНЫЙ {@code given()} API (фильтр в глобальных
 * {@code RestAssured.filters}). Изолированный {@code RequestSpecification} с локальными
 * фильтрами (без глобальных) в отчёт не попадёт.
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

    // RestAssured в scope provided — у потребителя его может не быть. Листенер
    // регистрируется всегда (spring.factories), поэтому без гейта обращение к RestAssured
    // в хуке дало бы NoClassDefFoundError и уронило бы тест потребителя. См. ClassPresence.
    private static final boolean RESTASSURED_PRESENT =
            ClassPresence.isPresent("io.restassured.RestAssured");

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public void beforeTestExecution(TestContext testContext) {
        if (!RESTASSURED_PRESENT) {
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
}
