package io.github.kolomyychenkoai.allure.spring.demo;

import io.github.kolomyychenkoai.allure.spring.support.CurrentReport;
import io.github.kolomyychenkoai.allure.spring.support.JpaTestApp;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.model.Attachment;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Уровень B: прямые JDBC-вызовы (минуя репозитории) попадают в отчёт шагами «DB JdbcTemplate.*».
 * Таблица {@code widget} поднимается Hibernate (JpaTestApp), DataSource обёрнут datasource-proxy —
 * значит проверяем заодно, что реальный SQL вкладывается ВНУТРЬ шага шаблона.
 * Бизнес-ассерты — на AssertJ (тоже попадают в отчёт, это ок); проверки ОТЧЁТА — на JUnit assertTrue.
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
        assertTrue(steps.stream().anyMatch("DB JdbcTemplate.update"::equals),
                () -> "нет шага DB JdbcTemplate.update: " + steps);
        assertTrue(steps.stream().anyMatch("DB JdbcTemplate.queryForObject"::equals),
                () -> "нет шага DB JdbcTemplate.queryForObject: " + steps);
        assertTrue(CurrentReport.attachmentContent("SQL").orElse("").toLowerCase().contains("insert into widget"),
                () -> "SQL без текста запроса: " + CurrentReport.attachmentContent("SQL"));
        // содержимое результата ИМЕННО шага queryForObject (что вернулось) — через реальную цепочку,
        // не только уровень A. Берём DB Result конкретного шага: первый общий DB Result — это update (=1).
        assertTrue(dbResultOfStep("DB JdbcTemplate.queryForObject").orElse("").contains("jdbc-gadget"),
                () -> "DB Result queryForObject без значения: " + dbResultOfStep("DB JdbcTemplate.queryForObject"));

        // реальный SQL от datasource-proxy вложен в шаг шаблона (без него виден только текст запроса)
        assertTrue(steps.stream().anyMatch(n -> n.startsWith("SQL INSERT") && n.contains("widget")),
                () -> "нет вложенного SQL INSERT widget: " + steps);
    }

    @Test
    @DisplayName("NamedParameterJdbcTemplate: один шаг с именованным SQL, без дубля делегата JdbcTemplate")
    void namedParameterNoDuplicate() {
        namedJdbc.update("insert into widget(name) values (:n)", Map.of("n", "named-gadget"));

        List<String> steps = CurrentReport.stepNames();
        long namedSteps = steps.stream().filter("DB NamedParameterJdbcTemplate.update"::equals).count();
        assertTrue(namedSteps == 1, () -> "ожидался ровно один NamedParameter-шаг: " + steps);
        // внутренний делегат JdbcTemplate.update подавлён счётчиком глубины
        assertTrue(steps.stream().noneMatch("DB JdbcTemplate.update"::equals),
                () -> "делегат JdbcTemplate.update не должен давать отдельный шаг: " + steps);
        assertTrue(CurrentReport.attachmentContent("SQL").orElse("").contains(":n"),
                () -> "в шаге NamedParameter должен быть именованный SQL (:n): " + CurrentReport.attachmentContent("SQL"));
    }

    /** Содержимое вложения «DB Result» КОНКРЕТНОГО шага (общий хелпер берёт первый по имени). */
    private static Optional<String> dbResultOfStep(String stepName) {
        return CurrentReport.steps().stream()
                .filter(s -> stepName.equals(s.getName()))
                .flatMap(s -> s.getAttachments().stream())
                .filter(a -> "DB Result".equals(a.getName()))
                .map(Attachment::getSource)
                .filter(src -> src != null && !src.isBlank())
                .findFirst()
                .flatMap(src -> {
                    try {
                        return Optional.of(Files.readString(Paths.get(
                                System.getProperty("allure.results.directory", "allure-results"), src),
                                StandardCharsets.UTF_8));
                    } catch (IOException e) {
                        return Optional.empty();
                    }
                });
    }
}
