package io.github.kolomyychenkoai.allure.spring.unit;

import io.github.kolomyychenkoai.allure.spring.wiremock.internal.AllureWireMockVerifyInstrumentation;
import io.github.kolomyychenkoai.allure.spring.support.InMemoryAllure;
import io.qameta.allure.model.Status;
import io.qameta.allure.model.StepResult;
import io.qameta.allure.model.TestResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.github.tomakehurst.wiremock.stubbing.StubMapping;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.lessThan;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

/** Уровень A: проверка содержимого отчёта для WireMock verify/reset (без брокера). */
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
        assertThat(stepNames(strategy)).anyMatch(n ->
                n.startsWith("Проверка обращений к заглушке") && n.toLowerCase().contains("less than 3"));
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
    @DisplayName("частичный сброс на инстансе (resetRequests) даёт шаг с именем метода")
    void logsPartialReset() {
        // server=null: снимок near-miss пропускается, проверяем сам шаг частичного сброса
        TestResult result = allure.run("partial",
                () -> AllureWireMockVerifyInstrumentation.onPartialReset(null, "resetRequests"));

        assertThat(allure.hasStep(result, "WireMock: частичный сброс (resetRequests)")).isTrue();
    }

    @Test
    @DisplayName("частичный сброс через статический DSL (resetAllRequests) тоже даёт шаг")
    void logsStaticPartialReset() {
        TestResult result = allure.run("static-partial",
                () -> AllureWireMockVerifyInstrumentation.onStaticPartialReset("resetAllRequests"));

        assertThat(allure.hasStep(result, "WireMock: частичный сброс (resetAllRequests)")).isTrue();
    }

    @Test
    @DisplayName("stubFor: шаг «Создана заглушка …» с вложением WireMock Stub")
    void logsStub() {
        StubMapping stub = get(urlPathEqualTo("/api/prices"))
                .willReturn(okJson("{\"price\":9.99}")).build();

        TestResult result = allure.run("stub", () ->
                AllureWireMockVerifyInstrumentation.onStub(stub));

        assertThat(allure.hasStep(result, "Создана заглушка: GET /api/prices → 200")).isTrue();
        // содержимое вложения, а не только наличие: url и тело ответа из самого стаба
        assertThat(allure.attachment(result, "WireMock Stub").orElseThrow())
                .contains("/api/prices").contains("9.99");
    }

    @Test
    @DisplayName("onResetAll снимает near-miss и состояние сценария ДО сброса (самое хрупкое место)")
    void resetAllSnapshotsNearMissAndScenario() throws Exception {
        WireMockServer server = new WireMockServer(options().dynamicPort());
        server.start();
        try {
            server.stubFor(get(urlPathEqualTo("/api/prices")).willReturn(okJson("{\"price\":1}")));
            server.stubFor(get(urlPathEqualTo("/api/flaky")).inScenario("retry")
                    .whenScenarioStateIs(Scenario.STARTED)
                    .willReturn(aResponse().withStatus(503)).willSetStateTo("recovered"));
            // незаматченный запрос → WireMock запишет near-miss (ближайший стаб)
            HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create(server.baseUrl() + "/api/wrong")).build(),
                    HttpResponse.BodyHandlers.ofString());

            // onResetAll должен СНЯТЬ near-miss и состояние сценария ДО сброса (иначе reset их стирает)
            TestResult result = allure.run("reset-snap", () ->
                    AllureWireMockVerifyInstrumentation.onResetAll(server));

            assertThat(stepNames(result)).anyMatch(n -> n.startsWith("Near-miss:") && n.contains("/api/wrong"));
            assertThat(stepNames(result)).anyMatch(n -> n.contains("сценарий") && n.contains("retry"));
            assertThat(allure.hasStep(result, "WireMock: сброс заглушек")).isTrue();

            // near-miss — ИНФОРМАЦИОННЫЙ PASSED-шаг (тест им не роняем) и несёт diff во вложении
            StepResult nearMiss = result.getSteps().stream()
                    .filter(s -> s.getName().startsWith("Near-miss:")).findFirst().orElseThrow();
            assertThat(nearMiss.getStatus()).isEqualTo(Status.PASSED);
            assertThat(allure.attachment(result, "Near miss (почему не сматчилось)").orElseThrow())
                    .isNotBlank();
        } finally {
            server.stop();
        }
    }
}
