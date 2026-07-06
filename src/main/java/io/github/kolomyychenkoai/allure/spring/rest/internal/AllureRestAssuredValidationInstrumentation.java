package io.github.kolomyychenkoai.allure.spring.rest.internal;

import io.github.kolomyychenkoai.allure.spring.internal.AllureAdviceSupport;
import io.github.kolomyychenkoai.allure.spring.internal.AllureInstrumentation;
import io.github.kolomyychenkoai.allure.spring.internal.AllureInstrumentationLogger;
import net.bytebuddy.asm.Advice;

import java.util.concurrent.atomic.AtomicBoolean;

import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.not;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

/**
 * ByteBuddy-инструментирование ПРОВЕРОК RestAssured ({@code .then().statusCode(...).body(...)}):
 * каждая УСПЕШНАЯ проверка {@code ValidatableResponse} даёт в отчёте шаг «Проверка ответа: …» —
 * без кода в тестах. HTTP-шаг запроса/ответа пишет фильтр ({@code AllureRestAssuredFilter});
 * этот модуль добавляет к нему сами проверки, которых у нас раньше не было.
 * <p>
 * Почему байткодом: RestAssured валидирует своим внутренним путём (мимо {@code MatcherAssert.assertThat},
 * на который завязан наш Hamcrest-перехват), поэтому listener/фильтром эти проверки не поймать.
 * <p>
 * <b>Что цепляем.</b> Публичные проверочные методы {@code ValidatableResponseOptionsImpl}
 * (носитель всех перегрузок {@code statusCode/statusLine/body/content/header(s)/cookie(s)/
 * contentType/time}). ИСКЛЮЧЕНЫ log-варианты того же имени ({@code body()}, {@code body(boolean)},
 * {@code headers()}, {@code cookies()} — они логируют, а не проверяют): фильтр по
 * {@code not(takesArguments(0))} + {@code not(takesArguments(boolean.class))}. 0-арг вариант
 * покрыт живым тестом ({@code .then().log().body()}); boolean-вариант — защитно (не так частотен).
 * <p>
 * <b>Только УСПЕШНАЯ проверка.</b> RestAssured проверяет eager — упавшая проверка бросает
 * прямо из метода, {@code @Thrown != null} → шаг НЕ пишем (падение Allure покажет на уровне
 * теста).
 * <p>
 * <b>Дедуп делегации перегрузок (счётчик глубины НУЖЕН здесь).</b> Обычно перегрузки форвардят
 * НАРУЖУ в {@code ResponseSpecificationImpl} (не инструментируем) — там дублировать нечего. НО
 * перегрузки с {@code ResponseAwareMatcher} само-делегируют ВНУТРИ класса: {@code body(String,
 * ResponseAwareMatcher)} по байткоду зовёт {@code body(String, Matcher, Object[])} того же класса —
 * оба инструментированы, без гарда вышло бы 2 шага на один пользовательский {@code .body(...)}.
 * Поэтому считаем глубину (как в AssertJ/Spring-ассертах): шаг пишет только ВНЕШНИЙ вызов
 * (проверено тестом {@code RestAssuredReportIT} через {@code ResponseAwareMatcher} → ровно 1 шаг).
 * <p>
 * <b>Границы by-design:</b> ловится только API {@code .then()...} с проверками ПРЯМО на нём;
 * проверки, спрятанные в общий {@code ResponseSpecification} ({@code .then().spec(spec)}),
 * отдельными шагами не выходят — их гоняет внутренний {@code validate()}, а не публичные методы.
 * {@code content(...)} — deprecated-алиас {@code body(...)}, отражается меткой «тело».
 * <p>
 * ⚠️ <b>Завязано на внутренности RestAssured 5.5.x</b> ({@code io.restassured.internal.
 * ValidatableResponseOptionsImpl} — носитель всех перегрузок {@code .then()}). При апгрейде
 * RestAssured проверить (см. канарейку в {@code InstrumentationApiCanaryTest}): (1) класс всё ещё
 * impl проверок {@code .then()} и несёт {@code statusCode}/{@code body}; (2) log-варианты по-прежнему
 * 0-арг/{@code boolean}; (3) перечень имён-проверок не расширился новым методом.
 * Установка идемпотентна (CAS-гард {@code INSTALLED}) — один раз на JVM.
 */
