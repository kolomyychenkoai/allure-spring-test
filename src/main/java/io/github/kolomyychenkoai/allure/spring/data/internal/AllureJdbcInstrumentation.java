package io.github.kolomyychenkoai.allure.spring.data.internal;

import io.github.kolomyychenkoai.allure.spring.internal.AllureAdviceSupport;
import io.github.kolomyychenkoai.allure.spring.internal.AllureInstrumentation;
import io.github.kolomyychenkoai.allure.spring.internal.AllureInstrumentationLogger;
import io.qameta.allure.Allure;
import io.qameta.allure.model.Status;
import io.qameta.allure.model.StepResult;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.bytecode.assign.Assigner;

import java.util.Collection;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.isStatic;
import static net.bytebuddy.matcher.ElementMatchers.namedOneOf;
import static net.bytebuddy.matcher.ElementMatchers.not;

/**
 * ByteBuddy-инструментирование прямого JDBC: вызовы {@code JdbcTemplate} и
 * {@code NamedParameterJdbcTemplate} (минуя репозитории Spring Data) дают шаг
 * «DB &lt;Тип&gt;.&lt;метод&gt;» с вложениями «SQL» (текст запроса) и «DB Result» (что вернулось).
 * Без кода в тестах. Механизм байткодный (а не AspectJ), т.к. шаблоны часто создаются руками
 * ({@code new JdbcTemplate(ds)}) и не являются Spring-бинами — Spring AOP их не ловит.
 * <p>
 * Шаг открывается ДО вызова, поэтому реальный SQL (модуль {@code AllureDataSourceListener},
 * datasource-proxy) вкладывается ВНУТРЬ — видно «вызов шаблона → его SQL», как репозиторий → SQL.
 * Работает и без datasource-proxy: тогда виден шаг с текстом запроса, но без вложенного реального SQL.
 * <p>
 * <b>Дедуп по глубине.</b> Методы делегируют друг другу (queryForObject → query → execute) и
 * {@code NamedParameterJdbcTemplate} внутри зовёт обычный {@code JdbcTemplate} — без защиты
 * получили бы вложенные дубли. Считаем глубину на потоке (ThreadLocal): шаг открывает и
 * закрывает только ВНЕШНИЙ вызов, внутренние делегаты пропускаем. У NamedParameter в шаге
 * виден ИМЕНОВАННЫЙ SQL (а {@code ?}-вариант делегата подавлен); реальный SQL всё равно
 * вложится во внешний шаг. Счётчик, а не флаг — на случай многоуровневой делегации.
 * <p>
 * Гейт — активный Allure тест-кейс (молчим на старте контекста). Любой сбой ловится и
 * логируется на WARNING, тест не затрагивается. Установка идемпотентна (CAS-гард) — один раз на JVM.
 * <p>
 * ⚠️ Дедуп держится на том, что {@code NamedParameterJdbcTemplate} делегирует ВНУТРЬ обычного
 * {@code JdbcTemplate} (проверено на Spring 6.2.x) — внешний Named-вызов открывает шаг, внутренний
 * Jdbc-делегат подавлён счётчиком глубины. Имена методов и оба класса закреплены канарейкой
 * {@code InstrumentationApiCanaryTest#jdbcMatchers}. By-design НЕ покрыты {@code JdbcClient}
 * (новый fluent-API Spring 6.1+), {@code SimpleJdbcInsert}/{@code SimpleJdbcCall}, jOOQ/MyBatis,
 * ручной {@code Connection}/{@code PreparedStatement} (см. README) — но их реальный SQL всё равно
 * виден SQL-шагами datasource-proxy, просто без шага-обёртки уровня вызова.
 */
public final class AllureJdbcInstrumentation {

    private static final AtomicBoolean INSTALLED = new AtomicBoolean(false);

    // Считаем глубину вложенности вызовов шаблонов на потоке — логируем только внешний.
    private static final ThreadLocal<Integer> DEPTH = ThreadLocal.withInitial(() -> 0);
    // Контекст внешнего (логируемого) вызова — один уровень, т.к. вложенные не логируются.
    private static final ThreadLocal<Ctx> CURRENT = new ThreadLocal<>();

    // Методы-операции с БД у JdbcTemplate / NamedParameterJdbcTemplate.
    private static final String[] METHODS = {
            "query", "queryForObject", "queryForList", "queryForMap", "queryForRowSet",
            "queryForStream", "update", "batchUpdate", "execute"
    };

