package io.github.kolomyychenkoai.allure.spring.assertion;

import io.github.kolomyychenkoai.allure.spring.internal.AllureAdviceSupport;
import io.github.kolomyychenkoai.allure.spring.internal.AllureInstrumentation;
import io.github.kolomyychenkoai.allure.spring.internal.AllureInstrumentationLogger;
import net.bytebuddy.asm.Advice;

import java.util.concurrent.atomic.AtomicBoolean;

import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

/**
 * ByteBuddy-инструментирование Hamcrest ({@code org.hamcrest.MatcherAssert.assertThat}):
 * каждый assertThat даёт в отчёте шаг «Проверка: …» — без кода в тестах.
 * Патчим только 3-арг {@code assertThat(reason, actual, matcher)}; 2-арг
 * {@code assertThat(actual, matcher)} внутри зовёт его (reason=""), поэтому ловятся оба
 * без двойного шага. Шаг пишется ТОЛЬКО для успешной проверки; упавшая шага не создаёт —
 * её падение Allure показывает из коробки на уровне теста.
 * Ставится один раз на JVM — см. {@link AllureAssertionsListener}.
 * <p>
 * Перегрузка {@code assertThat(String, boolean)} (без матчера) намеренно НЕ
 * перехватывается — это не matcher-проверка (просто boolean + reason), её аналог в отчёте
 * дают Spring-ассерты/AssertJ; покрываются только matcher-формы ({@code Matcher}-аргумент).
 */
public final class AllureHamcrestInstrumentation {

    private static final AtomicBoolean INSTALLED = new AtomicBoolean(false);

    private AllureHamcrestInstrumentation() {
    }

    public static void install() {
        if (!INSTALLED.compareAndSet(false, true)) {
            return;
        }
        AllureInstrumentation.retransform(
                named("org.hamcrest.MatcherAssert"),
                (builder, type, cl, module, pd) -> builder
                        .visit(Advice.to(AssertThatAdvice.class)
                                .on(named("assertThat").and(takesArguments(3)))));
    }

    public static class AssertThatAdvice {
        @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
        public static void onExit(@Advice.Argument(0) String reason,
                                  @Advice.Argument(1) Object actual,
                                  @Advice.Argument(2) Object matcher,
                                  @Advice.Thrown Throwable thrown) {
            try {
                String label = (reason != null && !reason.isEmpty()) ? reason + ": " : "";
                AllureAdviceSupport.step("Проверка: " + label + "значение " + AllureAdviceSupport.safe(actual)
                        + ", ожидалось " + AllureAdviceSupport.safe(matcher), thrown);
            } catch (Throwable t) {
                AllureInstrumentationLogger.warn("Hamcrest", t);
            }
        }
    }
}
