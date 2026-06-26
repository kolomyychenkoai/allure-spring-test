package io.github.kolomyychenkoai.allure.spring.assertion;

import io.github.kolomyychenkoai.allure.spring.assertion.internal.AllureAssertJInstrumentation;
import io.github.kolomyychenkoai.allure.spring.assertion.internal.AllureHamcrestInstrumentation;
import io.github.kolomyychenkoai.allure.spring.assertion.internal.AllureSpringAssertionsInstrumentation;
import io.github.kolomyychenkoai.allure.spring.internal.AllureInstrumentation;
import org.springframework.core.Ordered;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.TestExecutionListener;

/**
 * Ставит байткод-инструментирование ассертов один раз (идемпотентно) перед первым
 * тест-классом: Spring AssertionErrors, Hamcrest и AssertJ. Регистрируется через
 * {@code META-INF/spring.factories}.
 * <p>
 * Перед установкой проверяется {@link AllureInstrumentation#available()} — если byte-buddy
 * нет на classpath, листенер тихо ничего не ставит (типы matcher/advice не линкуются).
 * Если конкретной библиотеки ассертов нет — её матчер просто ничего не находит (no-op).
 */
public class AllureAssertionsListener implements TestExecutionListener, Ordered {

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public void beforeTestClass(TestContext testContext) {
        if (!AllureInstrumentation.available()) {
            return;
        }
        AllureSpringAssertionsInstrumentation.install();
        AllureHamcrestInstrumentation.install();
        AllureAssertJInstrumentation.install();
    }
}
