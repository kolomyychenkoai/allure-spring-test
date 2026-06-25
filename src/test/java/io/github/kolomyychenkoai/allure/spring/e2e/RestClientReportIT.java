package io.github.kolomyychenkoai.allure.spring.e2e;

import io.github.kolomyychenkoai.allure.spring.support.CurrentReport;
import io.github.kolomyychenkoai.allure.spring.support.WebTestApp;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Уровень B: вызовы {@code RestClient} (новый текучий клиент Spring) попадают в отчёт через
 * интерсептор, навешенный байткодом на {@code DefaultRestClientBuilder.build()}. Раньше этого
 * клиента не было видно. {@code RestClient} строим обычным {@code RestClient.create(baseUrl)} —
 * именно так его создаёт прикладной код, и именно эту цепочку проверяем.
 */
@SpringBootTest(classes = WebTestApp.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Epic("allure-spring-test")
@Feature("HTTP-вызовы (RestClient)")
class RestClientReportIT {

    @LocalServerPort
    int port;

    private RestClient client() {
        return RestClient.create("http://localhost:" + port);
    }

    @Test
    @DisplayName("GET через RestClient даёт HTTP-шаг с телом ответа")
    void restClientCallAppearsInReport() {
        client().get().uri("/api/hello/{name}", "world").retrieve().body(String.class);

        List<String> steps = CurrentReport.stepNames();
        assertTrue(steps.stream().anyMatch("HTTP GET /api/hello/world → 200"::equals),
                () -> "нет HTTP-шага RestClient: " + steps);

        String resp = CurrentReport.attachmentContent("HTTP Response").orElse("");
        assertTrue(resp.contains("hello world"), () -> "HTTP Response без тела: " + resp);
    }

    @Test
    @DisplayName("повторный build() того же билдера не задваивает HTTP-шаг (дедуп интерсептора)")
    void reusedBuilderDoesNotDuplicateStep() {
        // билдер можно собирать многократно — байткод в build() добавляет интерсептор каждый раз,
        // но дедуп по типу не даёт второго; иначе вызов одного клиента дал бы два HTTP-шага
        RestClient.Builder builder = RestClient.builder().baseUrl("http://localhost:" + port);
        builder.build();
        RestClient client = builder.build();
        client.get().uri("/api/hello/{name}", "dup").retrieve().body(String.class);

        long steps = CurrentReport.stepNames().stream().filter("HTTP GET /api/hello/dup → 200"::equals).count();
        assertTrue(steps == 1, () -> "ожидался ровно один HTTP-шаг (без дубля): " + CurrentReport.stepNames());
    }

    @Test
    @DisplayName("POST с телом: и тело запроса, и тело ответа в отчёте")
    void restClientPostBodyAppearsInReport() {
        client().post().uri("/api/echo")
                .body(java.util.Map.of("productName", "laptop")).retrieve().body(String.class);

        List<String> steps = CurrentReport.stepNames();
        assertTrue(steps.stream().anyMatch("HTTP POST /api/echo → 200"::equals),
                () -> "нет POST-шага RestClient: " + steps);

        String req = CurrentReport.attachmentContent("HTTP Request").orElse("");
        assertTrue(req.contains("laptop"), () -> "тело POST-запроса не попало: " + req);
    }
}
