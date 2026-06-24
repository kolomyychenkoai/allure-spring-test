package io.github.kolomyychenkoai.allure.spring.web.internal;

import io.github.kolomyychenkoai.allure.spring.internal.AllureInstrumentation;
import io.github.kolomyychenkoai.allure.spring.internal.AllureInstrumentationLogger;
import net.bytebuddy.asm.Advice;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import java.util.concurrent.atomic.AtomicBoolean;

import static net.bytebuddy.matcher.ElementMatchers.named;

/**
 * Байткод-перехват {@code MockMvc.perform(...)} — ловит ЛЮБОЙ MockMvc, включая собранный
 * руками ({@code MockMvcBuilders.standaloneSetup(...)}), которого кастомайзер Spring Boot
 * ({@code AllureMockMvcAutoConfiguration}) не достаёт. Логику отчёта переиспользует у
 * {@link AllureMockMvcResultHandler}; дубль с кастомайзером отсекается дедупом по identity
 * {@code MvcResult} внутри хендлера — один вызов даёт один шаг.
 * <p>
 * Установка идемпотентна (CAS-гард, один раз на JVM), ставится из
 * {@code AllureMockMvcListener#beforeTestClass}. Сбой инструментирования логируется на
 * WARNING и не роняет тест (контракт {@link AllureInstrumentation}).
 */
public final class AllureMockMvcInstrumentation {

    private static final AtomicBoolean INSTALLED = new AtomicBoolean(false);
    private static final AllureMockMvcResultHandler HANDLER = new AllureMockMvcResultHandler();

    private AllureMockMvcInstrumentation() {
    }

    public static void install() {
        if (!INSTALLED.compareAndSet(false, true)) {
            return;
        }
        AllureInstrumentation.retransform(named("org.springframework.test.web.servlet.MockMvc"),
                (builder, type, cl, module, pd) -> builder
                        .visit(Advice.to(PerformAdvice.class).on(named("perform"))));
    }

    /** Логика шага (вынесена из advice). {@code andReturn()} отдаёт уже готовый MvcResult. */
    public static void onPerform(Object resultActions) {
        try {
            if (resultActions instanceof ResultActions actions) {
                MvcResult result = actions.andReturn();
                HANDLER.handle(result);
            }
        } catch (Throwable t) {
            AllureInstrumentationLogger.warn("MockMvc", t);
        }
    }

    public static class PerformAdvice {
        @Advice.OnMethodExit(suppress = Throwable.class)
        public static void onExit(@Advice.Return Object resultActions) {
            onPerform(resultActions);
        }
    }
}
