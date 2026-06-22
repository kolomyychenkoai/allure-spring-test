package io.github.kolomyychenkoai.allure.spring.assertion;

import org.springframework.core.Ordered;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.TestExecutionListener;

/**
 * Ставит байткод-инструментирование ассертов один раз (идемпотентно) перед первым
 * тест-классом: Spring AssertionErrors и Hamcrest. Регистрируется через
 * {@code META-INF/spring.factories}; если ByteBuddy нет на classpath, Spring пропустит
 * этот листенер. Если конкретной библиотеки ассертов нет — её матчер просто ничего
 * не находит (no-op).
 */
public class AllureAssertionsListener implements TestExecutionListener, Ordered {

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public void beforeTestClass(TestContext testContext) {
        AllureSpringAssertionsInstrumentation.install();
        AllureHamcrestInstrumentation.install();
    }
}
