package io.github.kolomyychenkoai.allure.spring.demo;

import io.github.kolomyychenkoai.allure.spring.support.CurrentReport;
import io.github.kolomyychenkoai.allure.spring.support.WebTestApp;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import io.restassured.matcher.ResponseAwareMatcher;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
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
        // + contentType: не-statusCode/body проверочный метод — доказать, что матчер ловит и его
        // через РЕАЛЬНУЮ .then()-цепочку (а не только на уровне A)
        given().when().get("/api/hello/{name}", "world").then().statusCode(200).contentType(ContentType.JSON);
        given().contentType(ContentType.JSON).body("{\"productName\":\"laptop\"}")
                .when().post("/api/echo").then().statusCode(200).body("productName", equalTo("laptop"));
        given().when().get("/api/does-not-exist").then().statusCode(404);
        // перегрузка body(String, ResponseAwareMatcher) САМО-делегирует в body(String,Matcher,Object[])
        // ТОГО ЖЕ класса (DEPTH=2) — дедуп глубиной должен оставить РОВНО 1 шаг (снять гард → 2 → RED)
        given().queryParam("q", "dq42").when().get("/api/search").then()
                .body("query", (ResponseAwareMatcher<Response>) r -> equalTo("dq42"));
        // log-вариант того же имени (body() без аргументов) — это ЛОГ, не проверка: шага «Проверка …» не даёт
        given().when().get("/api/hello/{name}", "world").then().log().body();

        List<String> steps = CurrentReport.stepNames();
        assertTrue(steps.contains("HTTP GET /api/hello/world → 200"), () -> "" + steps);
        assertTrue(steps.contains("HTTP POST /api/echo → 200"), () -> "" + steps);
        assertTrue(steps.contains("HTTP GET /api/does-not-exist → 404"), () -> "" + steps);

        // проверки .then() тоже попали в отчёт шагами (bytecode-перехват RestAssured-валидации)
        assertTrue(steps.contains("Проверка ответа: статус 200"), () -> "" + steps);
        assertTrue(steps.contains("Проверка ответа: статус 404"), () -> "" + steps);
        // не-statusCode/body метод (contentType) ловится через реальную .then()-цепочку
        assertTrue(steps.stream().anyMatch(n -> n.startsWith("Проверка ответа: тип содержимого")), () -> "" + steps);
        // ТОЧНОЕ имя (не startsWith): ловит и задвоение, и мусор в значениях (напр. хвостовой [])
        long bodyChecks = steps.stream().filter(n -> n.equals("Проверка ответа: тело productName \"laptop\"")).count();
        assertTrue(bodyChecks == 1, () -> "ожидался 1 чистый шаг проверки тела, а их " + bodyChecks + ": " + steps);
        // само-делегирующая перегрузка (ResponseAwareMatcher) → РОВНО 1 шаг (иначе гард глубины не работает)
        long rawBodyChecks = steps.stream().filter(n -> n.startsWith("Проверка ответа: тело query")).count();
        assertTrue(rawBodyChecks == 1, () -> "ResponseAwareMatcher: ожидался 1 шаг, а их " + rawBodyChecks + ": " + steps);
        // .log().body() (лог, не проверка) не должен породить пустой шаг «Проверка ответа: тело»
        assertTrue(steps.stream().noneMatch(n -> n.equals("Проверка ответа: тело")),
                () -> "log().body() протёк шагом проверки: " + steps);

        // содержимое вложений пришло через реальную цепочку
        String req = CurrentReport.attachmentContent("HTTP Request").orElse("");
        assertTrue(req.contains("/api/hello/world"), () -> "HTTP Request без пути: " + req);
        String resp = CurrentReport.attachmentContent("HTTP Response").orElse("");
        assertTrue(resp.contains("world"), () -> "HTTP Response без тела: " + resp);
    }
}
