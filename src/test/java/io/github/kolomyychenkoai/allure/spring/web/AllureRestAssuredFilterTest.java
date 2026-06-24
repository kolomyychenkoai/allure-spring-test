package io.github.kolomyychenkoai.allure.spring.web;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.kolomyychenkoai.allure.spring.support.InMemoryAllure;
import io.qameta.allure.model.TestResult;
import io.restassured.RestAssured;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Уровень A: детерминированная проверка содержимого отчёта для RestAssured-фильтра.
 * Поднимаем лёгкий in-process HttpServer (JDK) и применяем фильтр per-request —
 * без Spring и без глобального состояния RestAssured.
 */
class AllureRestAssuredFilterTest {

    private InMemoryAllure allure;
    private HttpServer server;
    private String base;

    @BeforeEach
    void setUp() throws IOException {
        allure = new InMemoryAllure().install();
        RestAssured.reset(); // изоляция от возможного глобального фильтра
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/ping", ex -> respond(ex, 200, "{\"pong\":true}"));
        server.createContext("/echo", ex -> {
            byte[] in = ex.getRequestBody().readAllBytes();
            respond(ex, 200, new String(in, StandardCharsets.UTF_8));
        });
        server.start();
        base = "http://localhost:" + server.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
        RestAssured.reset();
        allure.uninstall();
    }

    private static void respond(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    @Test
    @DisplayName("GET через RestAssured даёт шаг «HTTP GET … → 200» с вложениями")
    void getProducesStep() {
        TestResult result = allure.run("ra-get", () ->
                given().filter(new AllureRestAssuredFilter())
                        .when().get(base + "/ping")
                        .then().statusCode(200));

        assertThat(result.getSteps().stream().map(s -> s.getName()))
                .anyMatch(n -> n.startsWith("HTTP GET") && n.endsWith("/ping → 200"));
        assertThat(allure.attachment(result, "HTTP Request").orElseThrow()).contains("GET ").contains("/ping");
        assertThat(allure.attachment(result, "HTTP Response").orElseThrow()).contains("pong");
    }

    @Test
    @DisplayName("POST: тело запроса и ответа попадают во вложения")
    void postProducesBodies() {
        TestResult result = allure.run("ra-post", () ->
                given().filter(new AllureRestAssuredFilter())
                        .contentType("application/json")
                        .body("{\"productName\":\"laptop\"}")
                        .when().post(base + "/echo")
                        .then().statusCode(200));

        assertThat(result.getSteps().stream().map(s -> s.getName()))
                .anyMatch(n -> n.startsWith("HTTP POST") && n.endsWith("/echo → 200"));
        assertThat(allure.attachment(result, "HTTP Request").orElseThrow()).contains("productName");
        assertThat(allure.attachment(result, "HTTP Response").orElseThrow()).contains("productName");
    }

    @Test
    @DisplayName("кастомный заголовок запроса попадает во вложение")
    void includesRequestHeader() {
        TestResult result = allure.run("ra-header", () ->
                given().filter(new AllureRestAssuredFilter())
                        .header("X-Request-Id", "abc-123")
                        .when().get(base + "/ping")
                        .then().statusCode(200));

        assertThat(allure.attachment(result, "HTTP Request").orElseThrow())
                .contains("X-Request-Id: abc-123");
    }

    @Test
    @DisplayName("query-string попадает в имя шага и в request-вложение")
    void includesQueryString() {
        TestResult result = allure.run("ra-query", () ->
                given().filter(new AllureRestAssuredFilter())
                        .when().get(base + "/ping?x=1")
                        .then().statusCode(200));

        assertThat(result.getSteps().stream().map(s -> s.getName()))
                .anyMatch(n -> n.contains("/ping?x=1 → 200"));
        assertThat(allure.attachment(result, "HTTP Request").orElseThrow()).contains("x=1");
    }

    @Test
    @DisplayName("без активного тест-кейса фильтр не пишет шаг и не роняет вызов")
    void noStepWithoutActiveTestCase() {
        // вне allure.run(...) активного Allure тест-кейса нет → фильтр должен тихо пропустить
        io.restassured.response.Response response = given()
                .filter(new AllureRestAssuredFilter())
                .when().get(base + "/ping");

        assertThat(response.statusCode()).isEqualTo(200); // HTTP-вызов не сломан…
        assertThat(allure.wroteNothing()).isTrue();       // …и в отчёт ничего не ушло (убери гейт → запишет вложения → покраснеет)
    }

    @Test
    @DisplayName("PUT и DELETE тоже дают шаги (перехват не зависит от метода)")
    void includesPutAndDelete() {
        TestResult put = allure.run("ra-put", () ->
                given().filter(new AllureRestAssuredFilter())
                        .contentType("application/json").body("{\"x\":1}")
                        .when().put(base + "/echo")
                        .then().statusCode(200));
        assertThat(put.getSteps().stream().map(s -> s.getName()))
                .anyMatch(n -> n.startsWith("HTTP PUT") && n.endsWith("/echo → 200"));

        TestResult delete = allure.run("ra-del", () ->
                given().filter(new AllureRestAssuredFilter())
                        .when().delete(base + "/ping")
                        .then().statusCode(200));
        assertThat(delete.getSteps().stream().map(s -> s.getName()))
                .anyMatch(n -> n.startsWith("HTTP DELETE") && n.endsWith("/ping → 200"));
    }

    @Test
    @DisplayName("не-200 (404) отражается в имени шага")
    void includesNon200Status() {
        TestResult result = allure.run("ra-404", () ->
                given().filter(new AllureRestAssuredFilter())
                        .when().get(base + "/missing")
                        .then().statusCode(404));

        assertThat(result.getSteps().stream().map(s -> s.getName()))
                .anyMatch(n -> n.startsWith("HTTP GET") && n.endsWith("/missing → 404"));
    }
}
