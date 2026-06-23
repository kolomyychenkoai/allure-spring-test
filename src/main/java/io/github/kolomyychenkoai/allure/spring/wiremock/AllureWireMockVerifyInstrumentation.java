package io.github.kolomyychenkoai.allure.spring.wiremock;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.CountMatchingStrategy;
import com.github.tomakehurst.wiremock.matching.RequestPatternBuilder;
import com.github.tomakehurst.wiremock.stubbing.StubMapping;
import io.github.kolomyychenkoai.allure.spring.internal.AllureAdviceSupport;
import io.github.kolomyychenkoai.allure.spring.internal.AllureInstrumentation;
import io.github.kolomyychenkoai.allure.spring.internal.AllureInstrumentationLogger;
import io.qameta.allure.Allure;
import io.qameta.allure.model.Status;
import net.bytebuddy.asm.Advice;

import java.util.concurrent.atomic.AtomicBoolean;

import static net.bytebuddy.matcher.ElementMatchers.named;

/**
 * ByteBuddy-инструментирование WireMock, у которого нет listener-хука:
 * <ul>
 *   <li>{@code stubFor} → шаг «Создана заглушка …» в момент создания (живой, верный порядок,
 *       переживает {@code resetAll()});</li>
 *   <li>{@code verify(...)} → шаг «Проверка обращений к заглушке (×N)» только для УСПЕШНОЙ
 *       проверки; упавший verify шага не создаёт (падение покажет Allure на уровне теста);</li>
 *   <li>{@code WireMockServer.resetAll} → перед сбросом снимает near-miss и состояния
 *       сценариев со СЕРВЕРА (иначе reset их сотрёт до afterTestMethod), затем шаг
 *       «WireMock: сброс заглушек»;</li>
 *   <li>статический {@code WireMock.reset()} (старый DSL через {@code configureFor}) →
 *       тот же шаг «WireMock: сброс заглушек» (без снимка near-miss/сценариев — у статики
 *       нет ссылки на сервер; снимок остаётся у инстансного {@code resetAll}).</li>
 * </ul>
 * Перехватываются и static {@code client.WireMock.*}, и {@code WireMockServer.*}.
 * Дубля шага НЕТ: {@code verify}-перегрузки делегируют в {@code verifyThat} (не в {@code verify}),
 * {@code stubFor} — в {@code register}, статический {@code reset()} — в инстансный
 * {@code resetMappings()} (мы их не матчим). Проверено на WireMock 3.9/3.13.
 * <p>
 * ЧАСТИЧНЫЕ сбросы намеренно НЕ логируются (логируем только полный сброс): инстансные
 * {@code resetMappings/resetRequests/resetScenarios} и статические {@code resetAllRequests/
 * resetScenario/resetAllScenarios} — у них нет отдельного шага.
 * Установка идемпотентна (CAS-гард {@code INSTALLED}, потокобезопасно) — один раз на JVM.
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
                (builder, type, cl, module, pd) -> builder
                        .visit(Advice.to(VerifyAdvice.class).on(named("verify")))
                        .visit(Advice.to(StubAdvice.class).on(named("stubFor")))
                        .visit(Advice.to(StaticResetAdvice.class).on(named("reset"))));
        AllureInstrumentation.retransform(named("com.github.tomakehurst.wiremock.WireMockServer"),
                (builder, type, cl, module, pd) -> builder
                        .visit(Advice.to(VerifyAdvice.class).on(named("verify")))
                        .visit(Advice.to(StubAdvice.class).on(named("stubFor")))
                        .visit(Advice.to(ResetAdvice.class).on(named("resetAll"))));
    }

    /** Логика шага создания заглушки (вынесена из advice). */
    public static void onStub(Object stub) {
        if (stub instanceof StubMapping mapping) {
            AllureWireMockSteps.stub(mapping);
        }
    }

    /** Логика логирования verify (вынесена из advice). Шаг — только для УСПЕШНОЙ проверки. */
    public static void onVerify(Object[] args, Throwable thrown) {
        try {
            // упавший verify не логируем — падение покажет Allure (тест падает)
            if (thrown != null || !AllureWireMockSteps.active()) {
                return;
            }
            String count = null;
            String pattern = "";
            if (args != null) {
                for (Object a : args) {
                    if (a instanceof Integer integer) {
                        count = "×" + integer;
                    } else if (a instanceof CountMatchingStrategy strategy) {
                        count = AllureAdviceSupport.safe(strategy); // напр. «less than 3»
                    } else if (a instanceof RequestPatternBuilder builder) {
                        pattern = AllureAdviceSupport.safe(builder.build());
                    }
                }
            }
            final String condition = pattern;
            Allure.step("Проверка обращений к заглушке" + (count != null ? " (" + count + ")" : ""), step -> {
                Allure.addAttachment("Условие проверки", "text/plain", condition);
            });
        } catch (Throwable t) {
            AllureInstrumentationLogger.warn("WireMockVerify", t);
        }
    }

    /**
     * Логика resetAll. Вызывается ПЕРЕД фактическим сбросом (advice OnMethodEnter), поэтому
     * near-miss/сценарии ещё доступны на сервере — снимаем их до того, как reset всё сотрёт.
     */
    public static void onResetAll(Object server) {
        try {
            if (!AllureWireMockSteps.active()) {
                return;
            }
            if (server instanceof WireMockServer wireMockServer) {
                AllureWireMockSteps.nearMisses(wireMockServer);
                AllureWireMockSteps.scenarios(wireMockServer);
            }
            Allure.step("WireMock: сброс заглушек", Status.PASSED);
        } catch (Throwable t) {
            AllureInstrumentationLogger.warn("WireMockReset", t);
        }
    }

    /** Логика статического {@code WireMock.reset()} (старый DSL): шаг сброса без снимка сервера. */
    public static void onStaticReset() {
        try {
            if (AllureWireMockSteps.active()) {
                Allure.step("WireMock: сброс заглушек", Status.PASSED);
            }
        } catch (Throwable t) {
            AllureInstrumentationLogger.warn("WireMockStaticReset", t);
        }
    }

    public static class StubAdvice {
        @Advice.OnMethodExit(suppress = Throwable.class)
        public static void onExit(@Advice.Return Object stub) {
            onStub(stub);
        }
    }

    public static class StaticResetAdvice {
        @Advice.OnMethodEnter(suppress = Throwable.class)
        public static void onEnter() {
            onStaticReset();
        }
    }

    public static class VerifyAdvice {
        @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
        public static void onExit(@Advice.AllArguments Object[] args, @Advice.Thrown Throwable thrown) {
            onVerify(args, thrown);
        }
    }

    public static class ResetAdvice {
        @Advice.OnMethodEnter(suppress = Throwable.class)
        public static void onEnter(@Advice.This Object server) {
            onResetAll(server);
        }
    }
}
