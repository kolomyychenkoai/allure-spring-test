package io.github.kolomyychenkoai.allure.spring.e2e;

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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
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
 *   <li>миграции на СТАРТЕ контекста — одним снимком в первом тесте (пишется в afterTestMethod,
 *       поэтому проверяем по уже записанному файлу результата следующим по порядку тестом).</li>
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
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LiquibaseReportIT {

    @Autowired
    private DataSource dataSource;

    @Test
    @Order(1)
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

    @Test
    @Order(2)
    @DisplayName("снимок миграций, применённых на старте, записан в отчёт")
    void startupSnapshotIsWritten() {
        // снимок выложен в afterTestMethod первого теста (@Order 1) → файл результата уже записан
        assertTrue(CurrentReport.anyResultFileContains("changeset на старте"),
                "нет снимка стартовых миграций Liquibase в записанных результатах");
        assertTrue(CurrentReport.anyResultFileContainsAll("create-account", "add-account-email"),
                "снимок старта не содержит id применённых changeset'ов");
    }
}
