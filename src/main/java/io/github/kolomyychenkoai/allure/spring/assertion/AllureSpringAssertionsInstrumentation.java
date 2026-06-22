package io.github.kolomyychenkoai.allure.spring.assertion;

import io.github.kolomyychenkoai.allure.spring.internal.AllureInstrumentation;
import io.github.kolomyychenkoai.allure.spring.internal.AllureInstrumentationLogger;
import io.qameta.allure.Allure;
import io.qameta.allure.model.Status;
import net.bytebuddy.asm.Advice;

import java.util.concurrent.atomic.AtomicBoolean;

import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

/**
 * ByteBuddy-инструментирование Spring-ассертов ({@code org.springframework.test.util.AssertionErrors}):
 * каждый assertEquals/assertNotEquals/assertTrue/assertFalse/assertNull/assertNotNull/fail
 * даёт в Allure-отчёте шаг «Assert: …» — БЕЗ кода в тестах. Шаг создаётся и при УСПЕХЕ
 * (Status.PASSED), и при ПАДЕНИИ ассерта (Status.FAILED) — чтобы упавшая проверка была
 * видна в отчёте. Advice инлайнится в байткод AssertionErrors, поэтому ссылается только
 * на Allure + j.u.l-логгер. Ставится один раз на JVM — см. {@link AllureAssertionsListener}.
 */
public final class AllureSpringAssertionsInstrumentation {

    private static final AtomicBoolean INSTALLED = new AtomicBoolean(false);

    // Spring assertFalse/assertNull/assertNotNull внутри зовут assertTrue — чтобы не было
    // двойного шага, на время делегирующего ассерта помечаем поток и внутренний assertTrue молчит.
    private static final ThreadLocal<Boolean> DELEGATING = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private AllureSpringAssertionsInstrumentation() {
    }

    public static boolean delegating() {
        return DELEGATING.get();
    }

    public static void delegating(boolean value) {
        DELEGATING.set(value);
    }

    /** Шаг с автоматическим статусом по факту падения. */
    public static void step(String name, Throwable thrown) {
        Allure.step(name, thrown == null ? Status.PASSED : Status.FAILED);
    }

    public static void install() {
        if (!INSTALLED.compareAndSet(false, true)) {
            return;
        }
        AllureInstrumentation.retransform(
                named("org.springframework.test.util.AssertionErrors"),
                (builder, type, cl, module, pd) -> builder
                        .visit(Advice.to(AssertEqualsAdvice.class)
                                .on(named("assertEquals").and(takesArguments(3))))
                        .visit(Advice.to(AssertNotEqualsAdvice.class)
                                .on(named("assertNotEquals").and(takesArguments(3))))
                        .visit(Advice.to(AssertTrueAdvice.class)
                                .on(named("assertTrue").and(takesArguments(2))))
                        .visit(Advice.to(AssertFalseAdvice.class)
                                .on(named("assertFalse").and(takesArguments(2))))
                        .visit(Advice.to(AssertNullAdvice.class)
                                .on(named("assertNull").and(takesArguments(2))))
                        .visit(Advice.to(AssertNotNullAdvice.class)
                                .on(named("assertNotNull").and(takesArguments(2))))
                        .visit(Advice.to(FailAdvice.class)
                                .on(named("fail").and(takesArguments(1)))));
    }

    public static class AssertEqualsAdvice {
        @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
        public static void onExit(@Advice.Argument(0) String message,
                                  @Advice.Argument(1) Object expected,
                                  @Advice.Argument(2) Object actual,
                                  @Advice.Thrown Throwable thrown) {
            try {
                step("Assert: " + message + " — expected " + expected
                        + (thrown == null ? " = " : " ≠ ") + actual, thrown);
            } catch (Throwable t) {
                AllureInstrumentationLogger.warn("SpringAssertEquals", t);
            }
        }
    }

    public static class AssertNotEqualsAdvice {
        @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
        public static void onExit(@Advice.Argument(0) String message,
                                  @Advice.Argument(1) Object unexpected,
                                  @Advice.Argument(2) Object actual,
                                  @Advice.Thrown Throwable thrown) {
            try {
                step("Assert: " + message + (thrown == null
                        ? " — unexpected " + unexpected + " != " + actual
                        : " — значения равны: " + actual), thrown);
            } catch (Throwable t) {
                AllureInstrumentationLogger.warn("SpringAssertNotEquals", t);
            }
        }
    }

    public static class AssertTrueAdvice {
        @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
        public static void onExit(@Advice.Argument(0) String message, @Advice.Thrown Throwable thrown) {
            try {
                // внутренний вызов из assertFalse/assertNull/assertNotNull — пропускаем (не дублируем)
                if (delegating()) {
                    return;
                }
                step("Assert: " + message + (thrown == null ? " — true" : " — false"), thrown);
            } catch (Throwable t) {
                AllureInstrumentationLogger.warn("SpringAssertTrue", t);
            }
        }
    }

    public static class AssertFalseAdvice {
        @Advice.OnMethodEnter(suppress = Throwable.class)
        public static void onEnter() {
            delegating(true);
        }

        @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
        public static void onExit(@Advice.Argument(0) String message, @Advice.Thrown Throwable thrown) {
            try {
                delegating(false);
                step("Assert: " + message + (thrown == null ? " — false" : " — true"), thrown);
            } catch (Throwable t) {
                AllureInstrumentationLogger.warn("SpringAssertFalse", t);
            }
        }
    }

    public static class AssertNullAdvice {
        @Advice.OnMethodEnter(suppress = Throwable.class)
        public static void onEnter() {
            delegating(true);
        }

        @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
        public static void onExit(@Advice.Argument(0) String message,
                                  @Advice.Argument(1) Object actual,
                                  @Advice.Thrown Throwable thrown) {
            try {
                delegating(false);
                step("Assert: " + message + " — actual " + actual
                        + (thrown == null ? " is null" : " is NOT null"), thrown);
            } catch (Throwable t) {
                AllureInstrumentationLogger.warn("SpringAssertNull", t);
            }
        }
    }

    public static class AssertNotNullAdvice {
        @Advice.OnMethodEnter(suppress = Throwable.class)
        public static void onEnter() {
            delegating(true);
        }

        @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
        public static void onExit(@Advice.Argument(0) String message,
                                  @Advice.Argument(1) Object actual,
                                  @Advice.Thrown Throwable thrown) {
            try {
                delegating(false);
                step("Assert: " + message + (thrown == null
                        ? " — actual " + actual + " is not null"
                        : " — actual is null"), thrown);
            } catch (Throwable t) {
                AllureInstrumentationLogger.warn("SpringAssertNotNull", t);
            }
        }
    }

    public static class FailAdvice {
        @Advice.OnMethodEnter(suppress = Throwable.class)
        public static void onEnter(@Advice.Argument(0) String message) {
            try {
                Allure.step("Assert fail: " + message, Status.FAILED);
            } catch (Throwable t) {
                AllureInstrumentationLogger.warn("SpringFail", t);
            }
        }
    }
}
