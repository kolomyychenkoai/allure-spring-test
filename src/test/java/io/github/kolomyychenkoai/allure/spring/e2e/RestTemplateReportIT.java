package io.github.kolomyychenkoai.allure.spring.e2e;

import io.github.kolomyychenkoai.allure.spring.support.CurrentReport;
import io.github.kolomyychenkoai.allure.spring.support.WebTestApp;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Уровень B: вызовы {@code TestRestTemplate} попадают в отчёт через интерсептор,
 * навешенный байткодом на конструктор RestTemplate. Раньше этого клиента не было видно.
 */
@SpringBootTest(classes = WebTestApp.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Epic("allure-spring-test")
@Feature("HTTP-вызовы (TestRestTemplate)")
class RestTemplateReportIT {

    @Autowired
    TestRestTemplate rest;

    @Test
    @DisplayName("GET через TestRestTemplate даёт HTTP-шаг с телом ответа")
    void restTemplateCallAppearsInReport() {
        rest.getForObject("/api/hello/{name}", String.class, "world");

        List<String> steps = CurrentReport.stepNames();
        assertTrue(steps.stream().anyMatch("HTTP GET /api/hello/world → 200"::equals),
                () -> "нет HTTP-шага TestRestTemplate: " + steps);

        String resp = CurrentReport.attachmentContent("HTTP Response").orElse("");
        assertTrue(resp.contains("hello world"), () -> "HTTP Response без тела: " + resp);
    }

    @Test
    @DisplayName("POST с телом: и тело запроса, и тело ответа в отчёте")
    void restTemplatePostBodyAppearsInReport() {
        rest.postForEntity("/api/echo", java.util.Map.of("productName", "laptop"), String.class);

        List<String> steps = CurrentReport.stepNames();
        assertTrue(steps.stream().anyMatch("HTTP POST /api/echo → 200"::equals),
                () -> "нет POST-шага TestRestTemplate: " + steps);

        String req = CurrentReport.attachmentContent("HTTP Request").orElse("");
        assertTrue(req.contains("laptop"), () -> "тело POST-запроса не попало: " + req);
    }

    @Test
    @DisplayName("ошибочный статус (404) тоже даёт шаг (TestRestTemplate не бросает)")
    void restTemplateErrorStatusAppearsInReport() {
        rest.getForEntity("/api/does-not-exist", String.class);

        List<String> steps = CurrentReport.stepNames();
        assertTrue(steps.stream().anyMatch("HTTP GET /api/does-not-exist → 404"::equals),
                () -> "нет шага для 404: " + steps);
    }
}
