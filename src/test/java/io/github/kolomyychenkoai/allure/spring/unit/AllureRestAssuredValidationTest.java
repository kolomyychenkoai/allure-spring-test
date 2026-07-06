package io.github.kolomyychenkoai.allure.spring.unit;

import io.github.kolomyychenkoai.allure.spring.rest.internal.AllureRestAssuredValidationInstrumentation;
import io.github.kolomyychenkoai.allure.spring.support.InMemoryAllure;
import io.qameta.allure.model.StepResult;
import io.qameta.allure.model.TestResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

/** Уровень A: логика шага «Проверка ответа: …» для RestAssured-валидации (без реального HTTP). */
class AllureRestAssuredValidationTest {

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
    @DisplayName("statusCode(200) → шаг «Проверка ответа: статус 200»")
    void logsStatusCode() {
        TestResult result = allure.run("status", () ->
                AllureRestAssuredValidationInstrumentation.onValidation("statusCode", new Object[]{200}, null));

        assertThat(allure.hasStep(result, "Проверка ответа: статус 200")).isTrue();
    }

    @Test
    @DisplayName("body(path, matcher) → шаг с путём и матчером")
    void logsBodyWithPathAndMatcher() {
        TestResult result = allure.run("body", () -> AllureRestAssuredValidationInstrumentation.onValidation(
                "body", new Object[]{"price", equalTo(9.99)}, null));

        assertThat(stepNames(result)).anyMatch(n ->
                n.startsWith("Проверка ответа: тело price") && n.contains("9.99"));
    }

    @Test
    @DisplayName("русские метки для header/contentType/time")
    void logsHumanLabels() {
        TestResult h = allure.run("h", () -> AllureRestAssuredValidationInstrumentation.onValidation(
                "header", new Object[]{"X-Trace", "abc"}, null));
        assertThat(stepNames(h)).contains("Проверка ответа: заголовок X-Trace abc");

        TestResult ct = allure.run("ct", () -> AllureRestAssuredValidationInstrumentation.onValidation(
                "contentType", new Object[]{"application/json"}, null));
        assertThat(stepNames(ct)).contains("Проверка ответа: тип содержимого application/json");

        TestResult t = allure.run("t", () -> AllureRestAssuredValidationInstrumentation.onValidation(
                "time", new Object[]{equalTo(200L)}, null));
        assertThat(stepNames(t)).anyMatch(n -> n.startsWith("Проверка ответа: время ответа"));
    }

    @Test
    @DisplayName("непрошедшая проверка (thrown != null) шага НЕ создаёт")
    void failedValidationProducesNoStep() {
        TestResult result = allure.run("fail", () -> AllureRestAssuredValidationInstrumentation.onValidation(
                "statusCode", new Object[]{200}, new AssertionError("expected 200 but was 500")));

        assertThat(stepNames(result)).noneMatch(n -> n.startsWith("Проверка ответа:"));
    }
}
