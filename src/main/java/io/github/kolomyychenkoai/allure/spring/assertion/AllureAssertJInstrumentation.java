package io.github.kolomyychenkoai.allure.spring.assertion;

import io.github.kolomyychenkoai.allure.spring.internal.AllureInstrumentation;
import io.github.kolomyychenkoai.allure.spring.internal.AllureInstrumentationLogger;
import io.qameta.allure.Allure;
import io.qameta.allure.model.Status;
import net.bytebuddy.asm.Advice;
import org.assertj.core.api.AbstractAssert;

import java.util.concurrent.atomic.AtomicBoolean;

import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.isStatic;
import static net.bytebuddy.matcher.ElementMatchers.isSubTypeOf;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.not;

/**
 * ByteBuddy-инструментирование AssertJ ({@code AbstractAssert} и наследники):
 * каждый ассерт-метод ({@code isEqualTo}, {@code startsWith}, {@code contains}…) даёт
 * в отчёте шаг «Проверка: значение X — method arg» — без кода в тестах.
 * Подход — blacklist: перехватываем ВСЕ public non-static методы, КРОМЕ
 * конфигурационных/fluent-builder (as, describedAs, extracting, isNotNull…), которые
 * не являются проверками и/или зовутся внутренне. Шаг и при успехе (PASSED), и при
 * падении (FAILED). Ставится один раз на JVM — см. {@link AllureAssertionsListener}.
 */
public final class AllureAssertJInstrumentation {

    private static final AtomicBoolean INSTALLED = new AtomicBoolean(false);

    // AssertJ-методы делегируют в super (isEqualTo → AbstractAssert.isEqualTo и т.п.) — оба
    // инструментируются. Считаем глубину вложенности: логируем только внешний (пользовательский)
    // вызов, внутренние делегаты пропускаем. Счётчик, а не флаг — на случай многоуровневой делегации.
    private static final ThreadLocal<Integer> DEPTH = ThreadLocal.withInitial(() -> 0);

    private AllureAssertJInstrumentation() {
    }

    /** Вход в ассерт-метод; возвращает глубину (1 — внешний вызов). */
    public static int enter() {
        int depth = DEPTH.get() + 1;
        DEPTH.set(depth);
        return depth;
    }

    /** Выход из ассерт-метода. */
    public static void exit() {
        int depth = DEPTH.get() - 1;
        DEPTH.set(depth < 0 ? 0 : depth);
    }

    public static void install() {
        if (!INSTALLED.compareAndSet(false, true)) {
            return;
        }
        AllureInstrumentation.retransform(
                isSubTypeOf(AbstractAssert.class),
                (builder, type, cl, module, pd) -> builder.visit(Advice.to(AssertJAdvice.class)
                        .on(isPublic()
                                .and(not(isStatic()))
                                .and(not(named("as")))
                                .and(not(named("describedAs")))
                                .and(not(named("withFailMessage")))
                                .and(not(named("withRepresentation")))
                                .and(not(named("overridingErrorMessage")))
                                .and(not(named("usingComparator")))
                                .and(not(named("usingElementComparator")))
                                .and(not(named("usingRecursiveComparison")))
                                .and(not(named("usingDefaultComparator")))
                                .and(not(named("withThreadDumpOnError")))
                                .and(not(named("withAssertionInfo")))
                                .and(not(named("inHexadecimal")))
                                .and(not(named("inBinary")))
                                .and(not(named("extracting")))
                                .and(not(named("filteredOn")))
                                .and(not(named("asInstanceOf")))
                                .and(not(named("asString")))
                                .and(not(named("asList")))
                                .and(not(named("newAbstractIterableAssert")))
                                .and(not(named("getActual")))
                                .and(not(named("actual")))
                                .and(not(named("info")))
                                .and(not(named("myself")))
                                .and(not(named("objects")))
                                .and(not(named("throwUnsupportedExceptionOnEquals")))
                                .and(not(named("hashCode")))
                                .and(not(named("equals")))
                                .and(not(named("toString")))
                                .and(not(named("failWithMessage")))
                                .and(not(named("failWithActualExpectedAndMessage")))
                                .and(not(named("isNotNull"))))));
    }

    public static class AssertJAdvice {
        // без suppress: enter() тривиален (ThreadLocal), бросить не может, а suppress
        // маскировал бы рассинхрон счётчика глубины.
        @Advice.OnMethodEnter
        public static boolean onEnter() {
            return enter() == 1; // true — это внешний (пользовательский) вызов
        }

        @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
        public static void onExit(@Advice.Enter boolean outermost,
                                  @Advice.FieldValue("actual") Object actual,
                                  @Advice.Origin("#m") String methodName,
                                  @Advice.AllArguments Object[] args,
                                  @Advice.Thrown Throwable thrown) {
            try {
                exit();
                if (!outermost) {
                    return; // внутренний делегат (super.*) — не дублируем
                }
                StringBuilder sb = new StringBuilder("Проверка: значение ").append(actual)
                        .append(" — ").append(methodName);
                if (args != null) {
                    for (int i = 0; i < args.length; i++) {
                        Object a = args[i];
                        // varargs приходят массивом — показываем элементы, а не [Ljava…
                        String rendered = (a instanceof Object[])
                                ? java.util.Arrays.toString((Object[]) a) : String.valueOf(a);
                        sb.append(i == 0 ? " " : ", ").append(rendered);
                    }
                }
                Allure.step(sb.toString(), thrown == null ? Status.PASSED : Status.FAILED);
            } catch (Throwable t) {
                AllureInstrumentationLogger.warn("AssertJ", t);
            }
        }
    }
}
