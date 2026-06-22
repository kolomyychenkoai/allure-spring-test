package io.github.kolomyychenkoai.allure.spring.assertion;

import io.github.kolomyychenkoai.allure.spring.support.InMemoryAllure;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.model.Status;
import io.qameta.allure.model.StepResult;
import io.qameta.allure.model.TestResult;
import org.hamcrest.MatcherAssert;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

/** Уровень A: детерминированная проверка содержимого отчёта для Hamcrest. */
@Epic("allure-spring-test")
@Feature("Hamcrest")
class AllureHamcrestTest {

    @BeforeAll
    static void install() {
        AllureHamcrestInstrumentation.install();
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

    private List<String> stepNames(TestResult result) {
        return result.getSteps().stream().map(StepResult::getName).toList();
    }

    @Test
    @DisplayName("2-арг assertThat(actual, matcher) даёт шаг (через делегирование в 3-арг)")
    void logsTwoArg() {
        TestResult result = allure.run("2arg", () ->
                MatcherAssert.assertThat("mouse", is("mouse")));

        assertThat(stepNames(result))
                .anyMatch(n -> n.startsWith("Проверка:")
                        && n.contains("значение mouse")
                        && n.contains("ожидалось"));
    }

    @Test
    @DisplayName("3-арг assertThat(reason, actual, matcher) — reason в имени шага")
    void logsThreeArgWithReason() {
        TestResult result = allure.run("3arg", () ->
                MatcherAssert.assertThat("имя товара", "mouse", is("mouse")));

        assertThat(stepNames(result))
                .anyMatch(n -> n.contains("имя товара") && n.contains("значение mouse"));
    }

    @Test
    @DisplayName("несовпадение видно шагом со статусом FAILED")
    void failedMatchIsFailedStep() {
        TestResult result = allure.run("fail", () ->
                assertThatThrownBy(() -> MatcherAssert.assertThat("несовпадение", "mouse", equalTo("cat")))
                        .isInstanceOf(AssertionError.class));

        StepResult step = result.getSteps().stream()
                .filter(s -> s.getName().contains("несовпадение"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("нет шага про несовпадение"));
        assertThat(step.getStatus()).isEqualTo(Status.FAILED);
    }

    @Test
    @DisplayName("не-equality матчеры и не-строковый actual: имена шагов читаемы, без падений")
    void logsVariousMatchersAndTypes() {
        TestResult result = allure.run("various", () -> {
            MatcherAssert.assertThat(5, greaterThan(0));
            MatcherAssert.assertThat("есть значение", "x", notNullValue());
            MatcherAssert.assertThat(List.of("a", "b"), hasItem("a"));
        });

        List<String> names = stepNames(result);
        assertThat(names).anyMatch(n -> n.contains("значение 5") && n.contains("greater than"));
        assertThat(names).anyMatch(n -> n.contains("есть значение") && n.contains("not null"));
        assertThat(names).anyMatch(n -> n.contains("[a, b]") && n.contains("collection containing"));
    }
}
