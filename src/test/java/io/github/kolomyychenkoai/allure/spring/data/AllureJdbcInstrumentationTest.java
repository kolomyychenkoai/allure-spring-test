package io.github.kolomyychenkoai.allure.spring.data;

import io.github.kolomyychenkoai.allure.spring.data.internal.AllureJdbcInstrumentation;
import io.github.kolomyychenkoai.allure.spring.support.InMemoryAllure;
import io.qameta.allure.model.TestResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Уровень A: логика перехвата JDBC без байткода и без БД — зовём {@code enter/exit} напрямую
 * (как это сделал бы advice вокруг метода шаблона). Дополняет уровень B ({@code JdbcReportIT})
 * детерминированной проверкой шага/вложений, дедупа по глубине и гейта активного кейса.
 */
class AllureJdbcInstrumentationTest {

    private static final String JDBC = "org.springframework.jdbc.core.JdbcTemplate";
    private static final String NAMED = "org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate";

    private InMemoryAllure allure;

    @BeforeEach
    void setUp() {
        allure = new InMemoryAllure().install();
    }

    @AfterEach
    void tearDown() {
        allure.uninstall();
    }

    @Test
    @DisplayName("query даёт шаг «DB JdbcTemplate.query» с вложениями SQL и DB Result")
    void queryProducesStep() {
        TestResult result = allure.run("jdbc-query", () -> {
            String uuid = AllureJdbcInstrumentation.enter(JDBC, "query",
                    new Object[]{"select name from widget where id = ?", 1});
            AllureJdbcInstrumentation.exit(uuid, List.of("a", "b"), null);
        });

        assertThat(allure.hasStep(result, "DB JdbcTemplate.query")).isTrue();
        assertThat(allure.attachment(result, "SQL").orElseThrow()).contains("select name from widget");
        assertThat(allure.attachment(result, "DB Result").orElseThrow()).contains("Collection size: 2");
    }

    @Test
    @DisplayName("вложенный вызов (делегат) не плодит второй шаг — логируется только внешний")
    void nestedDelegateNotDuplicated() {
        TestResult result = allure.run("jdbc-nested", () -> {
            // имитируем NamedParameterJdbcTemplate.update → внутренний JdbcTemplate.update
            String outer = AllureJdbcInstrumentation.enter(NAMED, "update",
                    new Object[]{"update widget set name = :n", java.util.Map.of("n", "x")});
            String inner = AllureJdbcInstrumentation.enter(JDBC, "update",
                    new Object[]{"update widget set name = ?"});
            assertThat(inner).isNull(); // внутренний делегат шаг не открывает
            AllureJdbcInstrumentation.exit(inner, 1, null);
            AllureJdbcInstrumentation.exit(outer, 1, null);
        });

        long dbSteps = result.getSteps().stream().filter(s -> s.getName().startsWith("DB ")).count();
        assertThat(dbSteps).isEqualTo(1);
        assertThat(allure.hasStep(result, "DB NamedParameterJdbcTemplate.update")).isTrue();
        // в шаге NamedParameter виден ИМЕНОВАННЫЙ SQL (а не ?-делегат)
        assertThat(allure.attachment(result, "SQL").orElseThrow()).contains(":n");
    }

    @Test
    @DisplayName("ошибка вызова — шаг BROKEN, DB Result не пишется")
    void errorIsBrokenStep() {
        TestResult result = allure.run("jdbc-error", () -> {
            String uuid = AllureJdbcInstrumentation.enter(JDBC, "queryForObject",
                    new Object[]{"select 1 from missing"});
            AllureJdbcInstrumentation.exit(uuid, null, new RuntimeException("bad sql"));
        });

        assertThat(result.getSteps().stream()
                .anyMatch(s -> s.getName().equals("DB JdbcTemplate.queryForObject")
                        && s.getStatus() == io.qameta.allure.model.Status.BROKEN)).isTrue();
        assertThat(allure.attachment(result, "DB Result")).isEmpty();
    }

    @Test
    @DisplayName("без активного тест-кейса в отчёт ничего не пишется")
    void noStepWithoutActiveCase() {
        // setUp установил InMemoryAllure, но allure.run не вызывали → активного кейса нет
        String uuid = AllureJdbcInstrumentation.enter(JDBC, "query", new Object[]{"select 1"});
        assertThat(uuid).isNull();
        AllureJdbcInstrumentation.exit(uuid, null, null); // парный выход — чтобы не утёк счётчик глубины

        assertThat(allure.wroteNothing()).isTrue(); // убери гейт активного кейса → покраснеет
    }
}
