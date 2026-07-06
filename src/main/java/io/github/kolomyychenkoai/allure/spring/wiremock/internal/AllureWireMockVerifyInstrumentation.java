package io.github.kolomyychenkoai.allure.spring.wiremock.internal;

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

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
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
 *       «WireMock: сброс заглушек (:порт)». НЕСКОЛЬКО серверов (сервис мокает несколько
 *       зависимостей) → шаг на КАЖДЫЙ сервер с его портом; ПОВТОРНЫЙ сброс одного сервера
 *       за тест — дубль, глушится (ключ дедупа {@code (тест-кейс, сервер)});</li>
 *   <li>статический {@code WireMock.reset()} (старый DSL через {@code configureFor}) →
 *       шаг «WireMock: сброс заглушек» (без порта и без снимка near-miss/сценариев — у статики
 *       нет ссылки на сервер; снимок остаётся у инстансного {@code resetAll}).</li>
 * </ul>
 * Перехватываются и static {@code client.WireMock.*}, и {@code WireMockServer.*}.
 * Дубля шага НЕТ: {@code verify}-перегрузки делегируют в {@code verifyThat} (не в {@code verify}),
 * {@code stubFor} — в {@code register}, статический {@code reset()} — в инстансный
 * {@code resetMappings()} (мы их не матчим). Проверено на WireMock 3.9/3.13.
 * <p>
 * ЧАСТИЧНЫЕ и дефолт-сбросы намеренно НЕ логируются (логируем только полный «сброс заглушек»):
 * инстансные {@code resetMappings/resetRequests/resetScenarios/resetToDefaultMappings} и статические
 * {@code resetAllRequests/resetScenario/resetAllScenarios/resetToDefault} — у них нет отдельного шага.
 * <p>
 * ⚠️ Инстансный {@code resetAll} и статический {@code WireMock.reset()} — РАЗНЫЕ точки входа с разными
 * ключами дедупа, поэтому если один teardown зовёт ОБА на один сервер (так делает Spring Cloud Contract:
 * {@code WireMockConfiguration.resetMappings()} каскадит {@code resetAll()}+{@code WireMock.reset()}+
 * частичные), в отчёте будет ДВА шага «сброс заглушек» (инстансный с портом + статический без) — это не
 * 10×-баг, а честное отражение двух вызовов; связать статику с конкретным сервером нечем (у неё нет ссылки).
 * Установка идемпотентна (CAS-гард {@code INSTALLED}, потокобезопасно) — один раз на JVM.
 */
public final class AllureWireMockVerifyInstrumentation {

    private static final AtomicBoolean INSTALLED = new AtomicBoolean(false);

    /**
     * Что уже дало шаг сброса в текущем тест-кейсе. Сброс — теардаун-рутина: сервис мокает
     * несколько зависимостей (свой {@code WireMockServer} на каждую), teardown сбрасывает каждый —
     * плюс возможный ручной/повторный сброс. НЕСКОЛЬКО серверов — разные события, логируем КАЖДЫЙ
     * (в имя шага порт-метка). А вот ПОВТОРНЫЙ сброс ОДНОГО сервера за тест — дубль, его глушим.
     * <p>
     * Ключ дедупа — САМ объект сервера (identity), НЕ порт: порт у сервера меняется по ходу жизни
     * (до старта / после stop / только-HTTPS → {@code port()} бросает), а identity стабильна — иначе
     * один и тот же сервер получил бы два ключа и дал бы дубль, который мы как раз лечим.
     * {@link IdentityHashMap}-множество исключает и коллизии {@code hashCode}. Статический
     * {@code WireMock.reset()} сервера не знает — у него отдельный ключ-синглтон {@link #STATIC_RESET_KEY}.
     * ThreadLocal (а не общий флаг) — чтобы параллельные тесты не гасили сброс друг у друга.
     */
    private static final ThreadLocal<ResetLog> RESET_LOG = ThreadLocal.withInitial(ResetLog::new);

    /** Ключ дедупа для статического {@code WireMock.reset()} — у него нет ссылки на сервер. */
    private static final Object STATIC_RESET_KEY = new Object();

    private AllureWireMockVerifyInstrumentation() {
    }

    /** Что уже отметилось шагом сброса (серверы by-identity + STATIC_RESET_KEY) в одном тест-кейсе. */
    private static final class ResetLog {
        private String testCaseUuid;
        private final Set<Object> resetKeys = Collections.newSetFromMap(new IdentityHashMap<>());
    }

    /**
     * true — если для (текущий тест-кейс, этот ключ) шаг сброса ещё не писали (и помечаем, что
     * теперь написали). Снимок near-miss/сценариев этим НЕ гейтится — он самодедуплицируется
     * (reset стирает данные) и по разным серверам несёт разное; глушим только повтор шага сброса
     * одного и того же сервера. При смене тест-кейса набор чистится — тот же сервер в следующем
     * тесте снова даст свой шаг.
     */
    private static boolean firstResetForKeyInTestCase(String testCaseUuid, Object key) {
        ResetLog log = RESET_LOG.get();
        if (!testCaseUuid.equals(log.testCaseUuid)) {
            log.testCaseUuid = testCaseUuid;
            log.resetKeys.clear();
        }
        return log.resetKeys.add(key); // add() == true, если этот ключ в тесте ещё не сбрасывали
    }

