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
import static org.hamcrest.Matchers.lessThan;

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
        // body(String, ResponseAwareMatcher) само-делегирует в body(String,Matcher,Object[]) того же
        // класса — обёртку сами НЕ логируем, её значение пишет внутренний plain-вызов: РОВНО 1 ЧИСТЫЙ
        // шаг «тело query "dq42"» (а не мусорный toString лямбды). Снять RAM-skip → 2 шага → RED
        given().queryParam("q", "dq42").when().get("/api/search").then()
                .body("query", (ResponseAwareMatcher<Response>) r -> equalTo("dq42"));
        // time(Matcher) само-делегирует в time(Matcher, TimeUnit) того же класса — счётчик глубины
        // должен оставить РОВНО 1 шаг (внешний, без единицы); снять счётчик → 2 → RED
        given().when().get("/api/hello/{name}", "world").then().time(lessThan(600000L));
        // log-вариант того же имени (body() без аргументов) — это ЛОГ, не проверка: шага «Проверка …» не даёт
        given().when().get("/api/hello/{name}", "world").then().log().body();

        List<String> steps = CurrentReport.stepNames();
        CurrentReport.assertStep("HTTP GET /api/hello/world → 200");
        CurrentReport.assertStep("HTTP POST /api/echo → 200");
        CurrentReport.assertStep("HTTP GET /api/does-not-exist → 404");

        // проверки .then() тоже попали в отчёт шагами (bytecode-перехват RestAssured-валидации)
        CurrentReport.assertStep("Проверка ответа: статус 200");
        CurrentReport.assertStep("Проверка ответа: статус 404");
        // не-statusCode/body метод (contentType) ловится через реальную .then()-цепочку
        CurrentReport.check(steps.stream().anyMatch(n -> n.startsWith("Проверка ответа: тип содержимого")), () -> "" + steps);
        // ТОЧНОЕ имя (не startsWith): ловит и задвоение, и мусор в значениях (напр. хвостовой [])
        long bodyChecks = steps.stream().filter(n -> n.equals("Проверка ответа: тело productName \"laptop\"")).count();
        CurrentReport.check(bodyChecks == 1, () -> "ожидался 1 чистый шаг проверки тела, а их " + bodyChecks + ": " + steps);
        // ResponseAwareMatcher: РОВНО 1 шаг (обёртка не логируется, пишет внутренний plain-вызов)
        // И имя ЧИСТОЕ — разрешённый матчер «"dq42"», а не toString лямбды (мутация «снять RAM-skip»
        // → 2 шага, второй с мусорной лямбдой → RED)
        long queryChecks = steps.stream().filter(n -> n.startsWith("Проверка ответа: тело query")).count();
        CurrentReport.check(queryChecks == 1, () -> "ResponseAwareMatcher: ожидался 1 шаг, а их " + queryChecks + ": " + steps);
        CurrentReport.assertStep("Проверка ответа: тело query \"dq42\"");
        // time(Matcher) → РОВНО 1 шаг (внутренний делегат time(Matcher,TimeUnit) погашен счётчиком глубины)
        long timeChecks = steps.stream().filter(n -> n.startsWith("Проверка ответа: время ответа")).count();
        CurrentReport.check(timeChecks == 1, () -> "time: ожидался 1 шаг, а их " + timeChecks + ": " + steps);
        // .log().body() (лог, не проверка) не должен породить пустой шаг «Проверка ответа: тело»
        CurrentReport.check(steps.stream().noneMatch(n -> n.equals("Проверка ответа: тело")),
                () -> "log().body() протёк шагом проверки: " + steps);

        // содержимое вложений пришло через реальную цепочку
        String req = CurrentReport.attachmentContent("HTTP Request").orElse("");
        CurrentReport.check(req.contains("/api/hello/world"), () -> "HTTP Request без пути: " + req);
        // тело переехало в ОТДЕЛЬНОЕ вложение «HTTP Response Body» типа application/json
        String resp = CurrentReport.attachmentContent("HTTP Response Body").orElse("");
        CurrentReport.check(resp.contains("world"), () -> "HTTP Response Body без тела: " + resp);
        String respType = CurrentReport.attachmentType("HTTP Response Body").orElse("");
        CurrentReport.check(respType.equals("application/json"),
                () -> "HTTP Response Body должно быть application/json, а было: " + respType);
    }
}
