package io.github.kolomyychenkoai.allure.spring.web;

import io.github.kolomyychenkoai.allure.spring.internal.AllureInstrumentation;
import io.github.kolomyychenkoai.allure.spring.internal.ClassPresence;
import io.github.kolomyychenkoai.allure.spring.web.internal.AllureRestClientInstrumentation;
import org.springframework.core.Ordered;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.TestExecutionListener;

/**
 * Ставит байткод-перехват RestClient один раз перед первым тест-классом — чтобы вызовы
 * {@code RestClient} (новый текучий HTTP-клиент Spring 6.1+) попадали в отчёт. Регистрируется
 * через {@code META-INF/spring.factories}.
 * <p>
 * Гейты: нет RestClient на classpath — нечего инструментировать; нет byte-buddy — тихий
 * no-op (типы matcher/advice не линкуются).
 */
public class AllureRestClientListener implements TestExecutionListener, Ordered {

    private static final boolean RESTCLIENT_PRESENT =
            ClassPresence.isPresent("org.springframework.web.client.RestClient");

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public void beforeTestClass(TestContext testContext) {
        if (!RESTCLIENT_PRESENT || !AllureInstrumentation.available()) {
            return;
        }
        AllureRestClientInstrumentation.install();
    }
}
