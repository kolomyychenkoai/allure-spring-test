package io.github.kolomyychenkoai.allure.spring.wiremock;

import io.github.kolomyychenkoai.allure.spring.support.InMemoryAllure;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.model.StepResult;
import io.qameta.allure.model.TestResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import com.github.tomakehurst.wiremock.stubbing.StubMapping;

import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.lessThan;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/** Уровень A: проверка содержимого отчёта для WireMock verify/reset (без брокера). */
@Epic("allure-spring-test")
@Feature("WireMock (заглушки внешних сервисов)")
class AllureWireMockVerifyTest {

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
    @DisplayName("verify(pattern): шаг + условие во вложении")
    void logsVerifyPattern() {
        TestResult result = allure.run("v", () -> AllureWireMockVerifyInstrumentation.onVerify(
                new Object[]{getRequestedFor(urlPathEqualTo("/api/prices"))}, null));

        assertThat(allure.hasStep(result, "Проверка обращений к заглушке")).isTrue();
        assertThat(allure.attachment(result, "Условие проверки").orElseThrow())
                .contains("/api/prices").contains("GET");
    }

    @Test
    @DisplayName("verify(count, ...) и verify(стратегия, ...): количество в имени шага")
    void logsVerifyCount() {
        TestResult exact = allure.run("count", () -> AllureWireMockVerifyInstrumentation.onVerify(
                new Object[]{2, getRequestedFor(urlPathEqualTo("/api/prices"))}, null));
        assertThat(allure.hasStep(exact, "Проверка обращений к заглушке (×2)")).isTrue();

        TestResult strategy = allure.run("strategy", () -> AllureWireMockVerifyInstrumentation.onVerify(
                new Object[]{lessThan(3), getRequestedFor(urlPathEqualTo("/api/prices"))}, null));
        assertThat(stepNames(strategy)).anyMatch(n -> n.toLowerCase().contains("less than"));
    }

    @Test
    @DisplayName("непрошедший verify шага НЕ создаёт (падение покажет Allure)")
    void failedVerifyProducesNoStep() {
        TestResult result = allure.run("fail", () -> AllureWireMockVerifyInstrumentation.onVerify(
                new Object[]{getRequestedFor(urlPathEqualTo("/api/prices"))},
                new AssertionError("ожидалось обращение, которого не было")));

        assertThat(stepNames(result)).noneMatch(n -> n.startsWith("Проверка обращений"));
    }

    @Test
    @DisplayName("resetAll даёт шаг «WireMock: сброс заглушек»")
    void logsResetAll() {
        // server=null: near-miss/сценарии не снимаются, проверяем сам шаг сброса
        TestResult result = allure.run("reset", () -> AllureWireMockVerifyInstrumentation.onResetAll(null));

        assertThat(allure.hasStep(result, "WireMock: сброс заглушек")).isTrue();
    }

    @Test
    @DisplayName("статический WireMock.reset() (старый DSL) тоже даёт шаг сброса")
    void logsStaticReset() {
        TestResult result = allure.run("static-reset", AllureWireMockVerifyInstrumentation::onStaticReset);

        assertThat(allure.hasStep(result, "WireMock: сброс заглушек")).isTrue();
    }

    @Test
    @DisplayName("stubFor: шаг «Создана заглушка …» с вложением WireMock Stub")
    void logsStub() {
        StubMapping stub = get(urlPathEqualTo("/api/prices"))
                .willReturn(okJson("{\"price\":9.99}")).build();

        TestResult result = allure.run("stub", () ->
                AllureWireMockVerifyInstrumentation.onStub(stub));

        assertThat(allure.hasStep(result, "Создана заглушка: GET /api/prices → 200")).isTrue();
        assertThat(allure.attachment(result, "WireMock Stub")).isPresent();
    }
}
