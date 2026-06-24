package io.github.kolomyychenkoai.allure.spring.data.internal;

import io.github.kolomyychenkoai.allure.spring.internal.AllureInstrumentationLogger;
import io.qameta.allure.Allure;
import net.ttddyy.dsproxy.ExecutionInfo;
import net.ttddyy.dsproxy.QueryInfo;
import net.ttddyy.dsproxy.listener.QueryExecutionListener;
import net.ttddyy.dsproxy.listener.logging.DefaultQueryLogEntryCreator;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Логирует РЕАЛЬНЫЙ SQL (через обёртку DataSource, datasource-proxy): каждый
 * выполненный запрос даёт в отчёте шаг «SQL &lt;OP&gt;» с вложением «SQL Query» —
 * текст запроса, параметры, время, успех. Дополняет аспект репозиториев («DB …»),
 * который показывает вызов метода и сущности.
 * <p>
 * Гейтинг — по активному Allure тест-кейсу: DDL при старте контекста в отчёт
 * не попадает. Всё в try/catch — инструментирование не роняет тест.
 * <p>
 * Порядок: SQL-шаг идёт ПЕРЕД соседним «DB Repo.method» (SQL выполняется внутри
 * вызова, а аспект эмитит свой шаг уже после) — это by-design.
 * <p>
 * Потокобезопасен: {@code logCreator} — stateless-форматтер datasource-proxy, {@code TABLE_PATTERNS}
 * неизменяема; собственного изменяемого состояния у листенера нет.
 */
public class AllureDataSourceListener implements QueryExecutionListener {

    /** Предкомпилированные шаблоны имени таблицы по операции (компиляция — один раз, не на запрос). */
    private static final Map<String, Pattern> TABLE_PATTERNS = Map.of(
            "INSERT", Pattern.compile("(?i)insert\\s+into\\s+([\\w.\"`]+)"),
            "UPDATE", Pattern.compile("(?i)update\\s+([\\w.\"`]+)"),
            "DELETE", Pattern.compile("(?i)delete\\s+from\\s+([\\w.\"`]+)"),
            "MERGE", Pattern.compile("(?i)merge\\s+into\\s+([\\w.\"`]+)"),
            "SELECT", Pattern.compile("(?i)\\bfrom\\s+([\\w.\"`]+)"));

    private final DefaultQueryLogEntryCreator logCreator = new DefaultQueryLogEntryCreator();

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
            String body = logCreator.getLogEntry(execInfo, queryInfoList, false, false, false);
            Allure.step(stepName(queryInfoList.get(0).getQuery()), step -> {
                Allure.addAttachment("SQL Query", "text/plain", body);
            });
        } catch (Throwable t) {
            AllureInstrumentationLogger.warn("DbSqlListener", t); // не роняем тест, сбой видно на WARNING
        }
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
