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
    @DisplayName("GET через WebTestClient (с чтением тела) даёт HTTP-шаг")
    void webTestClientCallAppearsInReport() {
        client.get().uri("/api/hello/world").exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.greeting").isEqualTo("hello world");

        List<String> steps = CurrentReport.stepNames();
        assertTrue(steps.stream().anyMatch("HTTP GET /api/hello/world → 200"::equals),
                () -> "нет HTTP-шага WebTestClient: " + steps);
    }
}
