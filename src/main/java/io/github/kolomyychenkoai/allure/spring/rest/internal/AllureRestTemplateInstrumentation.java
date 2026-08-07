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
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

/**
 * Вешает {@link AllureRestTemplateInterceptor} на КАЖДЫЙ создаваемый {@code RestTemplate}
 * (байткод на конструкторах) — ловит и {@code TestRestTemplate} (внутри него RestTemplate),
 * и ручной {@code new RestTemplate()}. Код в тестах не нужен.
 * <p>
 * Второй вход — {@code setInterceptors(...)}, который заменяет список целиком; подробности
 * и требования к матчеру — у самого вызова в {@link #install()}.
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
        // Второй вход: setInterceptors(...) ЗАМЕНЯЕТ список целиком и выбрасывает наш интерсептор,
        // поставленный в конструкторе. Так делают прикладной код и RestTemplateCustomizer-бины
        // (они исполняются последними) — HTTP-шаги у потребителя молча исчезали.
        // ⚠️ Метод объявлен НЕ в RestTemplate, а в InterceptingHttpAccessor: матчер по RestTemplate
        // не сматчил бы НИ ОДНОГО метода и стал бы тихим no-op — ровно тот класс бага, от которого
        // защищается эта библиотека. Ретрансформируем объявителя.
        AllureInstrumentation.retransform(
                named("org.springframework.http.client.support.InterceptingHttpAccessor"),
                (builder, type, cl, module, pd) -> builder.visit(Advice.to(SetInterceptorsAdvice.class)
                        .on(named("setInterceptors").and(takesArguments(1)))));
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

    /** Список интерсепторов заменили — возвращаем свой (onConstructed идемпотентен). */
    public static class SetInterceptorsAdvice {
        @Advice.OnMethodExit(suppress = Throwable.class)
        public static void onExit(@Advice.This Object self) {
            onConstructed(self);
        }
    }

    public static class CtorAdvice {
        @Advice.OnMethodExit(suppress = Throwable.class)
        public static void onExit(@Advice.This Object self) {
            onConstructed(self);
        }
    }
}
