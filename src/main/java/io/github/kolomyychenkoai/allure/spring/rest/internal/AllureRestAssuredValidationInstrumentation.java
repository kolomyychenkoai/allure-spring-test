package io.github.kolomyychenkoai.allure.spring.rest.internal;

import io.github.kolomyychenkoai.allure.spring.internal.AllureAdviceSupport;
import io.github.kolomyychenkoai.allure.spring.internal.AllureInstrumentation;
import io.github.kolomyychenkoai.allure.spring.internal.AllureInstrumentationLogger;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.matcher.ElementMatcher;

import java.util.concurrent.atomic.AtomicBoolean;

import static net.bytebuddy.matcher.ElementMatchers.hasParameters;
import static net.bytebuddy.matcher.ElementMatchers.hasType;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.not;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;
import static net.bytebuddy.matcher.ElementMatchers.whereAny;

/**
 * ByteBuddy-инструментирование ПРОВЕРОК RestAssured ({@code .then().statusCode(...).body(...)}):
 * каждая УСПЕШНАЯ проверка {@code ValidatableResponse} даёт в отчёте шаг «Проверка ответа: …» —
 * без кода в тестах. HTTP-шаг запроса/ответа пишет фильтр ({@code AllureRestAssuredFilter});
 * этот модуль добавляет к нему сами проверки.
 * <p>
 * Почему байткодом: RestAssured валидирует своим внутренним путём (мимо {@code MatcherAssert.assertThat},
 * на который завязан наш Hamcrest-перехват), поэтому listener/фильтром эти проверки не поймать.
 * <p>
 * <b>Что цепляем.</b> Публичные проверочные методы {@code ValidatableResponseOptionsImpl}
 * (носитель всех перегрузок {@code statusCode/statusLine/body/content/header(s)/cookie(s)/
 * contentType/time}). ИСКЛЮЧЕНЫ: (1) log-варианты того же имени ({@code body()}, {@code body(boolean)},
 * {@code headers()}, {@code cookies()} — логируют, а не проверяют) по {@code not(takesArguments(0))} +
 * {@code not(takesArguments(boolean.class))}; (2) перегрузки с {@code ResponseAwareMatcher} — см. ниже.
 * 0-арг вариант покрыт живым тестом ({@code .then().log().body()}); boolean-вариант — защитно.
 * <p>
 * <b>Только УСПЕШНАЯ проверка.</b> RestAssured проверяет eager — упавшая проверка бросает
 * прямо из метода, {@code @Thrown != null} → шаг НЕ пишем (падение Allure покажет на уровне
 * теста).
 * <p>
 * <b>Дедуп ВНУТРИКЛАССОВОЙ само-делегации.</b> Некоторые перегрузки зовут другую перегрузку ТОГО ЖЕ
 * класса (обе инструментированы) — без дедупа вышло бы 2 шага на один пользовательский вызов. Источники:
 * <ul>
 *   <li>{@code time(Matcher)} → {@code time(Matcher, TimeUnit)}: «чистый» — ВНЕШНИЙ ({@code time(matcher)}
 *       без единицы). Гасим внутренний счётчиком глубины: шаг пишет только ВНЕШНИЙ вызов (глубина 1).</li>
 *   <li>{@code body/content(…, ResponseAwareMatcher)} → {@code body(…, Matcher, Object[])}: «чистый» —
 *       наоборот, ВНУТРЕННИЙ (с УЖЕ РАЗРЕШЁННЫМ матчером; у внешней обёртки в имени был бы мусорный
 *       {@code toString} лямбды). Поэтому {@code ResponseAwareMatcher}-перегрузки вовсе НЕ инструментируем
 *       (матчер {@code not(hasParameters(whereAny(hasType(ResponseAwareMatcher))))}) — тогда внутренний
 *       plain-вызов становится внешним (глубина 1) и пишет читаемый шаг.</li>
 * </ul>
 * Два семейства требуют ПРОТИВОПОЛОЖНОГО выбора (внешний у time / внутренний у RAM), поэтому нужны ОБА
 * механизма: счётчик глубины + исключение RAM из матчера.
 * <p>
 * <b>Границы by-design:</b> (1) проверки в общем {@code ResponseSpecification} ({@code .then().spec(spec)})
 * отдельными шагами не выходят — их гоняет внутренний {@code validate()}; (2) {@code header(name,
 * ResponseAwareMatcher)} форвардит наружу в {@code ResponseSpecificationImpl} (не в plain-форму), поэтому,
 * как и все RAM-обёртки (не инструментируем), отдельным шагом не выходит — HTTP-шаг при этом на месте.
 * {@code content(...)} — deprecated-алиас {@code body(...)}, отражается меткой «тело».
 * <p>
 * ⚠️ <b>Завязано на внутренности RestAssured 6.0.x</b> ({@code io.restassured.internal.
 * ValidatableResponseOptionsImpl} — носитель всех перегрузок {@code .then()}). При апгрейде проверить
 * (см. канарейку в {@code InstrumentationApiCanaryTest}): (1) класс всё ещё impl проверок {@code .then()}
 * и несёт {@code statusCode}/{@code body}; (2) log-варианты по-прежнему 0-арг/{@code boolean}; (3) перечень
 * имён-проверок не расширился; (4) не появилось НОВОЙ внутриклассовой само-делегации помимо {@code time}
 * и {@code ResponseAwareMatcher} (иначе она задвоит шаг — счётчик глубины ловит только вложенные вызовы).
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
                (builder, type, cl, module, pd) ->
                        builder.visit(Advice.to(ValidationAdvice.class).on(validationMethods())));
    }

    /** Публичные проверочные методы .then() минус log-варианты (0-арг/boolean) и ResponseAwareMatcher-обёртки. */
    private static ElementMatcher<MethodDescription> validationMethods() {
        return isPublic()
                .and(named("statusCode").or(named("statusLine")).or(named("body"))
                        .or(named("content")).or(named("header")).or(named("headers"))
                        .or(named("cookie")).or(named("cookies")).or(named("contentType"))
                        .or(named("time")))
                // log-варианты того же имени: body()/headers()/cookies()/body(boolean)
                .and(not(takesArguments(0)))
                .and(not(takesArguments(boolean.class)))
                // ResponseAwareMatcher-обёртки: их значение пишет внутренний plain-вызов (см. class-javadoc)
                .and(not(hasParameters(whereAny(hasType(
                        named("io.restassured.matcher.ResponseAwareMatcher"))))));
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
                return; // внутренний делегат само-делегирующей перегрузки (напр. time(Matcher,TimeUnit)) — не дублируем
            }
            onValidation(method, args, thrown);
        }
    }
}
