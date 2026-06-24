package io.github.kolomyychenkoai.allure.spring.web;

import io.github.kolomyychenkoai.allure.spring.internal.AllureInstrumentation;
import io.github.kolomyychenkoai.allure.spring.internal.ClassPresence;
import io.github.kolomyychenkoai.allure.spring.web.internal.AllureMockMvcInstrumentation;
import org.springframework.core.Ordered;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.TestExecutionListener;

/**
 * Ставит байткод-перехват {@code MockMvc.perform(...)} один раз перед первым тест-классом —
 * чтобы в отчёт попадал и собранный руками MockMvc ({@code standaloneSetup}), а не только
 * авто-сконфигурированный (его и так ловит кастомайзер из {@link AllureMockMvcAutoConfiguration}).
 * Регистрируется через {@code META-INF/spring.factories}.
 * <p>
 * Гейты: нет MockMvc на classpath — нечего инструментировать; нет byte-buddy —
 * {@link AllureInstrumentation#available()} false, тихий no-op (типы matcher/advice не линкуются).
 */
public class AllureMockMvcListener implements TestExecutionListener, Ordered {

    private static final boolean MOCKMVC_PRESENT =
            ClassPresence.isPresent("org.springframework.test.web.servlet.MockMvc");

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public void beforeTestClass(TestContext testContext) {
        if (!MOCKMVC_PRESENT || !AllureInstrumentation.available()) {
            return;
        }
        AllureMockMvcInstrumentation.install();
    }
}