    private record Ctx(String uuid, String sql) {
    }

    private AllureJdbcInstrumentation() {
    }

    public static void install() {
        if (!INSTALLED.compareAndSet(false, true)) {
            return;
        }
        AllureInstrumentation.retransform(
                namedOneOf("org.springframework.jdbc.core.JdbcTemplate",
                        "org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate"),
                (builder, type, cl, module, pd) -> builder.visit(Advice.to(JdbcAdvice.class)
                        .on(isPublic().and(not(isStatic())).and(namedOneOf(METHODS)))));
    }

    /**
     * Вход в метод шаблона. Возвращает uuid открытого шага (для внешнего вызова) или {@code null}
     * (внутренний делегат / нет активного кейса / сбой). Только для inline-advice.
     */
    public static String enter(String type, String method, Object[] args) {
        int depth = DEPTH.get() + 1;
        DEPTH.set(depth);
        if (depth != 1) {
            return null; // внутренний делегат — внешний вызов уже открыл шаг
        }
        try {
            if (!Allure.getLifecycle().getCurrentTestCase().isPresent()) {
                return null;
            }
            String uuid = UUID.randomUUID().toString();
            Allure.getLifecycle().startStep(uuid,
                    new StepResult().setName("DB " + simpleName(type) + "." + method).setStatus(Status.PASSED));
            CURRENT.set(new Ctx(uuid, firstSql(args)));
            return uuid;
        } catch (Throwable t) {
            AllureInstrumentationLogger.warn("Jdbc", t);
            return null;
        }
    }

    /** Выход из метода шаблона (всегда парен {@link #enter}). Только для inline-advice. */
    public static void exit(String uuid, Object result, Throwable thrown) {
        int depth = DEPTH.get() - 1;
        if (depth <= 0) {
            DEPTH.remove();
        } else {
            DEPTH.set(depth);
        }
        if (uuid == null) {
            return; // внутренний делегат / шаг не открывали
        }
        try {
            Ctx ctx = CURRENT.get();
            CURRENT.remove();
            String sql = ctx != null ? ctx.sql() : null;
            Allure.addAttachment("SQL", "text/plain", sql != null ? sql : "—");
            if (thrown == null) {
                Allure.addAttachment("DB Result", "text/plain", formatResult(result));
            }
            // при ошибке — BROKEN, текст исключения не дублируем (Allure покажет на уровне теста)
            Allure.getLifecycle().updateStep(uuid, s -> s.setStatus(thrown == null ? Status.PASSED : Status.BROKEN));
        } catch (Throwable t) {
            AllureInstrumentationLogger.warn("Jdbc", t);
        } finally {
            try {
                Allure.getLifecycle().stopStep(uuid);
            } catch (Throwable t) {
                AllureInstrumentationLogger.warn("Jdbc", t);
            }
        }
    }

    /** Первый осмысленный SQL из аргументов: строка sql или массив строк (batchUpdate). */
    private static String firstSql(Object[] args) {
        if (args == null) {
            return null;
        }
        for (Object arg : args) {
            if (arg instanceof String s) {
                return s;
            }
            if (arg instanceof String[] arr && arr.length > 0) {
                return String.join(";\n", arr);
            }
        }
        return null;
    }

    private static String formatResult(Object result) {
        if (result == null) {
            return "void";
        }
        if (result instanceof Collection<?> col) {
            return "Collection size: " + col.size();
        }
        if (result instanceof int[] arr) {
            return "batch rows: " + arr.length;
        }
        return AllureAdviceSupport.safe(result);
    }

    private static String simpleName(String fqn) {
        int dot = fqn.lastIndexOf('.');
        return dot >= 0 ? fqn.substring(dot + 1) : fqn;
    }

    public static class JdbcAdvice {
        @Advice.OnMethodEnter(suppress = Throwable.class)
        public static String onEnter(@Advice.Origin("#t") String type,
                                     @Advice.Origin("#m") String method,
                                     @Advice.AllArguments Object[] args) {
            return enter(type, method, args);
        }

        @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
        public static void onExit(@Advice.Enter String uuid,
                                  @Advice.Return(typing = Assigner.Typing.DYNAMIC) Object result,
                                  @Advice.Thrown Throwable thrown) {
            exit(uuid, result, thrown);
        }
    }
}
