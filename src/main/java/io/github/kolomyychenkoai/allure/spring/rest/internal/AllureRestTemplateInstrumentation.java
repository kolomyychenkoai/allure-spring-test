package io.github.kolomyychenkoai.allure.spring.rest.internal;

import io.github.kolomyychenkoai.allure.spring.internal.AllureInstrumentation;
import io.github.kolomyychenkoai.allure.spring.internal.AllureInstrumentationLogger;
import net.bytebuddy.asm.Advice;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.named;

/**
 * Вешает {@link AllureRestTemplateInterceptor} на КАЖДЫЙ создаваемый {@code RestTemplate}
 * (байткод на конструкторах) — ловит и {@code TestRestTemplate} (внутри него RestTemplate),
 * и ручной {@code new RestTemplate()}. Код в тестах не нужен.
 * <p>
 * Установка идемпотентна (CAS-гард), ставится из {@code AllureRestTemplateListener}. Сбой
 * инструментирования логируется на WARNING и не роняет тест. RestTemplate, созданные ДО
 * установки, не охвачены (конструктор уже отработал) — но бины теста создаются после
 * {@code beforeTestClass}, где идёт установка.
 */
public final class AllureRestTemplateInstrumentation {

    private static final AtomicBoolean INSTALLED = new AtomicBoolean(false);

    private AllureRestTemplateInstrumentation() {
    }

    public static void install() {
        if (!INSTALLED.compareAndSet(false, true)) {
            return;
        }
        AllureInstrumentation.retransform(named("org.springframework.web.client.RestTemplate"),
                (builder, type, cl, module, pd) -> builder
                        .visit(Advice.to(CtorAdvice.class).on(isConstructor())));
    }

    /** Добавляет наш интерсептор в свежесозданный RestTemplate (без дублей). */
    public static void onConstructed(Object restTemplate) {
        try {
            if (restTemplate instanceof RestTemplate rt) {
                List<ClientHttpRequestInterceptor> interceptors = rt.getInterceptors();
                for (ClientHttpRequestInterceptor existing : interceptors) {
                    if (existing instanceof AllureRestTemplateInterceptor) {
                        return;
                    }
                }
                interceptors.add(new AllureRestTemplateInterceptor());
            }
        } catch (Throwable t) {
            AllureInstrumentationLogger.warn("RestTemplate", t);
        }
    }

    public static class CtorAdvice {
        @Advice.OnMethodExit(suppress = Throwable.class)
        public static void onExit(@Advice.This Object self) {
            onConstructed(self);
        }
    }
}
