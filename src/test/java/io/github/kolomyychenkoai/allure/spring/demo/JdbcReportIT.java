package io.github.kolomyychenkoai.allure.spring.demo;

import io.github.kolomyychenkoai.allure.spring.support.CurrentReport;
import io.github.kolomyychenkoai.allure.spring.support.JpaTestApp;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Уровень B: прямые JDBC-вызовы (минуя репозитории) попадают в отчёт шагами «DB JdbcTemplate.*».
 * Таблица {@code widget} поднимается Hibernate (JpaTestApp), DataSource обёрнут datasource-proxy —
 * значит проверяем заодно, что реальный SQL вкладывается ВНУТРЬ шага шаблона.
 * Бизнес-ассерты — на AssertJ (тоже попадают в отчёт, это ок); проверки ОТЧЁТА — через немой
 * {@code CurrentReport.check}/{@code assertStep} (JUnit assertTrue сам стал бы шагом «Проверка: …»).
 */
@SpringBootTest(classes = JpaTestApp.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Epic("allure-spring-test")
@Feature("Прямой JDBC (JdbcTemplate)")
@Transactional // вставки откатываются — не засоряем общую таблицу widget (контекст JpaTestApp кэшируется)
class JdbcReportIT {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private NamedParameterJdbcTemplate namedJdbc;

    @Test
    @DisplayName("update/queryForObject через JdbcTemplate дают шаги «DB JdbcTemplate.*» с SQL и реальным запросом внутри")
    void jdbcTemplateAppearsInReport() {
        jdbc.update("insert into widget(name) values (?)", "jdbc-gadget");
        String name = jdbc.queryForObject("select name from widget where name = ?", String.class, "jdbc-gadget");
        assertThat(name).isEqualTo("jdbc-gadget");

        List<String> steps = CurrentReport.stepNames();
        CurrentReport.check(steps.stream().anyMatch("DB JdbcTemplate.update"::equals),
                () -> "нет шага DB JdbcTemplate.update: " + steps);
        CurrentReport.check(steps.stream().anyMatch("DB JdbcTemplate.queryForObject"::equals),
                () -> "нет шага DB JdbcTemplate.queryForObject: " + steps);
        CurrentReport.check(CurrentReport.attachmentContent("SQL (шаблон)").orElse("").toLowerCase().contains("insert into widget"),
                () -> "SQL без текста запроса: " + CurrentReport.attachmentContent("SQL (шаблон)"));
        // содержимое результата ИМЕННО шага queryForObject (что вернулось) — через реальную цепочку,
        // не только уровень A. Берём DB Result конкретного шага: первый общий DB Result — это update (=1).
        CurrentReport.check(CurrentReport.attachmentOfStep("DB JdbcTemplate.queryForObject", "DB Result").orElse("").contains("jdbc-gadget"),
                () -> "DB Result queryForObject без значения: " + CurrentReport.attachmentOfStep("DB JdbcTemplate.queryForObject", "DB Result"));

        // реальный SQL от datasource-proxy вложен в шаг шаблона (без него виден только текст запроса)
        CurrentReport.check(steps.stream().anyMatch(n -> n.startsWith("SQL INSERT") && n.contains("widget")),
                () -> "нет вложенного SQL INSERT widget: " + steps);
    }

    @Test
    @DisplayName("NamedParameterJdbcTemplate: один шаг с именованным SQL, без дубля делегата JdbcTemplate")
    void namedParameterNoDuplicate() {
        namedJdbc.update("insert into widget(name) values (:n)", Map.of("n", "named-gadget"));

        List<String> steps = CurrentReport.stepNames();
        long namedSteps = steps.stream().filter("DB NamedParameterJdbcTemplate.update"::equals).count();
        CurrentReport.check(namedSteps == 1, () -> "ожидался ровно один NamedParameter-шаг: " + steps);
        // внутренний делегат JdbcTemplate.update подавлён счётчиком глубины
        CurrentReport.check(steps.stream().noneMatch("DB JdbcTemplate.update"::equals),
                () -> "делегат JdbcTemplate.update не должен давать отдельный шаг: " + steps);
        CurrentReport.check(CurrentReport.attachmentContent("SQL (шаблон)").orElse("").contains(":n"),
                () -> "в шаге NamedParameter должен быть именованный SQL (:n): " + CurrentReport.attachmentContent("SQL (шаблон)"));
    }

    @Test
    @DisplayName("batchUpdate: КАЖДЫЙ запрос пакета даёт свой шаг SQL, а не только первый")
    void batchUpdateLogsEveryStatement() {
        jdbc.batchUpdate("insert into widget(name) values ('batch-1')",
                "update widget set name='batch-2' where name='batch-1'");

        List<String> steps = CurrentReport.stepNames();
        CurrentReport.check(steps.stream().anyMatch("DB JdbcTemplate.batchUpdate"::equals),
                () -> "нет шага DB JdbcTemplate.batchUpdate: " + steps);
        CurrentReport.check(steps.stream().anyMatch(n -> n.startsWith("SQL INSERT") && n.contains("widget")),
                () -> "нет SQL INSERT из пакета: " + steps);
        // второй запрос пакета раньше молча терялся: брался queryInfoList.get(0)
        CurrentReport.check(steps.stream().anyMatch(n -> n.startsWith("SQL UPDATE") && n.contains("widget")),
                () -> "второй запрос пакета потерян: " + steps);
    }

}
