package io.github.kolomyychenkoai.allure.spring.assertion.internal;

import io.github.kolomyychenkoai.allure.spring.internal.AllureAdviceSupport;
import io.github.kolomyychenkoai.allure.spring.internal.AllureInstrumentation;
import io.github.kolomyychenkoai.allure.spring.internal.AllureInstrumentationLogger;
import net.bytebuddy.asm.Advice;
import org.assertj.core.api.AbstractAssert;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.isStatic;
import static net.bytebuddy.matcher.ElementMatchers.isSubTypeOf;
import static net.bytebuddy.matcher.ElementMatchers.namedOneOf;
import static net.bytebuddy.matcher.ElementMatchers.not;

/**
 * ByteBuddy-инструментирование AssertJ ({@code AbstractAssert} и наследники):
 * каждый ассерт-метод ({@code isEqualTo}, {@code startsWith}, {@code contains}…) даёт
 * в отчёте шаг «Проверка: значение X — method arg» — без кода в тестах.
 * <p>
 * <b>Подход — blacklist</b> (а не whitelist), чтобы ловить и КАСТОМНЫЕ ассерты потребителя
 * (его {@code extends AbstractAssert} с произвольно названными проверками). Перехватываем
 * ВСЕ public non-static методы, КРОМЕ перечисленных не-проверок: конфигурация/описание
 * ({@code as}, {@code describedAs}, {@code withFailMessage}, {@code usingComparator}…),
 * извлечение/навигация ({@code extracting}, {@code filteredOn}, {@code first}, {@code last},
 * {@code element}…) — они возвращают производный/тот же assert, сами ничего не проверяют,
 * и шаг по ним был бы ложным. ВАЖНО: настоящие проверки {@code satisfies}/{@code returns}/
 * {@code matches} в blacklist НЕ входят — они логируются. При добавлении новых
 * fluent/navigation-методов в AssertJ их, возможно, нужно дописать сюда.
 * <p>
 * Шаг пишется ТОЛЬКО для успешного ассерта; упавший ассерт шага не создаёт — его падение
 * Allure показывает из коробки на уровне теста. Ставится один раз на JVM —
 * см. {@link AllureAssertionsListener}.
 * <p>
 * <b>Дизайн и хрупкость этого узла</b> (порядок загрузки классов, дедуп по глубине,
 * отвергнутые альтернативы, что проверять при апгрейде) — ADR
 * {@code docs/adr/0001-assertj-instrumentation.md}.
 */
public final class AllureAssertJInstrumentation {

    private static final AtomicBoolean INSTALLED = new AtomicBoolean(false);

    // Не-проверки AssertJ (проверено на assertj-core 3.27.x; при апгрейде дополнить).
    // Конфигурация/описание ассерта (fluent, ничего не проверяют):
    private static final String[] CONFIG_METHODS = {
            "as", "describedAs", "withFailMessage", "withRepresentation", "overridingErrorMessage",
            "usingComparator", "usingElementComparator", "usingRecursiveComparison", "usingDefaultComparator",
            "usingRecursiveAssertion", "withThreadDumpOnError", "withAssertionInfo", "inHexadecimal", "inBinary",
            // семейство usingComparator*/using*ElementComparator*/usingEquals — тоже конфигурация:
            "usingEquals", "usingComparatorForType", "usingComparatorForFields",
            "usingComparatorForElementFieldsWithNames", "usingComparatorForElementFieldsWithType",
            "usingDefaultElementComparator", "usingFieldByFieldElementComparator",
            "usingRecursiveFieldByFieldElementComparator", "usingRecursiveFieldByFieldElementComparatorOnFields",
            "usingRecursiveFieldByFieldElementComparatorIgnoringFields"
    };
    // Извлечение/навигация (возвращают производный/под-элементный assert, сами не проверяют):
    private static final String[] NAVIGATION_METHODS = {
            "extracting", "filteredOn", "asInstanceOf", "asString", "asList",
            "first", "last", "element", "elements", "singleElement", "get", "value", "newAbstractIterableAssert",
            // ещё навигация/извлечение (возвращают производный assert, не проверяют):
            "map", "flatMap", "flatExtracting", "extractingResultOf",
            "filteredOnNull", "filteredOnAssertions", "size"
    };
    // Доступ к состоянию / Object-методы / внутренние фабрики ошибок + тривиальная
    // precondition isNotNull (часто авто-вызывается в цепочках — не «интересная» проверка):
    private static final String[] INTERNAL_METHODS = {
            "getActual", "actual", "info", "myself", "objects", "throwUnsupportedExceptionOnEquals",
            "hashCode", "equals", "toString", "failWithMessage", "failWithActualExpectedAndMessage", "isNotNull",
            "descriptionText", "getWritableAssertionInfo"
    };
    // ВАЖНО: настоящие проверки satisfies/returns/matches здесь НЕ перечислены — они логируются.
    private static final String[] NON_ASSERTION_METHODS = Stream.of(
            CONFIG_METHODS, NAVIGATION_METHODS, INTERNAL_METHODS).flatMap(Arrays::stream).toArray(String[]::new);

    // AssertJ-методы делегируют в super (isEqualTo → AbstractAssert.isEqualTo и т.п.) — оба
    // инструментируются. Считаем глубину вложенности: логируем только внешний (пользовательский)
    // вызов, внутренние делегаты пропускаем. Счётчик, а не флаг — на случай многоуровневой делегации.
    private static final ThreadLocal<Integer> DEPTH = ThreadLocal.withInitial(() -> 0);

    private AllureAssertJInstrumentation() {
    }

    /** Вход в ассерт-метод; возвращает глубину (1 — внешний вызов). Только для inline-advice. */
    public static int enter() {
        int depth = DEPTH.get() + 1;
        DEPTH.set(depth);
        return depth;
    }

    /** Выход из ассерт-метода (всегда парен {@link #enter()}). Только для inline-advice. */
    public static void exit() {
        int depth = DEPTH.get() - 1;
        if (depth <= 0) {
            DEPTH.remove(); // вернулись к нулю — не держим boxed 0 в пуле потоков surefire
        } else {
            DEPTH.set(depth);
        }
    }

    public static void install() {
        if (!INSTALLED.compareAndSet(false, true)) {
            return;
        }
        AllureInstrumentation.retransform(
                isSubTypeOf(AbstractAssert.class),
                (builder, type, cl, module, pd) -> builder.visit(Advice.to(AssertJAdvice.class)
                        .on(isPublic().and(not(isStatic())).and(not(namedOneOf(NON_ASSERTION_METHODS))))));
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
                StringBuilder sb = new StringBuilder("Проверка: значение ")
                        .append(AllureAdviceSupport.safe(actual))
                        .append(" — ").append(methodName);
                if (args != null) {
                    for (int i = 0; i < args.length; i++) {
                        // varargs приходят массивом — safe() печатает элементы (deepToString), не [Ljava…
                        sb.append(i == 0 ? " " : ", ").append(AllureAdviceSupport.safe(args[i]));
                    }
                }
                AllureAdviceSupport.step(sb.toString(), thrown);
            } catch (Throwable t) {
                AllureInstrumentationLogger.warn("AssertJ", t);
            }
        }
    }
}
