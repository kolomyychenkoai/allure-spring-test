package io.github.kolomyychenkoai.allure.spring.web.internal;

import io.github.kolomyychenkoai.allure.spring.internal.AllureInstrumentation;
import io.github.kolomyychenkoai.allure.spring.internal.AllureInstrumentationLogger;
import net.bytebuddy.asm.Advice;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.web.client.RestClient;

import java.util.concurrent.atomic.AtomicBoolean;

import static net.bytebuddy.matcher.ElementMatchers.named;

/**
 * Вешает {@link AllureRestTemplateInterceptor} на КАЖДЫЙ собираемый {@code RestClient}
 * (байткод на {@code DefaultRestClientBuilder.build()}) — ловит и {@code RestClient.create()},
 * и {@code RestClient.builder()}, и Spring-инжектируемый билдер (все строятся через
 * {@code DefaultRestClientBuilder}). Код в тестах не нужен.
 * <p>
 * Интерсептор — тот же generic {@link AllureRestTemplateInterceptor}, что и у RestTemplate
 * (это просто {@link ClientHttpRequestInterceptor}): шаг «HTTP METHOD path → status» с
 * вложениями «HTTP Request»/«HTTP Response», единообразно с остальными HTTP-клиентами.
 * <p>
 * Установка идемпотентна (CAS-гард), ставится из {@code AllureRestClientListener}. Перехват
 * добавляется в {@code build()} (а не в конструктор билдера): билдер можно переиспользовать,
 * поэтому добавляем без дублей. Сбой инструментирования логируется на WARNING и не роняет тест.
 * <p>
 * ⚠️ <b>Версионно-хрупкое допущение.</b> {@code DefaultRestClientBuilder} —
 * package-private ВНУТРЕННИЙ класс Spring (НЕ публичный API), проверено на Spring 6.2.x. Это
 * единственная реализация {@code RestClient.Builder}, через неё строятся и {@code RestClient.create()},
 * и {@code RestClient.builder()}, и Spring-инжектируемый билдер — поэтому одной точки достаточно.
 * При апгрейде Spring класс могут переименовать/убрать молча → перехват отвалится. Это допущение
 * закреплено канарейкой {@code InstrumentationApiCanaryTest#restClientMatchers} (краснеет точечно).
 * By-design НЕ покрыта кастомная реализация {@code RestClient.Builder} в обход (см. README).
 */
public final class AllureRestClientInstrumentation {

    private static final AtomicBoolean INSTALLED = new AtomicBoolean(false);

    private AllureRestClientInstrumentation() {
    }

    public static void install() {
        if (!INSTALLED.compareAndSet(false, true)) {
            return;
        }
        AllureInstrumentation.retransform(named("org.springframework.web.client.DefaultRestClientBuilder"),
                (builder, type, cl, module, pd) -> builder
                        .visit(Advice.to(BuildAdvice.class).on(named("build"))));
    }

    /** Добавляет наш интерсептор в список перехватчиков билдера (без дублей). */
    public static void onBuild(Object restClientBuilder) {
        try {
            if (restClientBuilder instanceof RestClient.Builder b) {
                b.requestInterceptors(interceptors -> {
                    for (ClientHttpRequestInterceptor existing : interceptors) {
                        if (existing instanceof AllureRestTemplateInterceptor) {
                            return;
                        }
                    }
                    interceptors.add(new AllureRestTemplateInterceptor());
                });
            }
        } catch (Throwable t) {
            AllureInstrumentationLogger.warn("RestClient", t);
        }
    }

    public static class BuildAdvice {
        @Advice.OnMethodEnter(suppress = Throwable.class)
        public static void onEnter(@Advice.This Object self) {
            onBuild(self);
        }
    }
}
