package io.github.kolomyychenkoai.allure.spring.assertion.internal;

import io.github.kolomyychenkoai.allure.spring.internal.AllureAdviceSupport;
import io.github.kolomyychenkoai.allure.spring.internal.AllureInstrumentation;
import io.github.kolomyychenkoai.allure.spring.internal.AllureInstrumentationLogger;
import net.bytebuddy.asm.Advice;

import java.util.concurrent.atomic.AtomicBoolean;

import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

/**
 * ByteBuddy-инструментирование Spring-ассертов ({@code org.springframework.test.util.AssertionErrors}):
 * каждый assertEquals/assertNotEquals/assertTrue/assertFalse/assertNull/assertNotNull даёт
 * в Allure-отчёте шаг «Проверка: …» — БЕЗ кода в тестах. Шаг пишется ТОЛЬКО для УСПЕШНОЙ
 * проверки; упавшая проверка шага не создаёт — её падение Allure показывает из коробки на
 * уровне теста (исключение пробрасывается).
 * <p>
 * <b>Граф делегации Spring (важно для дедупа УСПЕШНОГО пути).</b> Внутри {@code AssertionErrors}
 * {@code assertNull}/{@code assertNotNull} → {@code assertTrue}: один пользовательский ассерт
 * проходит через несколько инструментированных методов. Чтобы НЕ задвоить шаг, считаем
 * глубину вложенности (как в AssertJ): шаг пишет только ВНЕШНИЙ (пользовательский) вызов.
 * На пути ПАДЕНИЯ ({@code fail}, и т.п.) шага нет в любом случае, поэтому {@code fail} не
 * инструментируем.
 * <p>
 * Advice инлайнится в байткод AssertionErrors, поэтому ссылается только на хелперы
 * {@code internal} + j.u.l-логгер. Ставится один раз на JVM — см. {@link AllureAssertionsListener}.
 */
public final class AllureSpringAssertionsInstrumentation {

    private static final AtomicBoolean INSTALLED = new AtomicBoolean(false);

    // Глубина вложенности инструментированных вызовов в текущем потоке. Внешний
    // (пользовательский) вызов — глубина 1; внутренние делегаты (assertTrue, fail) — глубже.
    private static final ThreadLocal<Integer> DEPTH = ThreadLocal.withInitial(() -> 0);

    private AllureSpringAssertionsInstrumentation() {
    }

    /** Вход в инструментированный метод; {@code true} — это ВНЕШНИЙ (пользовательский) вызов. Только для inline-advice. */
    public static boolean enter() {
        int depth = DEPTH.get() + 1;
        DEPTH.set(depth);
        return depth == 1;
    }

    /** Выход из инструментированного метода (всегда парен {@link #enter()}). Только для inline-advice. */
    public static void exit() {
        int depth = DEPTH.get() - 1;
        if (depth <= 0) {
            DEPTH.remove(); // вернулись к нулю — не держим boxed 0 в пуле потоков surefire
        } else {
            DEPTH.set(depth);
        }
    }

    /**
     * Сообщения ВНУТРЕННИХ инвариантов Spring — их проверяет сам фреймворк, а не тест, и в
     * отчёте ручного тестировщика им не место.
     * <p>
     * Почему список сообщений, а не «кто вызвал». Отфильтровать по вызывающему классу нельзя:
     * полезный шаг «Проверка: Status — ожидалось 200 OK» приходит ИЗ ТОГО ЖЕ пакета Spring
     * ({@code org.springframework.test.web.support.AbstractStatusAssertions}). Разница не в
     * вызывающем, а в том, говорит ли сообщение что-то тестировщику.
     * <p>
     * ⚠️ Список по определению неполон: Spring может добавить свои инварианты. Ловушкой служит
     * эталон инвентаря — новый мусорный ВИД шага в нём виден сразу (`src/test/inventory`).
     */
    private static final String[] SPRING_INTERNAL_CHECKS = {
            // WebTestClient/RestTestClient, Spring 7: AbstractStatusAssertions.assertStatusAndReturn
            "exchangeResult unexpectedly null",
    };

