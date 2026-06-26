package io.github.kolomyychenkoai.allure.spring.unit;

import io.github.kolomyychenkoai.allure.spring.awaitility.internal.AllureAwaitilityConditionListener;
import io.github.kolomyychenkoai.allure.spring.support.InMemoryAllure;
import io.qameta.allure.model.StepResult;
import io.qameta.allure.model.TestResult;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Уровень A: гоняем НАСТОЯЩИЙ Awaitility с нашим слушателем через {@code InMemoryAllure}, без
 * Spring. Дополняет уровень B ({@code AwaitilityReportIT}) детерминированной проверкой шага и
 * гейта активного кейса.
 */
class AllureAwaitilityConditionListenerTest {

    @BeforeAll
    static void install() {
        Awaitility.setDefaultConditionEvaluationListener(new AllureAwaitilityConditionListener());
    }

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
    @DisplayName("ожидание с алиасом: шаг «Ожидание: <алиас> — выполнено за N мс», без техножаргона")
    void satisfiedAwaitProducesStep() {
        TestResult result = allure.run("await-ok", () ->
                Awaitility.await("кэш прогрелся").atMost(Duration.ofSeconds(2)).until(() -> true));

        // строго: человекочитаемый алиас сразу после «Ожидание: », время в мс
        assertThat(result.getSteps().stream().map(StepResult::getName))
                .anyMatch(n -> n.matches("Ожидание: кэш прогрелся — выполнено за \\d+ мс"));
        // и НИ в одном шаге нет сырого описания Awaitility (лямбда/FQCN) — иначе нечитаемо ручнику
        assertThat(result.getSteps().stream().map(StepResult::getName))
                .noneMatch(n -> n.contains("Lambda") || n.contains("defined as") || n.contains("alias"));
        // полное описание условия (что ждали) сохранено во вложении — детали по запросу
        assertThat(allure.attachment(result, "Условие ожидания").orElse("")).contains("кэш прогрелся");
    }

    @Test
    @DisplayName("ожидание без алиаса: нейтральный шаг «Ожидание выполнено за N мс» (без лямбды)")
    void satisfiedAwaitWithoutAliasProducesNeutralStep() {
        TestResult result = allure.run("await-noalias", () ->
                Awaitility.await().atMost(Duration.ofSeconds(2)).until(() -> true));

        assertThat(result.getSteps().stream().map(StepResult::getName))
                .anyMatch(n -> n.matches("Ожидание выполнено за \\d+ мс"));
        assertThat(result.getSteps().stream().map(StepResult::getName))
                .noneMatch(n -> n.contains("Lambda") || n.contains("defined as") || n.contains("alias"));
        // даже без алиаса деталь «что ждали» не потеряна — она во вложении (сырое описание Awaitility)
        assertThat(allure.attachment(result, "Условие ожидания")).isPresent();
    }

    @Test
    @DisplayName("без активного тест-кейса ожидание шага не пишет")
    void noStepWithoutActiveCase() {
        // setUp установил InMemoryAllure, но allure.run не вызывали → активного кейса нет
        Awaitility.await().atMost(Duration.ofSeconds(2)).until(() -> true);

        assertThat(allure.wroteNothing()).isTrue(); // убери гейт активного кейса → покраснеет
    }
}
