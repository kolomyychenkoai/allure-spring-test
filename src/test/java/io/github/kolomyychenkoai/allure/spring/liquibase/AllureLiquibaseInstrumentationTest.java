package io.github.kolomyychenkoai.allure.spring.liquibase;

import io.github.kolomyychenkoai.allure.spring.liquibase.internal.AllureLiquibaseInstrumentation;
import io.github.kolomyychenkoai.allure.spring.support.InMemoryAllure;
import io.qameta.allure.model.TestResult;
import liquibase.changelog.ChangeSet;
import liquibase.changelog.DatabaseChangeLog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Уровень A: логика перехвата Liquibase без движка — зовём {@code onExecute/flushStartupSnapshot}
 * напрямую. Дополняет уровень B ({@code LiquibaseReportIT}) детерминированной проверкой живого
 * шага, снимка старта и гейта. Статик-состояние (буфер старта + флаг снимка) сбрасываем
 * рефлексией вокруг каждого теста — чтобы тесты не влияли друг на друга и на уровень B.
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
    @DisplayName("упавший changeset шага не даёт")
    void failedChangesetNoStep() {
        TestResult result = allure.run("lb-fail", () ->
                AllureLiquibaseInstrumentation.onExecute(changeSet("boom", "alice"), new RuntimeException("fail")));

        assertThat(result.getSteps().stream().map(s -> s.getName()))
                .noneMatch(n -> n.startsWith("Liquibase:"));
    }

    @Test
    @DisplayName("миграции на старте (вне теста) выкладываются одним снимком в первом тесте")
    void startupSnapshotEmittedOnce() {
        // вне активного кейса — буферизуется как «старт»
        AllureLiquibaseInstrumentation.onExecute(changeSet("create-account", "allure"), null);
        AllureLiquibaseInstrumentation.onExecute(changeSet("add-email", "allure"), null);

        TestResult first = allure.run("lb-first", AllureLiquibaseInstrumentation::flushStartupSnapshot);
        assertThat(allure.hasStep(first, "Liquibase: применено 2 changeset на старте")).isTrue();
        assertThat(allure.attachment(first, "Применённые миграции").orElseThrow())
                .contains("create-account").contains("add-email");

        // во втором тесте снимок НЕ повторяется
        TestResult second = allure.run("lb-second", AllureLiquibaseInstrumentation::flushStartupSnapshot);
        assertThat(second.getSteps().stream().map(s -> s.getName()))
                .noneMatch(n -> n.contains("на старте"));
    }

    @Test
    @DisplayName("без активного кейса и без буфера снимок ничего не пишет")
    void noSnapshotWithoutBuffer() {
        // setUp установил InMemoryAllure, но allure.run не вызывали и буфер пуст
        AllureLiquibaseInstrumentation.flushStartupSnapshot();
        assertThat(allure.wroteNothing()).isTrue();
    }

    /** Сброс статик-состояния модуля (буфер старта + флаг снимка) рефлексией — для изоляции тестов. */
    private static void resetState() {
        try {
            Field buffer = AllureLiquibaseInstrumentation.class.getDeclaredField("STARTUP_BUFFER");
            buffer.setAccessible(true);
            ((Queue<?>) buffer.get(null)).clear();
            Field emitted = AllureLiquibaseInstrumentation.class.getDeclaredField("SNAPSHOT_EMITTED");
            emitted.setAccessible(true);
            ((AtomicBoolean) emitted.get(null)).set(false);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("не удалось сбросить состояние Liquibase-модуля", e);
        }
    }
}
