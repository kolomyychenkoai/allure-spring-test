package io.github.kolomyychenkoai.allure.spring.demo;

import io.github.kolomyychenkoai.allure.spring.support.CurrentReport;
import io.github.kolomyychenkoai.allure.spring.support.LiquibaseTestApp;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Уровень B: миграции Liquibase попадают в отчёт двумя путями (см. {@code AllureLiquibaseInstrumentation}):
 * <ul>
 *   <li>ручная {@code liquibase.update()} ВО ВРЕМЯ теста — шаги по changeset'ам сразу;</li>
 *   <li>миграции на СТАРТЕ контекста — одним снимком в первом тесте (пишется в afterTestMethod).
 *       Из тела теста его не прочитать; проверяем в {@code @AfterAll} по уже записанному на диск
 *       результату — БЕЗ завязки на порядок тестов (как {@code WireMockReportIT}/{@code ReportSmokeIT}).</li>
 * </ul>
 * Liquibase в тестах глобально выключен (application.yml); здесь включаем точечно.
 */
@SpringBootTest(classes = LiquibaseTestApp.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
        "spring.liquibase.enabled=true",
        "spring.liquibase.change-log=classpath:db/changelog/allure-test-changelog.xml",
        "spring.jpa.hibernate.ddl-auto=none"
})
@Epic("allure-spring-test")
@Feature("Миграции БД (Liquibase)")
class LiquibaseReportIT {

    @Autowired
    private DataSource dataSource;

    @Test
    @DisplayName("ручная миграция во время теста даёт шаг «Liquibase: changeset …»")
    void liveMigrationAppearsInReport() throws Exception {
        try (Connection conn = dataSource.getConnection()) {
            Database db = DatabaseFactory.getInstance()
                    .findCorrectDatabaseImplementation(new JdbcConnection(conn));
            try (Liquibase liquibase = new Liquibase(
                    "db/changelog/allure-live-changelog.xml", new ClassLoaderResourceAccessor(), db)) {
                liquibase.update(new Contexts(), new LabelExpression());
            }
        }

        List<String> steps = CurrentReport.stepNames();
        assertTrue(steps.stream().anyMatch("Liquibase: changeset create-live-account (allure)"::equals),
                () -> "нет шага живой миграции Liquibase: " + steps);
    }

    @AfterAll
    @DisplayName("снимок миграций, применённых на старте, записан в отчёт")
    static void startupSnapshotIsWritten() {
        // снимок выложен в afterTestMethod (один раз на JVM); к @AfterAll он уже на диске.
        // Проверяем без @Order — не важно, в каком порядке шли тесты класса.
        assertTrue(CurrentReport.anyResultFileContains("changeset на старте"),
                "нет снимка стартовых миграций Liquibase в записанных результатах");
        assertTrue(CurrentReport.anyResultFileContainsAll("create-account", "add-account-email"),
                "снимок старта не содержит id применённых changeset'ов");
    }
}
