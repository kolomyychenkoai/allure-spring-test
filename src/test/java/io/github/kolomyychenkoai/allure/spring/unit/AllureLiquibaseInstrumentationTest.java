package io.github.kolomyychenkoai.allure.spring.unit;

import io.github.kolomyychenkoai.allure.spring.liquibase.internal.AllureLiquibaseInstrumentation;
import io.github.kolomyychenkoai.allure.spring.support.InMemoryAllure;
import io.qameta.allure.model.TestResult;
import liquibase.changelog.ChangeSet;
import liquibase.changelog.DatabaseChangeLog;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Queue;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Уровень A: логика перехвата Liquibase без движка — зовём {@code onExecute/emitStartupSnapshot}
 * напрямую. Дополняет уровень B ({@code LiquibaseReportIT}) детерминированной проверкой живого
 * шага, снимка старта (реплей в каждом тесте + дедуп) и гейта. Статик-состояние (буфер старта +
 * накопленный снимок) сбрасываем рефлексией вокруг каждого теста — чтобы тесты не влияли друг на
 * друга и на уровень B.
 */
class AllureLiquibaseInstrumentationTest {

    private InMemoryAllure allure;

    @BeforeEach
    void setUp() {
        resetState();
        allure = new InMemoryAllure().install();
    }

    @AfterEach
    void tearDown() {
        allure.uninstall();
        resetState();
    }

    /**
     * Гарантированный сброс статики после ВСЕГО класса — чтобы порядок тест-классов в JVM не влиял
     * на {@code LiquibaseReportIT} (он полагается на пустой стартовый снимок до реальных миграций).
     */
    @AfterAll
    static void tearDownClass() {
        resetState();
    }

    private static ChangeSet changeSet(String id, String author) {
        return new ChangeSet(id, author, false, false,
                "db/changelog/test.xml", null, null, new DatabaseChangeLog());
    }

    @Test
    @DisplayName("миграция во время теста: шаг «Liquibase: changeset <id> (<author>)» с деталями")
    void liveChangesetProducesStep() {
        TestResult result = allure.run("lb-live", () ->
                AllureLiquibaseInstrumentation.onExecute(changeSet("create-thing", "alice"), null));

        assertThat(allure.hasStep(result, "Liquibase: changeset create-thing (alice)")).isTrue();
        assertThat(allure.attachment(result, "Changeset").orElseThrow())
                .contains("Id: create-thing").contains("Author: alice");
    }

    @Test
    @DisplayName("вложение Changeset содержит changelog и comments (детали под ассертом)")
    void changesetAttachmentHasDetails() {
        ChangeSet cs = changeSet("create-thing", "alice");
        cs.setComments("создаём таблицу thing");

        TestResult result = allure.run("lb-details", () -> AllureLiquibaseInstrumentation.onExecute(cs, null));

        assertThat(allure.attachment(result, "Changeset").orElseThrow())
                .contains("Changelog: db/changelog/test.xml")
                .contains("Comments: создаём таблицу thing");
    }

    @Test
    @DisplayName("упавший changeset шага не даёт")
    void failedChangesetNoStep() {
        TestResult result = allure.run("lb-fail", () ->
                AllureLiquibaseInstrumentation.onExecute(changeSet("boom", "alice"), new RuntimeException("fail")));

        assertThat(result.getSteps().stream().map(s -> s.getName()))
                .noneMatch(n -> n.startsWith("Liquibase:"));
    }

    @Test
    @DisplayName("снимок стартовой схемы повторяется в НАЧАЛЕ каждого теста (реплей)")
    void startupSnapshotReplaysEveryCall() {
        // вне активного кейса — буферизуется как «старт»
        AllureLiquibaseInstrumentation.onExecute(changeSet("create-account", "allure"), null);
        AllureLiquibaseInstrumentation.onExecute(changeSet("add-email", "allure"), null);

        // первый тест: буфер сливается в снимок и рисуется шаг
        TestResult first = allure.run("lb-first", AllureLiquibaseInstrumentation::emitStartupSnapshot);
        assertThat(allure.hasStep(first, "🛢️ Liquibase: схема БД (2 changeset)")).isTrue();
        assertThat(allure.attachment(first, "Применённые миграции").orElseThrow())
                .contains("create-account").contains("add-email");

        // второй тест: буфер уже пуст, но снимок ПОВТОРЯЕТСЯ (реплей из STARTUP_SNAPSHOT)
        TestResult second = allure.run("lb-second", AllureLiquibaseInstrumentation::emitStartupSnapshot);
        assertThat(allure.hasStep(second, "🛢️ Liquibase: схема БД (2 changeset)")).isTrue();
    }

    @Test
    @DisplayName("повторно забуференный changeset в снимок попадает один раз (дедуп)")
    void startupSnapshotDedupes() {
        AllureLiquibaseInstrumentation.onExecute(changeSet("create-account", "allure"), null);
        // тот же changeset забуферен снова (напр. перезагрузка контекста) — не должен задвоиться
        AllureLiquibaseInstrumentation.onExecute(changeSet("create-account", "allure"), null);

        TestResult result = allure.run("lb-dedup", AllureLiquibaseInstrumentation::emitStartupSnapshot);
        assertThat(allure.hasStep(result, "🛢️ Liquibase: схема БД (1 changeset)")).isTrue();
    }

    @Test
    @DisplayName("без активного кейса и без буфера снимок ничего не пишет")
    void noSnapshotWithoutBuffer() {
        // setUp установил InMemoryAllure, но allure.run не вызывали и буфер пуст
        AllureLiquibaseInstrumentation.emitStartupSnapshot();
        assertThat(allure.wroteNothing()).isTrue();
    }

    /** Сброс статик-состояния модуля (буфер старта + накопленный снимок) рефлексией — для изоляции тестов. */
    private static void resetState() {
        try {
            Field buffer = AllureLiquibaseInstrumentation.class.getDeclaredField("STARTUP_BUFFER");
            buffer.setAccessible(true);
            ((Queue<?>) buffer.get(null)).clear();
            Field snapshot = AllureLiquibaseInstrumentation.class.getDeclaredField("STARTUP_SNAPSHOT");
            snapshot.setAccessible(true);
            ((List<?>) snapshot.get(null)).clear();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("не удалось сбросить состояние Liquibase-модуля", e);
        }
    }
}
