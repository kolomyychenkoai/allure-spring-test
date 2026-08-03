package io.github.kolomyychenkoai.allure.spring.data.internal;

import io.github.kolomyychenkoai.allure.spring.internal.AllureInstrumentationLogger;
import io.qameta.allure.Allure;
import net.ttddyy.dsproxy.ExecutionInfo;
import net.ttddyy.dsproxy.QueryInfo;
import net.ttddyy.dsproxy.listener.QueryExecutionListener;
import net.ttddyy.dsproxy.proxy.ParameterSetOperation;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Логирует РЕАЛЬНЫЙ SQL (через обёртку DataSource, datasource-proxy): каждый
 * выполненный запрос даёт в отчёте шаг «SQL &lt;OP&gt;» с вложением «SQL Query» —
 * текст запроса с ПОДСТАВЛЕННЫМИ значениями связанных параметров (а не голые
 * {@code ?}), плюс время и успех. Дополняет аспект репозиториев («DB …»), который
 * показывает вызов метода и сущности.
 * <p>
 * Почему подставляем значения: голый {@code insert … values (?)} ручному приёмщику
 * непонятен — не видно, ЧТО записали. Связанные параметры PreparedStatement (их даёт
 * datasource-proxy) подставляются в текст: {@code values ('gadget')}. Строки — в
 * кавычках с экранированием, числа/булевы — как есть, {@code NULL} — как {@code NULL}.
 * Если значения нет — {@code ?} остаётся (безопасный фолбэк, отчёт не врёт).
 * <p>
 * Гейтинг — по активному Allure тест-кейсу: DDL при старте контекста в отчёт
 * не попадает. Всё в try/catch — инструментирование не роняет тест.
 * <p>
 * Порядок: SQL-шаг идёт ПЕРЕД соседним «DB Repo.method» (SQL выполняется внутри
 * вызова, а аспект эмитит свой шаг уже после) — это by-design.
 * <p>
 * Потокобезопасен: собственного состояния у листенера нет ({@code TABLE_PATTERNS}
 * неизменяема, рендер — на локальных переменных).
 * <p>
 * ⚠️ Версионные допущения о internal API datasource-proxy (форма {@code QueryInfo.getParametersList()}
 * и {@code ParameterSetOperation.getArgs()} = {@code [index, value]}, предикаты
 * {@code isSetNull/isRegisterOutParameterOperation}) стережёт
 * {@code InstrumentationApiCanaryTest#dataSourceProxyApi} — при апгрейде библиотеки чинить там.
 */
public class AllureDataSourceListener implements QueryExecutionListener {

    /**
     * Потолок шагов на один пакет. Батч Hibernate может содержать тысячи запросов — без
     * ограничителя отчёт у потребителя раздувался бы до нечитаемого. Остаток назван в тексте
     * вложения, а не отброшен молча.
     */
    private static final int MAX_BATCH_STEPS = 50;

    /** Предкомпилированные шаблоны имени таблицы по операции (компиляция — один раз, не на запрос). */
    private static final Map<String, Pattern> TABLE_PATTERNS = Map.of(
            "INSERT", Pattern.compile("(?i)insert\\s+into\\s+([\\w.\"`]+)"),
            "UPDATE", Pattern.compile("(?i)update\\s+([\\w.\"`]+)"),
            "DELETE", Pattern.compile("(?i)delete\\s+from\\s+([\\w.\"`]+)"),
            "MERGE", Pattern.compile("(?i)merge\\s+into\\s+([\\w.\"`]+)"),
            "SELECT", Pattern.compile("(?i)\\bfrom\\s+([\\w.\"`]+)"));

    @Override
    public void beforeQuery(ExecutionInfo execInfo, List<QueryInfo> queryInfoList) {
        // ничего — логируем после выполнения, когда известны время и успех
    }

    @Override
    public void afterQuery(ExecutionInfo execInfo, List<QueryInfo> queryInfoList) {
        try {
            if (queryInfoList == null || queryInfoList.isEmpty()
                    || !Allure.getLifecycle().getCurrentTestCase().isPresent()) {
                return;
            }
            // Шаг НА КАЖДЫЙ запрос пакета: batchUpdate(String...) и multi-statement execute отдают
            // несколько QueryInfo, и раньше все, кроме первого, молча исчезали из отчёта.
            // Шаг на запрос (а не один общий) — потому что имя «SQL <OP> <таблица>» и есть
            // единица, по которой человек читает отчёт, а инвентарь стережёт виды.
            int total = queryInfoList.size();
            int shown = Math.min(total, MAX_BATCH_STEPS);
            for (int i = 0; i < shown; i++) {
                QueryInfo query = queryInfoList.get(i);
                String body = renderQuery(query, execInfo, i, total, shown < total);
                Allure.step(stepName(query.getQuery()), step -> {
                    Allure.addAttachment("SQL Query", "text/plain", body);
                });
            }
        } catch (Throwable t) {
            AllureInstrumentationLogger.warn("DbSqlListener", t); // не роняем тест, сбой видно на WARNING
        }
    }

    /**
     * Текст запроса с подставленными значениями + место в пакете + строка «успех · время».
     * <p>
     * Про время честная оговорка: {@code getElapsedTime()} измерен на ВЕСЬ пакет, а не на этот
     * запрос — иначе отчёт врал бы числом (п. «достоверность значений» стандарта приёмки).
     */
    private static String renderQuery(QueryInfo query, ExecutionInfo exec, int index, int total, boolean capped) {
        List<List<ParameterSetOperation>> rows = query.getParametersList();
        StringBuilder sb = new StringBuilder(inlineParams(query.getQuery(), firstRow(rows)));
        if (rows != null && rows.size() > 1) {
            sb.append("\n(показан первый ряд из ").append(rows.size()).append(" — пакетная вставка)");
        }
        if (total > 1) {
            sb.append("\nзапрос ").append(index + 1).append(" из ").append(total).append(" в пакете");
            if (capped) {
                sb.append("\n(в отчёт попали первые ").append(MAX_BATCH_STEPS).append(" из ").append(total)
                        .append(" — остальные не показаны, чтобы не раздувать отчёт)");
            }
        }
        sb.append("\n\n");
        sb.append(exec != null && exec.isSuccess() ? "✓ успешно" : "✗ ошибка");
        if (exec != null) {
            sb.append(" · ").append(exec.getElapsedTime()).append(" мс")
                    .append(total > 1 ? " (на весь пакет)" : "");
        }
        return sb.toString();
    }

    /** Первый (не-батчевый) набор связанных параметров запроса; пусто, если их нет. */
    private static List<ParameterSetOperation> firstRow(List<List<ParameterSetOperation>> rows) {
        return (rows == null || rows.isEmpty()) ? List.of() : rows.get(0);
    }

    /**
     * Подставляет значения связанных параметров вместо позиционных {@code ?}. N-й {@code ?}
     * соответствует параметру с индексом N (PreparedStatement, 1-based). Если значения нет —
     * {@code ?} остаётся (безопасный фолбэк). Именованные/нестандартные параметры пропускаются.
     * <p>
     * ⚠️ Замена позиционная и наивная: считает КАЖДЫЙ символ {@code ?} за плейсхолдер. Опирается
     * на то, что SQL сюда приходит от ORM/JdbcTemplate, где {@code ?} — только плейсхолдеры (без
     * литеральных {@code ?} в строках). Для рукописного native-SQL с literal-{@code ?} (напр.
     * Postgres jsonb-операторы {@code ?}/{@code ?|}) нумерация может съехать — приемлемо: это
     * читаемая доп-подсказка, а «сырьё» кода (шаблон с {@code ?}) видно в соседнем вложении
     * «SQL (шаблон)» JdbcTemplate-пути.
     */
    private static String inlineParams(String sql, List<ParameterSetOperation> params) {
        if (sql == null || sql.indexOf('?') < 0 || params.isEmpty()) {
            return sql == null ? "" : sql;
        }
        Map<Integer, String> byIndex = new HashMap<>();
        for (ParameterSetOperation op : params) {
            // registerOutParameter(index, sqlType) у CallableStatement: второй аргумент — КОД типа,
            // не значение (как и setNull). Плейсхолдер out-параметра оставляем ?, кода не подставляем.
            if (ParameterSetOperation.isRegisterOutParameterOperation(op)) {
                continue;
            }
            Object[] args = op.getArgs(); // setXxx(index, value): [0]=индекс, [1]=значение
            if (args != null && args.length >= 2 && args[0] instanceof Integer idx) {
                byIndex.put(idx, renderParam(op, args[1]));
            }
        }
        if (byIndex.isEmpty()) {
            return sql;
        }
        StringBuilder out = new StringBuilder(sql.length() + 16);
        int position = 1;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (c == '?') {
                out.append(byIndex.getOrDefault(position++, "?"));
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    /**
     * Значение параметра для подстановки. Особый случай — {@code setNull(index, sqlType)}: у него
     * второй аргумент это КОД типа java.sql.Types, а не значение; наивный рендер вывел бы число —
     * поэтому опознаём его каноническим предикатом datasource-proxy и отдаём {@code NULL}.
     */
    private static String renderParam(ParameterSetOperation op, Object rawValue) {
        if (ParameterSetOperation.isSetNullParameterOperation(op)) {
            return "NULL";
        }
        return formatValue(rawValue);
    }

    /** Значение для подстановки: число/булево — как есть, null — NULL, остальное — в кавычках. */
    private static String formatValue(Object value) {
        if (value == null) {
            return "NULL";
        }
        if (value instanceof Number || value instanceof Boolean) {
            return value.toString();
        }
        if (value instanceof byte[]) {
            return "'<binary>'"; // не вываливаем бинарь в отчёт
        }
        return "'" + value.toString().replace("'", "''") + "'"; // экранируем одинарную кавычку
    }

    /** Имя шага: «SQL <OP> <таблица>» — чтобы разные запросы различались в дереве. */
    private static String stepName(String sql) {
        String op = firstKeyword(sql);
        String table = tableName(sql, op);
        return table.isEmpty() ? "SQL " + op : "SQL " + op + " " + table;
    }

    private static String firstKeyword(String sql) {
        if (sql == null || sql.isBlank()) {
            return "query";
        }
        // по любому пробельному символу (запрос может начинаться с переноса строки)
        return sql.trim().split("\\s+", 2)[0].toUpperCase();
    }

    private static String tableName(String sql, String op) {
        if (sql == null) {
            return "";
        }
        Pattern pattern = TABLE_PATTERNS.get(op);
        if (pattern == null) {
            return ""; // CALL/WITH/DDL и пр. — без таблицы, имя шага останется «SQL <OP>»
        }
        var matcher = pattern.matcher(sql);
        return matcher.find() ? matcher.group(1).replace("\"", "").replace("`", "") : "";
    }
}
