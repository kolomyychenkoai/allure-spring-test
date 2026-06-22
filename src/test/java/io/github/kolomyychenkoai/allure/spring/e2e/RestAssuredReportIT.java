package io.github.kolomyychenkoai.allure.spring.e2e;

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

import static io.restassured.RestAssured.given;

/**
 * Уровень B: «живой» прогон на реальном порту. Никакой настройки Allure/фильтра в
 * тесте — фильтр ставится САМ (AllureRestAssuredListener через spring.factories).
 * Смотреть: {@code mvn allure:serve}.
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
    @DisplayName("RestAssured-вызовы (GET, POST, 404) автоматически попадают в отчёт")
    void restAssuredCallsAppearInReport() {
        given().when().get("/api/hello/{name}", "world").then().statusCode(200);

        given().contentType(ContentType.JSON).body("{\"productName\":\"laptop\"}")
                .when().post("/api/echo").then().statusCode(200);

        // негативный сценарий — в отчёте виден шаг с не-200
        given().when().get("/api/does-not-exist").then().statusCode(404);
    }
}