    public static void install() {
        if (!INSTALLED.compareAndSet(false, true)) {
            return;
        }
        AllureInstrumentation.retransform(named("com.github.tomakehurst.wiremock.client.WireMock"),
                (builder, type, cl, module, pd) -> builder
                        .visit(Advice.to(VerifyAdvice.class).on(named("verify")))
                        .visit(Advice.to(StubAdvice.class).on(named("stubFor")))
                        .visit(Advice.to(StaticResetAdvice.class).on(named("reset")))
                        .visit(Advice.to(StaticPartialResetAdvice.class).on(
                                named("resetAllRequests").or(named("resetScenario"))
                                        .or(named("resetAllScenarios")))));
        AllureInstrumentation.retransform(named("com.github.tomakehurst.wiremock.WireMockServer"),
                (builder, type, cl, module, pd) -> builder
                        .visit(Advice.to(VerifyAdvice.class).on(named("verify")))
                        .visit(Advice.to(StubAdvice.class).on(named("stubFor")))
                        .visit(Advice.to(ResetAdvice.class).on(named("resetAll")))
                        .visit(Advice.to(PartialResetAdvice.class).on(
                                named("resetMappings").or(named("resetRequests"))
                                        .or(named("resetScenarios")))));
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
            String testCase = AllureWireMockSteps.currentTestCase();
            if (testCase == null) {
                return;
            }
            // ключ дедупа — сам сервер (identity, стабильна); порт — только метка имени, и может быть недоступен
            Object key = STATIC_RESET_KEY;
            String portLabel = "";
            if (server instanceof WireMockServer wireMockServer) {
                // снимок near-miss/сценариев — по КАЖДОМУ серверу (они разные), до фактического сброса
                AllureWireMockSteps.nearMisses(wireMockServer);
                AllureWireMockSteps.scenarios(wireMockServer);
                key = wireMockServer;
                Integer port = safePort(wireMockServer);
                if (port != null) {
                    portLabel = " (:" + port + ")";
                }
            }
            // несколько серверов → шаг на каждый (с портом); повтор одного сервера за тест → дубль, глушим
            if (firstResetForKeyInTestCase(testCase, key)) {
                Allure.step("WireMock: сброс заглушек" + portLabel, Status.PASSED);
            }
        } catch (Throwable t) {
            AllureInstrumentationLogger.warn("WireMockReset", t);
        }
    }

    /** Порт сервера, либо null, если его нельзя получить (не роняем инструментирование). */
    private static Integer safePort(WireMockServer server) {
        try {
            return server.port();
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Логика статического {@code WireMock.reset()} (старый DSL): шаг сброса без снимка сервера.
     * У статики нет ссылки на сервер — ключ дедупа {@code static}, порт в имени не показываем.
     */
    public static void onStaticReset() {
        try {
            String testCase = AllureWireMockSteps.currentTestCase();
            if (testCase != null && firstResetForKeyInTestCase(testCase, STATIC_RESET_KEY)) {
                Allure.step("WireMock: сброс заглушек", Status.PASSED);
            }
        } catch (Throwable t) {
            AllureInstrumentationLogger.warn("WireMockStaticReset", t);
        }
    }

    /**
     * Частичный сброс на инстансе сервера ({@code resetMappings/resetRequests/resetScenarios}).
     * Перед сбросом снимаем то, что метод вот-вот сотрёт: requests → near-miss журнала,
     * scenarios → состояния сценариев. Так частичный сброс не «съедает» данные молча.
     */
    public static void onPartialReset(Object server, String method) {
        try {
            if (!AllureWireMockSteps.active()) {
                return;
            }
            if (server instanceof WireMockServer wireMockServer) {
                if ("resetRequests".equals(method)) {
                    AllureWireMockSteps.nearMisses(wireMockServer);
                } else if ("resetScenarios".equals(method)) {
                    AllureWireMockSteps.scenarios(wireMockServer);
                }
            }
            Allure.step("WireMock: частичный сброс (" + method + ")", Status.PASSED);
        } catch (Throwable t) {
            AllureInstrumentationLogger.warn("WireMockPartialReset", t);
        }
    }

    /** Частичный сброс через статический DSL ({@code resetAllRequests/resetScenario/...}): шаг без снимка. */
    public static void onStaticPartialReset(String method) {
        try {
            if (AllureWireMockSteps.active()) {
                Allure.step("WireMock: частичный сброс (" + method + ")", Status.PASSED);
            }
        } catch (Throwable t) {
            AllureInstrumentationLogger.warn("WireMockStaticPartialReset", t);
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

    public static class PartialResetAdvice {
        @Advice.OnMethodEnter(suppress = Throwable.class)
        public static void onEnter(@Advice.This Object server, @Advice.Origin("#m") String method) {
            onPartialReset(server, method);
        }
    }

    public static class StaticPartialResetAdvice {
        @Advice.OnMethodEnter(suppress = Throwable.class)
        public static void onEnter(@Advice.Origin("#m") String method) {
            onStaticPartialReset(method);
        }
    }
}