    /** Проверка самого Spring, а не теста → шага не будет. Только для inline-advice. */
    public static boolean internalSpringCheck(String message) {
        if (message == null) {
            return false;
        }
        for (String known : SPRING_INTERNAL_CHECKS) {
            if (message.equals(known)) {
                return true;
            }
        }
        return false;
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
                                .on(named("assertNotNull").and(takesArguments(2)))));
    }

    public static class AssertEqualsAdvice {
        @Advice.OnMethodEnter
        public static boolean onEnter() {
            return enter();
        }

        @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
        public static void onExit(@Advice.Enter boolean outermost,
                                  @Advice.Argument(0) String message,
                                  @Advice.Argument(1) Object expected,
                                  @Advice.Argument(2) Object actual,
                                  @Advice.Thrown Throwable thrown) {
            try {
                exit();
                if (!outermost) {
                    return;
                }
                AllureAdviceSupport.step("Проверка: " + message + (thrown == null
                        ? " — ожидалось " + AllureAdviceSupport.safe(expected) + " = " + AllureAdviceSupport.safe(actual)
                        : " — ожидалось " + AllureAdviceSupport.safe(expected) + ", получено " + AllureAdviceSupport.safe(actual)), thrown);
            } catch (Throwable t) {
                AllureInstrumentationLogger.warn("SpringAssertEquals", t);
            }
        }
    }

    public static class AssertNotEqualsAdvice {
        @Advice.OnMethodEnter
        public static boolean onEnter() {
            return enter();
        }

        @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
        public static void onExit(@Advice.Enter boolean outermost,
                                  @Advice.Argument(0) String message,
                                  @Advice.Argument(1) Object unexpected,
                                  @Advice.Argument(2) Object actual,
                                  @Advice.Thrown Throwable thrown) {
            try {
                exit();
                if (!outermost) {
                    return;
                }
                AllureAdviceSupport.step("Проверка: " + message + (thrown == null
                        ? " — " + AllureAdviceSupport.safe(unexpected) + " ≠ " + AllureAdviceSupport.safe(actual)
                        : " — значения равны: " + AllureAdviceSupport.safe(actual)), thrown);
            } catch (Throwable t) {
                AllureInstrumentationLogger.warn("SpringAssertNotEquals", t);
            }
        }
    }

    public static class AssertTrueAdvice {
        @Advice.OnMethodEnter
        public static boolean onEnter() {
            return enter();
        }

        @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
        public static void onExit(@Advice.Enter boolean outermost,
                                  @Advice.Argument(0) String message,
                                  @Advice.Thrown Throwable thrown) {
            try {
                exit();
                if (!outermost) {
                    return; // внутренний делегат (из assertNull/assertNotNull) — не дублируем
                }
                AllureAdviceSupport.step("Проверка: " + message + (thrown == null ? " — верно" : " — неверно"), thrown);
            } catch (Throwable t) {
                AllureInstrumentationLogger.warn("SpringAssertTrue", t);
            }
        }
    }

    public static class AssertFalseAdvice {
        @Advice.OnMethodEnter
        public static boolean onEnter() {
            return enter();
        }

        @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
        public static void onExit(@Advice.Enter boolean outermost,
                                  @Advice.Argument(0) String message,
                                  @Advice.Thrown Throwable thrown) {
            try {
                exit();
                if (!outermost) {
                    return;
                }
                AllureAdviceSupport.step("Проверка: " + message + (thrown == null ? " — неверно" : " — верно"), thrown);
            } catch (Throwable t) {
                AllureInstrumentationLogger.warn("SpringAssertFalse", t);
            }
        }
    }

    public static class AssertNullAdvice {
        @Advice.OnMethodEnter
        public static boolean onEnter() {
            return enter();
        }

        @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
        // Аргумент значения НАМЕРЕННО не связан: то, что не передано в advice, невозможно
        // случайно отрендерить (см. комментарий ниже про побочный эффект toString()).
        public static void onExit(@Advice.Enter boolean outermost,
                                  @Advice.Argument(0) String message,
                                  @Advice.Thrown Throwable thrown) {
            try {
                exit();
                if (!outermost) {
                    return;
                }
                // Значение не рендерим по той же причине, что и в AssertNotNullAdvice — побочный
                // эффект чужого toString(). Путь ПРОВАЛА важен не меньше успешного: шага там не
                // будет (step() выходит при thrown != null), но имя-строка вычисляется РАНЬШЕ
                // вызова — то есть toString() всё равно бы сработал.
                AllureAdviceSupport.step("Проверка: " + message
                        + (thrown == null ? " — значение null" : " — значение не null"), thrown);
            } catch (Throwable t) {
                AllureInstrumentationLogger.warn("SpringAssertNull", t);
            }
        }
    }

    public static class AssertNotNullAdvice {
        @Advice.OnMethodEnter
        public static boolean onEnter() {
            return enter();
        }

        @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
        // Аргумент значения НАМЕРЕННО не связан: то, что не передано в advice, невозможно
        // случайно отрендерить (см. комментарий ниже про побочный эффект toString()).
        public static void onExit(@Advice.Enter boolean outermost,
                                  @Advice.Argument(0) String message,
                                  @Advice.Thrown Throwable thrown) {
            try {
                exit();
                if (!outermost || internalSpringCheck(message)) {
                    return; // инвариант самого Spring — тестировщику он ничего не говорит
                }
                // ⚠️ Значение НЕ рендерим: проверяется только его наличие, а toString() чужого
                // объекта — ПОБОЧНЫЙ ЭФФЕКТ, а не чтение. Живой пример: WebTestClient зовёт
                // assertNotNull("exchangeResult unexpectedly null", exchangeResult), а
                // ExchangeResult.toString() печатает тело ответа — одноразовое с Spring 7.
                // Отрендеришь — выпьешь тело у теста потребителя, и он получит
                // «The client response body can only be consumed once» на своём же expectBody().
                // Стережёт unit/AllureSpringAssertionsTest#nullAssertionsDoNotTouchValueToString.
                AllureAdviceSupport.step("Проверка: " + message
                        + (thrown == null ? " — значение не null" : " — значение null"), thrown);
            } catch (Throwable t) {
                AllureInstrumentationLogger.warn("SpringAssertNotNull", t);
            }
        }
    }
}
