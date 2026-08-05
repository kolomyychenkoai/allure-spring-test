package io.github.kolomyychenkoai.allure.spring.demo;

import io.github.kolomyychenkoai.allure.spring.support.CurrentReport;
import io.github.kolomyychenkoai.allure.spring.support.WebTestApp;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

/**
 * Уровень B: вызовы {@code TestRestTemplate} попадают в отчёт через интерсептор,
 * навешенный байткодом на конструктор RestTemplate. Раньше этого клиента не было видно.
 * <p>
 * ⚠️ С Boot 4 {@code TestRestTemplate} больше НЕ подаётся в контекст сам по факту
 * {@code webEnvironment=RANDOM_PORT} — нужна {@code @AutoConfigureTestRestTemplate}.
 */
@AutoConfigureTestRestTemplate
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
        CurrentReport.check(steps.stream().anyMatch("HTTP GET /api/hello/world → 200"::equals),
                () -> "нет HTTP-шага TestRestTemplate: " + steps);

        String resp = CurrentReport.attachmentContent("HTTP Response Body").orElse("");
        CurrentReport.check(resp.contains("hello world"), () -> "HTTP Response Body без тела: " + resp);
    }

    @Test
    @DisplayName("POST с телом: и тело запроса, и тело ответа в отчёте")
    void restTemplatePostBodyAppearsInReport() {
        rest.postForEntity("/api/echo", java.util.Map.of("productName", "laptop"), String.class);

        List<String> steps = CurrentReport.stepNames();
        CurrentReport.check(steps.stream().anyMatch("HTTP POST /api/echo → 200"::equals),
                () -> "нет POST-шага TestRestTemplate: " + steps);

        String req = CurrentReport.attachmentContent("HTTP Request Body").orElse("");
        CurrentReport.check(req.contains("laptop"), () -> "тело POST-запроса не попало: " + req);
    }

    @Test
    @DisplayName("ошибочный статус (404) тоже даёт шаг (TestRestTemplate не бросает)")
    void restTemplateErrorStatusAppearsInReport() {
        rest.getForEntity("/api/does-not-exist", String.class);

        List<String> steps = CurrentReport.stepNames();
        CurrentReport.check(steps.stream().anyMatch("HTTP GET /api/does-not-exist → 404"::equals),
                () -> "нет шага для 404: " + steps);
    }
}
