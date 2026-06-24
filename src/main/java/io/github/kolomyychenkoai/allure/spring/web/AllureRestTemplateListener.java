package io.github.kolomyychenkoai.allure.spring.web;

import io.github.kolomyychenkoai.allure.spring.internal.AllureInstrumentation;
import io.github.kolomyychenkoai.allure.spring.internal.ClassPresence;
import io.github.kolomyychenkoai.allure.spring.web.internal.AllureRestTemplateInstrumentation;
import org.springframework.core.Ordered;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.TestExecutionListener;

/**
 * Ставит байткод-перехват RestTemplate один раз перед первым тест-классом — чтобы
 * вызовы {@code RestTemplate}/{@code TestRestTemplate} попадали в отчёт. Регистрируется
 * через {@code META-INF/spring.factories}.
 * <p>
 * Гейты: нет RestTemplate на classpath — нечего инструментировать; нет byte-buddy —
 * тихий no-op (типы matcher/advice не линкуются).
 */
public class AllureRestTemplateListener implements TestExecutionListener, Ordered {

    private static final boolean RESTTEMPLATE_PRESENT =
            ClassPresence.isPresent("org.springframework.web.client.RestTemplate");

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public void beforeTestClass(TestContext testContext) {
        if (!RESTTEMPLATE_PRESENT || !AllureInstrumentation.available()) {
            return;
        }
        AllureRestTemplateInstrumentation.install();
    }
}
