package io.github.kolomyychenkoai.allure.spring.unit;

import io.github.kolomyychenkoai.allure.spring.data.internal.AllureDataSourceListener;
import io.github.kolomyychenkoai.allure.spring.support.InMemoryAllure;
import io.qameta.allure.model.TestResult;
import net.ttddyy.dsproxy.ExecutionInfo;
import net.ttddyy.dsproxy.QueryInfo;
import net.ttddyy.dsproxy.StatementType;
import net.ttddyy.dsproxy.proxy.ParameterSetOperation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.PreparedStatement;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Уровень A: детерминированная проверка содержимого отчёта для SQL-листенера. */
class AllureDataSourceListenerTest {

    private final AllureDataSourceListener listener = new AllureDataSourceListener();
    private InMemoryAllure allure;

    @BeforeEach
    void setUp() {
        allure = new InMemoryAllure().install();
    }

    @AfterEach
    void tearDown() {
        allure.uninstall();
    }

    private ExecutionInfo exec() {
        ExecutionInfo info = new ExecutionInfo();
        info.setSuccess(true);
        info.setStatementType(StatementType.PREPARED);
        info.setElapsedTime(3);
        return info;
    }

    private QueryInfo query(String sql) {
        QueryInfo info = new QueryInfo();
        info.setQuery(sql);
        return info;
    }

    /** PreparedStatement.setXxx(index, value) — как datasource-proxy записывает связанный параметр. */
    private static ParameterSetOperation param(String setter, Class<?> valueType, int index, Object value) {
        try {
            var method = PreparedStatement.class.getMethod(setter, int.class, valueType);
            return new ParameterSetOperation(method, new Object[]{index, value});
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("INSERT: шаг «SQL INSERT», текст запроса во вложении")
    void logsInsert() {
        TestResult result = allure.run("sql-insert", () ->
                listener.afterQuery(exec(), List.of(query("insert into widget (name, id) values (?, ?)"))));

        assertThat(allure.hasStep(result, "SQL INSERT widget")).isTrue();
        assertThat(allure.attachment(result, "SQL Query").orElseThrow())
                .contains("insert into widget");
    }

    @Test
    @DisplayName("SELECT: в имени шага операция и таблица")
    void logsSelect() {
        TestResult result = allure.run("sql-select", () ->
                listener.afterQuery(exec(), List.of(query("select w.id, w.name from widget w where w.id=?"))));

        assertThat(allure.hasStep(result, "SQL SELECT widget")).isTrue();
        assertThat(allure.attachment(result, "SQL Query").orElseThrow())
                .contains("from widget");
    }

    @Test
    @DisplayName("связанные параметры PreparedStatement: их ЗНАЧЕНИЯ видны во вложении SQL Query")
    void rendersBoundParameterValues() {
        QueryInfo query = query("insert into widget (name, id) values (?, ?)");
        // связываем ?1='laptop', ?2=42 — ровно как datasource-proxy после setString/setInt
        query.setParametersList(List.of(List.of(
                param("setString", String.class, 1, "laptop"),
                param("setInt", int.class, 2, 42))));

        TestResult result = allure.run("sql-params", () ->
                listener.afterQuery(exec(), List.of(query)));

        // мутация: если main перестанет рендерить параметры (напр. logCreator без Params) — значения исчезнут → RED
        assertThat(allure.attachment(result, "SQL Query").orElseThrow())
                .contains("laptop")   // значение строкового параметра
                .contains("42");      // значение числового параметра
    }

    @Test
    @DisplayName("UPDATE и DELETE: операция и таблица в имени шага")
    void logsUpdateAndDelete() {
        TestResult upd = allure.run("sql-update", () ->
                listener.afterQuery(exec(), List.of(query("update widget set name=? where id=?"))));
        assertThat(allure.hasStep(upd, "SQL UPDATE widget")).isTrue();

        TestResult del = allure.run("sql-delete", () ->
                listener.afterQuery(exec(), List.of(query("delete from widget where id=?"))));
        assertThat(allure.hasStep(del, "SQL DELETE widget")).isTrue();
    }
}
