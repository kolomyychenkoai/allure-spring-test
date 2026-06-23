package io.github.kolomyychenkoai.allure.spring.e2e;

import io.github.kolomyychenkoai.allure.spring.support.CurrentReport;
import io.github.kolomyychenkoai.allure.spring.support.WebTestApp;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Уровень B: обмен через {@code WebTestClient} попадает в отчёт через
 * {@code WebTestClientBuilderCustomizer} + консьюмер результата. Раньше клиент был невидим.
 */
@SpringBootTest(classes = WebTestApp.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@Epic("allure-spring-test")
@Feature("HTTP-вызовы (WebTestClient)")
class WebTestClientReportIT {

    @Autowired
    WebTestClient client;

    @Test
    @DisplayName("GET через WebTestClient (с чтением тела) даёт HTTP-шаг с телом ответа")
    void webTestClientCallAppearsInReport() {
        client.get().uri("/api/hello/world").exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.greeting").isEqualTo("hello world");

        List<String> steps = CurrentReport.stepNames();
        assertTrue(steps.stream().anyMatch("HTTP GET /api/hello/world → 200"::equals),
                () -> "нет HTTP-шага WebTestClient: " + steps);

        String resp = CurrentReport.attachmentContent("HTTP Response").orElse("");
        assertTrue(resp.contains("hello world"), () -> "HTTP Response без тела: " + resp);
    }

    @Test
    @DisplayName("POST с телом: и тело запроса, и тело ответа в отчёте")
    void webTestClientPostBodyAppearsInReport() {
        client.post().uri("/api/echo")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .bodyValue(java.util.Map.of("productName", "laptop")).exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.productName").isEqualTo("laptop");

        List<String> steps = CurrentReport.stepNames();
        assertTrue(steps.stream().anyMatch("HTTP POST /api/echo → 200"::equals),
                () -> "нет POST-шага WebTestClient: " + steps);

        String req = CurrentReport.attachmentContent("HTTP Request").orElse("");
        assertTrue(req.contains("laptop"), () -> "тело POST-запроса не попало: " + req);
    }
}
