package io.github.kolomyychenkoai.allure.spring.web;

import io.github.kolomyychenkoai.allure.spring.web.internal.AllureWebTestClientLogger;
import org.springframework.core.Ordered;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.TestExecutionListener;

/**
 * Проигрывает буфер статус-онли обменов {@code WebTestClient}, снятых
 * {@link AllureWebTestClientFilter} на реактивном потоке: чистит буфер в
 * {@code beforeTestMethod}, выкладывает шаги на тест-потоке в {@code afterTestMethod}
 * (см. {@link AllureWebTestClientLogger}). Регистрируется через {@code META-INF/spring.factories}.
 * <p>
 * flush/clear безопасны и без WebTestClient на classpath — буфер тогда просто пуст
 * (фильтр не подключается, если WebTestClient нет).
 */
public class AllureWebTestClientListener implements TestExecutionListener, Ordered {

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public void beforeTestMethod(TestContext testContext) {
        AllureWebTestClientLogger.clear(); // окно привязки = текущий тест-метод
    }

    @Override
    public void afterTestMethod(TestContext testContext) {
        AllureWebTestClientLogger.flush(); // проиграть статус-онли обмены на тест-потоке
    }
}
