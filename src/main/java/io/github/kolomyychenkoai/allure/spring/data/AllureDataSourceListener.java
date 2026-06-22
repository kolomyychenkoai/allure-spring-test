package io.github.kolomyychenkoai.allure.spring.data;

import io.qameta.allure.Allure;
import net.ttddyy.dsproxy.ExecutionInfo;
import net.ttddyy.dsproxy.QueryInfo;
import net.ttddyy.dsproxy.listener.QueryExecutionListener;
import net.ttddyy.dsproxy.listener.logging.DefaultQueryLogEntryCreator;

import java.util.List;

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
 */
public class AllureDataSourceListener implements QueryExecutionListener {

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
            String op = firstKeyword(queryInfoList.get(0).getQuery());
            String body = logCreator.getLogEntry(execInfo, queryInfoList, false, false, false);
            Allure.step("SQL " + op, step -> {
                Allure.addAttachment("SQL Query", "text/plain", body);
            });
        } catch (Throwable ignored) {
            // инструментирование не должно ронять тест
        }
    }

    private static String firstKeyword(String sql) {
        if (sql == null || sql.isBlank()) {
            return "query";
        }
        // по любому пробельному символу (запрос может начинаться с переноса строки)
        return sql.trim().split("\\s+", 2)[0].toUpperCase();
    }
}
