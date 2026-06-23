package io.github.kolomyychenkoai.allure.spring.assertion;

import io.github.kolomyychenkoai.allure.spring.support.InMemoryAllure;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.model.StepResult;
import io.qameta.allure.model.TestResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Уровень A: детерминированная проверка содержимого отчёта для AssertJ. */
@Epic("allure-spring-test")
@Feature("AssertJ")
class AllureAssertJTest {

    @BeforeAll
    static void install() {
        AllureAssertJInstrumentation.install();
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
    @DisplayName("isEqualTo даёт шаг «значение X — isEqualTo Y»")
    void logsIsEqualTo() {
        TestResult result = allure.run("eq", () -> assertThat("laptop").isEqualTo("laptop"));

        assertThat(stepNames(result))
                .anyMatch(n -> n.contains("значение laptop") && n.contains("isEqualTo laptop"));
    }

    @Test
    @DisplayName("цепочка ассертов даёт по шагу на каждый, isNotNull (blacklist) не логируется")
    void logsChainAndSkipsBlacklisted() {
        TestResult result = allure.run("chain", () ->
                assertThat("laptop").isNotNull().startsWith("lap").endsWith("top"));

        List<String> names = stepNames(result);
        assertThat(names).anyMatch(n -> n.contains("startsWith"));
        assertThat(names).anyMatch(n -> n.contains("endsWith"));
        assertThat(names).noneMatch(n -> n.contains("isNotNull")); // в blacklist
    }

    @Test
    @DisplayName("непройденный ассерт шага НЕ создаёт (падение покажет Allure)")
    void failedAssertProducesNoStep() {
        TestResult result = allure.run("fail", () ->
                assertThatThrownBy(() -> assertThat("laptop").isEqualTo("phone"))
                        .isInstanceOf(AssertionError.class));

        assertThat(stepNames(result)).noneMatch(n -> n.contains("isEqualTo phone"));
    }

    @Test
    @DisplayName("после падения счётчик глубины не «залипает» — следующий ассерт логируется")
    void depthBalancedAfterFailure() {
        TestResult result = allure.run("balance", () -> {
            assertThatThrownBy(() -> assertThat("a").isEqualTo("b"))
                    .isInstanceOf(AssertionError.class);
            assertThat("x").isEqualTo("x"); // если бы глубина утекла — шаг бы проглотился как внутренний
        });

        assertThat(stepNames(result)).anyMatch(n -> n.contains("значение x — isEqualTo x"));
    }

    @Test
    @DisplayName("конфигурационные методы (as) не логируются, многоарг и коллекции — да")
    void blacklistAndCollections() {
        TestResult result = allure.run("misc", () -> {
            assertThat("laptop").as("описание").isEqualTo("laptop"); // as — blacklist
            assertThat(5).isBetween(1, 10);                          // многоарг
            assertThat(List.of("a", "b")).contains("a").hasSize(2);  // коллекция
        });

        List<String> names = stepNames(result);
        assertThat(names).noneMatch(n -> n.contains(" — as "));
        assertThat(names).anyMatch(n -> n.contains("isBetween 1, 10")); // оба аргумента
        assertThat(names).anyMatch(n -> n.contains("contains [a]"));    // varargs развёрнут
        assertThat(names).anyMatch(n -> n.contains("hasSize 2"));
    }
}
