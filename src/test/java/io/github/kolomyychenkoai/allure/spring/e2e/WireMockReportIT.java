package io.github.kolomyychenkoai.allure.spring.e2e;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.github.kolomyychenkoai.allure.spring.support.CurrentReport;
import io.github.kolomyychenkoai.allure.spring.support.TestApp;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Уровень B: «живой» прогон с реальным WireMockServer через РЕАЛЬНУЮ авто-регистрацию
 * (листенер находит сервер сам + байткод stubFor/verify/resetAll). Шаги пишутся в настоящий
 * отчёт (showcase). Тест читает через {@link CurrentReport} шаги, записанные ВО ВРЕМЯ теста:
 * создание стабов, verify, near-miss и состояния сценариев (снимаются в reset-advice ДО
 * сброса), сам сброс. Шаги отдельных запросов листенер пишет в afterTestMethod (после чтения),
 * поэтому в showcase они есть, но здесь не проверяются — их содержимое на уровне A
 * ({@code AllureWireMockListenerTest}).
 */
@SpringBootTest(classes = TestApp.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Epic("allure-spring-test")
@Feature("WireMock (заглушки внешних сервисов)")
class WireMockReportIT {

    static WireMockServer wireMock = new WireMockServer(0);

    @BeforeAll
    static void start() {
        wireMock.start();
    }

    @AfterAll
    static void stop() {
        wireMock.stop();
    }

    @Test
    @DisplayName("стабы, verify, near-miss, сценарии и сброс WireMock попадают в отчёт")
    void wireMockExchangesAppearInReport() throws Exception {
        String base = "http://localhost:" + wireMock.port();
        HttpClient client = HttpClient.newHttpClient();

        wireMock.stubFor(get(urlPathEqualTo("/api/prices")).willReturn(okJson("{\"price\":9.99}")));
        wireMock.stubFor(post(urlPathEqualTo("/api/orders"))
                .willReturn(aResponse().withStatus(201).withBody("{\"id\":7}")));
        int pricesStatus = send(client, base + "/api/prices", null);
        send(client, base + "/api/orders", "{\"productName\":\"laptop\"}");

        wireMock.stubFor(get(urlPathEqualTo("/api/flaky")).inScenario("retry")
                .whenScenarioStateIs(STARTED)
                .willReturn(aResponse().withStatus(503)).willSetStateTo("recovered"));
        wireMock.stubFor(get(urlPathEqualTo("/api/flaky")).inScenario("retry")
                .whenScenarioStateIs("recovered").willReturn(okJson("{\"ok\":true}")));
        send(client, base + "/api/flaky", null);
        send(client, base + "/api/flaky", null);

        int missStatus = send(client, base + "/api/does-not-exist", null); // 404 → near-miss

        // санити: трафик реально дошёл до сервера (иначе near-miss/шаги не из ниоткуда)
        assertTrue(pricesStatus == 200, "стаб /api/prices не ответил 200: " + pricesStatus);
        assertTrue(missStatus == 404, "незаматченный запрос не дал 404: " + missStatus);

        wireMock.verify(getRequestedFor(urlPathEqualTo("/api/prices")));
        wireMock.verify(2, getRequestedFor(urlPathEqualTo("/api/flaky")));
        wireMock.resetAll(); // снимает near-miss/сценарии ДО сброса + шаг сброса

        List<String> steps = CurrentReport.stepNames();
        assertTrue(steps.stream().anyMatch(n -> n.startsWith("Создана заглушка:") && n.contains("/api/prices")),
                () -> "" + steps);
        assertTrue(steps.stream().anyMatch(n -> n.startsWith("Проверка обращений к заглушке")), () -> "" + steps);
        assertTrue(steps.stream().anyMatch(n -> n.contains("(×2)")), () -> "" + steps);
        assertTrue(steps.stream().anyMatch(n -> n.startsWith("Near-miss:") && n.contains("/api/does-not-exist")),
                () -> "" + steps);
        assertTrue(steps.stream().anyMatch(n -> n.contains("сценарий") && n.contains("retry")), () -> "" + steps);
        assertTrue(steps.contains("WireMock: сброс заглушек"), () -> "" + steps);

        // содержимое вложения стаба через реальную цепочку
        String stub = CurrentReport.attachmentContent("WireMock Stub").orElse("");
        assertTrue(stub.contains("/api/prices"), () -> "WireMock Stub: " + stub);
    }

    private static int send(HttpClient client, String url, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url));
        HttpRequest req = body == null ? b.build()
                : b.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build();
        return client.send(req, HttpResponse.BodyHandlers.ofString()).statusCode();
    }
}
