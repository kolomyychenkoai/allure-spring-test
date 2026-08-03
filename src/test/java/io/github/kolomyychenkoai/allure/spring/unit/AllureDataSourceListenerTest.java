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

    private ExecutionInfo execFailed() {
        ExecutionInfo info = exec();
        info.setSuccess(false);
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

    /** CallableStatement.registerOutParameter(index, sqlType) — out-параметр хранимки (второй арг — КОД типа). */
    private static ParameterSetOperation outParam(int index, int sqlType) {
        try {
            var method = java.sql.CallableStatement.class.getMethod("registerOutParameter", int.class, int.class);
            return new ParameterSetOperation(method, new Object[]{index, sqlType});
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
    @DisplayName("пакет из НЕСКОЛЬКИХ запросов даёт шаг НА КАЖДЫЙ, а не только на первый")
    void logsEveryQueryOfBatch() {
        // JdbcTemplate.batchUpdate(String...) и multi-statement execute отдают несколько QueryInfo;
        // раньше брался queryInfoList.get(0), остальные молча исчезали из отчёта
        TestResult result = allure.run("sql-batch", () -> listener.afterQuery(exec(), List.of(
                query("insert into widget (name) values ('b1')"),
                query("update widget set name='b2' where name='b1'"),
                query("delete from widget where name='b2'"))));

        assertThat(allure.hasStep(result, "SQL INSERT widget")).isTrue();
        assertThat(allure.hasStep(result, "SQL UPDATE widget")).isTrue();
        assertThat(allure.hasStep(result, "SQL DELETE widget")).isTrue();
    }

    @Test
    @DisplayName("у каждого шага пакета СВОЙ текст запроса и номер в пакете")
    void eachBatchStepCarriesItsOwnQuery() {
        TestResult result = allure.run("sql-batch-bodies", () -> listener.afterQuery(exec(), List.of(
                query("insert into widget (name) values ('b1')"),
                query("delete from widget where name='b1'"))));

        List<String> bodies = result.getSteps().stream()
                .flatMap(step -> step.getAttachments().stream())
                .map(att -> allure.attachmentContent(att).orElse(""))
                .toList();
        assertThat(bodies).anyMatch(body -> body.contains("insert into widget") && body.contains("запрос 1 из 2"));
        assertThat(bodies).anyMatch(body -> body.contains("delete from widget") && body.contains("запрос 2 из 2"));
    }

    @Test
    @DisplayName("огромный пакет не раздувает отчёт: шагов не больше потолка, остаток назван")
    void hugeBatchIsCapped() {
        List<QueryInfo> huge = java.util.stream.IntStream.range(0, 120)
                .mapToObj(i -> query("insert into widget (name) values ('n" + i + "')"))
                .toList();

        TestResult result = allure.run("sql-batch-huge", () -> listener.afterQuery(exec(), huge));

        // именно hasSize, а не «не больше»: при откате починки (shown=1) «не больше 50» осталось бы зелёным
        assertThat(result.getSteps()).hasSize(50);
        assertThat(result.getSteps().stream().flatMap(s -> s.getAttachments().stream())
                .map(att -> allure.attachmentContent(att).orElse(""))
                .anyMatch(body -> body.contains("из 120"))).isTrue();
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
    @DisplayName("связанные параметры подставляются В ТЕКСТ запроса (вместо ?), а не отдельным списком")
    void rendersBoundParameterValues() {
        QueryInfo query = query("insert into widget (name, id) values (?, ?)");
        // связываем ?1='laptop', ?2=42 — ровно как datasource-proxy после setString/setInt
        query.setParametersList(List.of(List.of(
                param("setString", String.class, 1, "laptop"),
                param("setInt", int.class, 2, 42))));

        TestResult result = allure.run("sql-params", () ->
                listener.afterQuery(exec(), List.of(query)));

        // подстановка позиционная: строка — в кавычках, число — как есть, голых ? не остаётся.
        // Мутация: если main вернёт шаблон с ? (перестанет подставлять) — упадёт на doesNotContain('?').
        assertThat(allure.attachment(result, "SQL Query").orElseThrow())
                .contains("values ('laptop', 42)")
                .doesNotContain("?");
    }

    @Test
    @DisplayName("NULL и экранирование одинарной кавычки в значении параметра")
    void rendersNullAndEscapesQuotes() {
        QueryInfo query = query("update widget set name=? where note=?");
        query.setParametersList(List.of(List.of(
                param("setString", String.class, 1, "O'Brien"), // кавычка в значении
                param("setString", String.class, 2, null))));    // null-значение строкой

        TestResult result = allure.run("sql-null", () ->
                listener.afterQuery(exec(), List.of(query)));

        assertThat(allure.attachment(result, "SQL Query").orElseThrow())
                .contains("name='O''Brien'")  // одинарная кавычка удвоена
                .contains("note=NULL");
    }

    @Test
    @DisplayName("setNull(index, sqlType): в текст идёт NULL, а не КОД типа java.sql.Types")
    void rendersSetNull() {
        QueryInfo query = query("update widget set name=? where id=?");
        // setNull(1, VARCHAR): второй аргумент — код типа (12), НЕ значение
        query.setParametersList(List.of(List.of(
                param("setNull", int.class, 1, java.sql.Types.VARCHAR),
                param("setInt", int.class, 2, 7))));

        TestResult result = allure.run("sql-setnull", () ->
                listener.afterQuery(exec(), List.of(query)));

        // мутация: без спец-обработки setNull вывелось бы «name=12» (код VARCHAR) — тут ждём NULL
        assertThat(allure.attachment(result, "SQL Query").orElseThrow())
                .contains("name=NULL where id=7")
                .doesNotContain("name=12");
    }

    @Test
    @DisplayName("batch (>1 ряда параметров): показан первый ряд + честная пометка про batch")
    void rendersBatchNote() {
        QueryInfo query = query("insert into widget (name) values (?)");
        query.setParametersList(List.of(
                List.of(param("setString", String.class, 1, "first")),
                List.of(param("setString", String.class, 1, "second"))));

        TestResult result = allure.run("sql-batch", () ->
                listener.afterQuery(exec(), List.of(query)));

        assertThat(allure.attachment(result, "SQL Query").orElseThrow())
                .contains("values ('first')")        // первый ряд подставлен
                .contains("пакетная вставка")        // пометка про пропущенные ряды
                .contains("из 2");
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

    @Test
    @DisplayName("неудачный запрос: в футере вложения «✗ ошибка», а не «✓ успешно»")
    void rendersErrorFooter() {
        TestResult result = allure.run("sql-failed", () ->
                listener.afterQuery(execFailed(), List.of(query("insert into widget (name) values (?)"))));

        // мутация: если футер захардкодят на «✓ успешно» — покажем «успех» на упавшей записи (соврём приёмщику)
        assertThat(allure.attachment(result, "SQL Query").orElseThrow())
                .contains("✗ ошибка")
                .doesNotContain("✓ успешно");
    }

    @Test
    @DisplayName("инвариант «отчёт не врёт»: несвязанный ? остаётся ? (нет параметров и частичный биндинг)")
    void keepsUnboundPlaceholders() {
        // (а) параметров нет вовсе → все ? сохранены как есть, ничего не подставляем
        TestResult noParams = allure.run("sql-noparams", () ->
                listener.afterQuery(exec(), List.of(query("insert into widget (name, id) values (?, ?)"))));
        assertThat(allure.attachment(noParams, "SQL Query").orElseThrow())
                .contains("values (?, ?)");

        // (б) связан только ?1 → ?2 остаётся ? (не глотаем, не сдвигаем)
        QueryInfo partial = query("insert into widget (name, id) values (?, ?)");
        partial.setParametersList(List.of(List.of(param("setString", String.class, 1, "solo"))));
        TestResult result = allure.run("sql-partial", () ->
                listener.afterQuery(exec(), List.of(partial)));
        assertThat(allure.attachment(result, "SQL Query").orElseThrow())
                .contains("values ('solo', ?)");
    }

    @Test
    @DisplayName("registerOutParameter (CallableStatement): плейсхолдер остаётся ?, КОД типа не подставляем")
    void skipsRegisterOutParameter() {
        QueryInfo call = query("call compute(?, ?)");
        // ?1 — обычное значение, ?2 — OUT-параметр (второй арг registerOutParameter = код java.sql.Types)
        call.setParametersList(List.of(List.of(
                param("setInt", int.class, 1, 5),
                outParam(2, java.sql.Types.INTEGER))));

        TestResult result = allure.run("sql-outparam", () ->
                listener.afterQuery(exec(), List.of(call)));

        // мутация: без пропуска registerOut во втором ? оказался бы код типа INTEGER (4) вместо ?
        assertThat(allure.attachment(result, "SQL Query").orElseThrow())
                .contains("call compute(5, ?)")
                .doesNotContain("compute(5, 4)");
    }
}
