package io.github.kolomyychenkoai.allure.spring.assertion.internal;

import io.github.kolomyychenkoai.allure.spring.internal.AllureAdviceSupport;
import io.github.kolomyychenkoai.allure.spring.internal.AllureInstrumentation;
import io.github.kolomyychenkoai.allure.spring.internal.AllureInstrumentationLogger;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.bytecode.assign.Assigner;

import java.util.concurrent.atomic.AtomicBoolean;

import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.isStatic;
import static net.bytebuddy.matcher.ElementMatchers.named;

/**
 * ByteBuddy-инструментирование JUnit Jupiter ассертов ({@code org.junit.jupiter.api.Assertions}):
 * каждый УСПЕШНЫЙ {@code assertEquals}/{@code assertTrue}/{@code assertThrows}/… даёт в отчёте шаг
 * «Проверка: …» — БЕЗ кода в тестах. Шаг только для УСПЕШНОЙ проверки; упавшая шага не создаёт
 * (падение Allure показывает на уровне теста).
 * <p>
 * <b>Перехват на ФАСАДЕ {@code Assertions} — 1:1, БЕЗ депт-счётчика.</b> Каждый публичный метод
 * {@code Assertions} — тонкий форвардер НАРУЖУ в package-private {@code Assert*} (проверено
 * байткодом: {@code invokestatic .../Assertions.} внутри класса нет). Само-делегации на фасаде нет
 * → один пользовательский вызов = одна перехваченная точка → дедуп/ThreadLocal не нужен (в отличие
 * от AssertJ/Spring, где перегрузки само-делегируют). Advice — только {@code OnMethodExit}.
 * <p>
 * <b>Сообщение — по СТАТИЧЕСКОЙ сигнатуре, не по runtime-значениям.</b> У JUnit сообщение — ПОСЛЕДНИЙ
 * опциональный параметр ({@code String} или {@code Supplier<String>}). Определяем по дескриптору
 * ({@code @Advice.Origin("#d")}): доказано, что НИ ОДИН параметр-ЗНАЧЕНИЕ среди включённых методов не
 * типизирован статически как {@code String}/{@code Supplier} (значения — {@code Object}/примитив/
 * массив/{@code List}/{@code Stream}/{@code Class}/…; {@code assertEquals(String,String)} не
 * существует — строки идут через {@code Object}). Значит «последний параметр {@code String}/
 * {@code Supplier} ⇒ это сообщение» однозначно. {@code Supplier}-сообщение НЕ резолвим ({@code .get()}
 * JUnit зовёт только на падении; мы логируем на успехе → не дёргаем чужой код с побочками).
 * <p>
 * <b>Исключены:</b> {@code fail} (всегда бросает — успеха нет); {@code assertAll} (агрегатор, его
 * {@code Executable} сами зовут {@code Assertions.*} → дают свои шаги; плюс он message-FIRST).
 * <b>Границы:</b> {@code assertTimeoutPreemptively} — внешний шаг честен (на тест-потоке), но
 * вложенные проверки внутри лямбды идут в рабочем потоке без активного кейса → в отчёт не попадают.
 * <p>
 * Строковый матчер {@code named("org.junit.jupiter.api.Assertions")} — без импорта Jupiter-типа в
 * main (нет новой compile-зависимости, как Hamcrest/Spring). Установка идемпотентна (CAS-гард
 * {@code INSTALLED}). ⚠️ Апгрейд JUnit: канарейка {@code InstrumentationApiCanaryTest} стережёт
 * наличие имён и допущение «фасад не само-делегирует».
 */
public final class AllureJUnitJupiterAssertionsInstrumentation {

    private static final AtomicBoolean INSTALLED = new AtomicBoolean(false);

    private AllureJUnitJupiterAssertionsInstrumentation() {
    }

    public static void install() {
        if (!INSTALLED.compareAndSet(false, true)) {
            return;
        }
        AllureInstrumentation.retransform(
                named("org.junit.jupiter.api.Assertions"),
                (builder, type, cl, module, pd) -> builder.visit(Advice.to(AssertionAdvice.class).on(
                        isStatic().and(isPublic()).and(
                                named("assertEquals").or(named("assertNotEquals"))
                                        .or(named("assertTrue")).or(named("assertFalse"))
                                        .or(named("assertNull")).or(named("assertNotNull"))
                                        .or(named("assertSame")).or(named("assertNotSame"))
                                        .or(named("assertArrayEquals")).or(named("assertIterableEquals"))
                                        .or(named("assertLinesMatch")).or(named("assertInstanceOf"))
                                        .or(named("assertThrows")).or(named("assertThrowsExactly"))
                                        .or(named("assertDoesNotThrow"))
                                        .or(named("assertTimeout")).or(named("assertTimeoutPreemptively"))))));
    }