public final class AllureRestAssuredValidationInstrumentation {

    private static final AtomicBoolean INSTALLED = new AtomicBoolean(false);

    /** Глубина вложенности инструментированных вызовов в потоке: внешний (пользовательский) — 1. */
    private static final ThreadLocal<Integer> DEPTH = ThreadLocal.withInitial(() -> 0);

    private AllureRestAssuredValidationInstrumentation() {
    }

    /** Вход в инструментированный метод; {@code true} — это ВНЕШНИЙ вызов. Только для inline-advice. */
    public static boolean enter() {
        int depth = DEPTH.get() + 1;
        DEPTH.set(depth);
        return depth == 1;
    }

    /** Выход (всегда парен {@link #enter()}). Только для inline-advice. */
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
                named("io.restassured.internal.ValidatableResponseOptionsImpl"),
                (builder, type, cl, module, pd) -> builder.visit(Advice.to(ValidationAdvice.class).on(
                        isPublic()
                                .and(named("statusCode").or(named("statusLine")).or(named("body"))
                                        .or(named("content")).or(named("header")).or(named("headers"))
                                        .or(named("cookie")).or(named("cookies")).or(named("contentType"))
                                        .or(named("time")))
                                // отсечь log-варианты того же имени: body()/headers()/cookies()/body(boolean)
                                .and(not(takesArguments(0)))
                                .and(not(takesArguments(boolean.class))))));
    }

    /** Логика шага проверки (вынесена из advice для level-A теста). Шаг — только для УСПЕШНОЙ проверки. */
    public static void onValidation(String method, Object[] args, Throwable thrown) {
        try {
            AllureAdviceSupport.step("Проверка ответа: " + describe(method, args), thrown);
        } catch (Throwable t) {
            AllureInstrumentationLogger.warn("RestAssuredValidation", t);
        }
    }

    /** Человекочитаемое имя проверки: русская метка метода + значения аргументов. */
    private static String describe(String method, Object[] args) {
        String label = switch (method) {
            case "statusCode" -> "статус";
            case "statusLine" -> "строка статуса";
            case "body", "content" -> "тело";
            case "header" -> "заголовок";
            case "headers" -> "заголовки";
            case "cookie" -> "cookie";
            case "cookies" -> "cookies";
            case "contentType" -> "тип содержимого";
            case "time" -> "время ответа";
            default -> method;
        };
        String values = argsString(args);
        return values.isEmpty() ? label : label + " " + values;
    }

    /** Аргументы через пробел, безопасно (без throw/с лимитом длины). */
    private static String argsString(Object[] args) {
        if (args == null || args.length == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Object a : args) {
            // пустой varargs (напр. body(path, matcher) без доп. пар) в имя не тащим;
            // а вот null-аргумент рендерим явно ("null") — для header(name, null)/equalTo(null)
            // это и есть проверяемое значение, терять его нельзя
            if (a instanceof Object[] arr && arr.length == 0) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(AllureAdviceSupport.safe(a));
        }
        return sb.toString();
    }

    public static class ValidationAdvice {
        @Advice.OnMethodEnter
        public static boolean onEnter() {
            return enter();
        }

        @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
        public static void onExit(@Advice.Enter boolean outermost,
                                  @Advice.Origin("#m") String method,
                                  @Advice.AllArguments Object[] args,
                                  @Advice.Thrown Throwable thrown) {
            exit();
            if (!outermost) {
                return; // внутренний делегат перегрузки — не дублируем
            }
            onValidation(method, args, thrown);
        }
    }
}
