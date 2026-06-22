package io.github.kolomyychenkoai.allure.spring.wiremock;

import com.github.tomakehurst.wiremock.client.CountMatchingStrategy;
import com.github.tomakehurst.wiremock.matching.RequestPatternBuilder;
import io.github.kolomyychenkoai.allure.spring.internal.AllureInstrumentation;
import io.github.kolomyychenkoai.allure.spring.internal.AllureInstrumentationLogger;
import io.qameta.allure.Allure;
import io.qameta.allure.AllureLifecycle;
import io.qameta.allure.model.Status;
import io.qameta.allure.model.StepResult;
import net.bytebuddy.asm.Advice;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static net.bytebuddy.matcher.ElementMatchers.named;

/**
 * ByteBuddy-инструментирование WireMock {@code verify(...)} (проверки «дёрнули ли
 * сервис, сколько раз, с чем») и {@code resetAll} (сброс заглушек). У этих операций нет
 * listener-хука, поэтому ловим байткодом. verify даёт шаг «Проверка обращений к
 * заглушке (×N)» (PASSED/FAILED по исходу), resetAll — «WireMock: сброс заглушек».
 * Перехватываются и static {@code client.WireMock.verify}, и {@code WireMockServer.verify}.
 * Ставится один раз на JVM — см. {@link AllureWireMockTestListener}.
 */
public final class AllureWireMockVerifyInstrumentation {

    private static final AtomicBoolean INSTALLED = new AtomicBoolean(false);

    private AllureWireMockVerifyInstrumentation() {
    }

    public static void install() {
        if (!INSTALLED.compareAndSet(false, true)) {
            return;
        }
        AllureInstrumentation.retransform(named("com.github.tomakehurst.wiremock.client.WireMock"),
                (builder, type, cl, module, pd) -> builder.visit(
                        Advice.to(VerifyAdvice.class).on(named("verify"))));
        AllureInstrumentation.retransform(named("com.github.tomakehurst.wiremock.WireMockServer"),
                (builder, type, cl, module, pd) -> builder
                        .visit(Advice.to(VerifyAdvice.class).on(named("verify")))
                        .visit(Advice.to(ResetAdvice.class).on(named("resetAll"))));
    }

    /** Логика логирования verify (вынесена из advice). */
    public static void onVerify(Object[] args, Throwable thrown) {
        AllureLifecycle lifecycle = Allure.getLifecycle();
        String stepId = UUID.randomUUID().toString();
        boolean started = false;
        try {
            if (!lifecycle.getCurrentTestCase().isPresent()) {
                return;
            }
            String count = null;
            String pattern = "";
            if (args != null) {
                for (Object a : args) {
                    if (a instanceof Integer integer) {
                        count = "×" + integer;
                    } else if (a instanceof CountMatchingStrategy strategy) {
                        count = String.valueOf(strategy); // напр. «less than 3»
                    } else if (a instanceof RequestPatternBuilder builder) {
                        pattern = String.valueOf(builder.build());
                    }
                }
            }
            String name = "Проверка обращений к заглушке" + (count != null ? " (" + count + ")" : "");
            lifecycle.startStep(stepId, new StepResult()
                    .setName(name)
                    .setStatus(thrown == null ? Status.PASSED : Status.FAILED));
            started = true;
            Allure.addAttachment("Условие проверки", "text/plain", pattern);
        } catch (Throwable t) {
            AllureInstrumentationLogger.warn("WireMockVerify", t);
        } finally {
            if (started) {
                try {
                    lifecycle.stopStep(stepId);
                } catch (Throwable ignored) {
                    // шаг гарантированно закрываем
                }
            }
        }
    }

    /** Логика логирования resetAll. */
    public static void onResetAll() {
        try {
            if (Allure.getLifecycle().getCurrentTestCase().isPresent()) {
                Allure.step("WireMock: сброс заглушек", Status.PASSED);
            }
        } catch (Throwable t) {
            AllureInstrumentationLogger.warn("WireMockReset", t);
        }
    }

    public static class VerifyAdvice {
        @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
        public static void onExit(@Advice.AllArguments Object[] args, @Advice.Thrown Throwable thrown) {
            onVerify(args, thrown);
        }
    }

    public static class ResetAdvice {
        @Advice.OnMethodExit(suppress = Throwable.class)
        public static void onExit() {
            onResetAll();
        }
    }
}
