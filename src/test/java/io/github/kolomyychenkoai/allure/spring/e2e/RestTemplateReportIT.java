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
}