    /** Логика шага (вынесена из advice для level-A теста). Шаг — только для УСПЕШНОЙ проверки. */
    public static void onAssertion(String method, String descriptor, Object[] args, Object returned, Throwable thrown) {
        try {
            AllureAdviceSupport.step(describe(method, descriptor, args, returned), thrown);
        } catch (Throwable t) {
            AllureInstrumentationLogger.warn("JUnitAssertion", t);
        }
    }

    /** Имя шага: «Проверка: [<сообщение> — ]<вердикт по семейству>». Сообщение — по дескриптору. */
    private static String describe(String method, String descriptor, Object[] args, Object returned) {
        String message = messageArg(descriptor, args);      // текст String-сообщения или null
        Object[] vals = valueArgs(descriptor, args);         // аргументы-значения (без хвоста-сообщения)

        // true/false: словарь Spring «верно/неверно»; без сообщения — «условие верно/неверно»
        if (method.equals("assertTrue")) {
            return message != null ? "Проверка: " + message + " — верно" : "Проверка: условие верно";
        }
        if (method.equals("assertFalse")) {
            return message != null ? "Проверка: " + message + " — неверно" : "Проверка: условие неверно";
        }

        String core = switch (method) {
            case "assertEquals" -> "ожидалось " + v(vals, 0) + " = " + v(vals, 1);
            case "assertNotEquals" -> v(vals, 0) + " ≠ " + v(vals, 1);
            case "assertSame" -> "тот же объект: " + v(vals, 1);
            case "assertNotSame" -> "разные объекты: " + v(vals, 0) + " ≠ " + v(vals, 1);
            case "assertNull" -> "значение null";
            case "assertNotNull" -> "значение " + v(vals, 0) + " не null";
            case "assertArrayEquals" -> "массивы равны";
            case "assertIterableEquals" -> "коллекции равны";
            case "assertLinesMatch" -> "строки совпали";
            case "assertInstanceOf" -> "значение " + v(vals, 1) + " — экземпляр " + typeName(vals.length > 0 ? vals[0] : null);
            case "assertThrows", "assertThrowsExactly" -> "брошено " + thrownType(returned, vals);
            case "assertDoesNotThrow" -> "без исключения";
            // длительность (vals[0]) в имя НЕ тащим — Duration.toString() даёт ISO-жаргон «PT5S»
            case "assertTimeout", "assertTimeoutPreemptively" -> "уложились в таймаут";
            default -> method;
        };
        return message != null ? "Проверка: " + message + " — " + core : "Проверка: " + core;
    }

    /** Текст String-сообщения (последний параметр типа String), либо null (нет/Supplier — не резолвим). */
    private static String messageArg(String descriptor, Object[] args) {
        if (args == null || args.length == 0) {
            return null;
        }
        String params = params(descriptor);
        if (params.endsWith("Ljava/lang/String;")) {
            Object last = args[args.length - 1];
            return last == null ? null : AllureAdviceSupport.safe(last);
        }
        return null; // нет сообщения или Supplier<String> (не резолвим)
    }

    /** Аргументы-значения: все, кроме хвостового сообщения (String или Supplier). */
    private static Object[] valueArgs(String descriptor, Object[] args) {
        if (args == null || args.length == 0) {
            return new Object[0];
        }
        String params = params(descriptor);
        boolean hasTailMessage = params.endsWith("Ljava/lang/String;")
                || params.endsWith("Ljava/util/function/Supplier;");
        if (!hasTailMessage) {
            return args;
        }
        Object[] out = new Object[args.length - 1];
        System.arraycopy(args, 0, out, 0, out.length);
        return out;
    }

    /** Список параметров дескриптора {@code (...)R} — то, что между скобками. */
    private static String params(String descriptor) {
        int close = descriptor.indexOf(')');
        return close > 0 ? descriptor.substring(1, close) : "";
    }

    private static String v(Object[] vals, int i) {
        return i < vals.length ? AllureAdviceSupport.safe(vals[i]) : "?";
    }

    /** Простое имя типа для assertInstanceOf (ожидаемый {@code Class}). */
    private static String typeName(Object expectedClass) {
        return expectedClass instanceof Class<?> c ? c.getSimpleName() : AllureAdviceSupport.safe(expectedClass);
    }

    /** Тип пойманного исключения: из возврата assertThrows (реальный), иначе — ожидаемый Class. */
    private static String thrownType(Object returned, Object[] vals) {
        if (returned instanceof Throwable t) {
            return t.getClass().getSimpleName();
        }
        return vals.length > 0 ? typeName(vals[0]) : "исключение";
    }

    public static class AssertionAdvice {
        @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
        public static void onExit(@Advice.Origin("#m") String method,
                                  @Advice.Origin("#d") String descriptor,
                                  @Advice.AllArguments Object[] args,
                                  @Advice.Return(typing = Assigner.Typing.DYNAMIC) Object returned,
                                  @Advice.Thrown Throwable thrown) {
            onAssertion(method, descriptor, args, returned, thrown);
        }
    }
}
