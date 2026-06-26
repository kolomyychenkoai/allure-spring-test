package io.github.kolomyychenkoai.allure.spring.demo;

import io.github.kolomyychenkoai.allure.spring.support.CurrentReport;
import io.github.kolomyychenkoai.allure.spring.support.WebTestApp;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.util.List;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Уровень B: «живой» прогон на реальном порту через РЕАЛЬНУЮ авто-регистрацию фильтра
 * (AllureRestAssuredListener через spring.factories). HTTP-шаги пишутся в настоящий отчёт
 * (showcase); тест читает их через {@link CurrentReport}. Краснеет, если фильтр не подключился
 * или имя HTTP-шага съехало.
 */
@SpringBootTest(classes = WebTestApp.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Epic("allure-spring-test")
@Feature("HTTP-вызовы (RestAssured)")
class RestAssuredReportIT {

    @LocalServerPort
    int port;

    @BeforeEach
    void setUp() {
        RestAssured.port = port;
    }

    @Test
    @DisplayName("RestAssured-вызовы (GET, POST, 404) попадают в отчёт шагами")
    void restAssuredCallsAppearInReport() {
        given().when().get("/api/hello/{name}", "world").then().statusCode(200);
        given().contentType(ContentType.JSON).body("{\"productName\":\"laptop\"}")
                .when().post("/api/echo").then().statusCode(200);
        given().when().get("/api/does-not-exist").then().statusCode(404);

        List<String> steps = CurrentReport.stepNames();
        assertTrue(steps.contains("HTTP GET /api/hello/world → 200"), () -> "" + steps);
        assertTrue(steps.contains("HTTP POST /api/echo → 200"), () -> "" + steps);
        assertTrue(steps.contains("HTTP GET /api/does-not-exist → 404"), () -> "" + steps);

        // содержимое вложений пришло через реальную цепочку
        String req = CurrentReport.attachmentContent("HTTP Request").orElse("");
        assertTrue(req.contains("/api/hello/world"), () -> "HTTP Request без пути: " + req);
        String resp = CurrentReport.attachmentContent("HTTP Response").orElse("");
        assertTrue(resp.contains("world"), () -> "HTTP Response без тела: " + resp);
    }
}
