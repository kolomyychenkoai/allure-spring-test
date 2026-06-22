package io.github.kolomyychenkoai.allure.spring.e2e;

import com.github.tomakehurst.wiremock.WireMockServer;
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

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Уровень B: «живой» прогон с реальным WireMockServer (in-process, без Docker).
 * Никакой настройки Allure в тесте — листенер находит сервер сам (через static-поле)
 * и пишет в отчёт стабы и все запросы (GET, POST с телом, незаматченный → 404).
 * Смотреть: {@code mvn allure:serve}.
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
    @DisplayName("стабы и запросы к WireMock (GET, POST с телом, 404) попадают в отчёт")
    void wireMockExchangesAppearInReport() throws Exception {
        wireMock.stubFor(get(urlPathEqualTo("/api/prices"))
                .willReturn(okJson("{\"price\":9.99}")));
        wireMock.stubFor(post(urlPathEqualTo("/api/orders"))
                .willReturn(aResponse().withStatus(201)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":7,\"status\":\"CREATED\"}")));

        String base = "http://localhost:" + wireMock.port();
        HttpClient client = HttpClient.newHttpClient();

        HttpResponse<String> get = client.send(
                HttpRequest.newBuilder(URI.create(base + "/api/prices")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(get.statusCode()).isEqualTo(200);
        assertThat(get.body()).contains("price");

        HttpResponse<String> post = client.send(
                HttpRequest.newBuilder(URI.create(base + "/api/orders"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{\"productName\":\"laptop\"}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(post.statusCode()).isEqualTo(201);

        // незаматченный запрос — WireMock отдаёт 404, он тоже должен быть в отчёте
        HttpResponse<String> miss = client.send(
                HttpRequest.newBuilder(URI.create(base + "/api/does-not-exist")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(miss.statusCode()).isEqualTo(404);
    }
}
