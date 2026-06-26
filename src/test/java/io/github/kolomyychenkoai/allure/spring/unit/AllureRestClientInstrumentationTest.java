package io.github.kolomyychenkoai.allure.spring.unit;

import io.github.kolomyychenkoai.allure.spring.support.InMemoryAllure;
import io.github.kolomyychenkoai.allure.spring.rest.internal.AllureRestClientInstrumentation;
import io.github.kolomyychenkoai.allure.spring.rest.internal.AllureRestTemplateInterceptor;
import io.qameta.allure.model.TestResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Уровень A: проверяем установку перехвата RestClient без байткода — зовём {@code onBuild(...)}
 * напрямую (как это сделал бы advice в {@code build()}) и гоняем настоящий {@code RestClient}
 * с фейковой фабрикой запросов. Дополняет уровень B ({@code RestClientReportIT})
 * детерминированной проверкой шага/вложений, дедупа и гейта активного кейса.
 */
class AllureRestClientInstrumentationTest {

    private InMemoryAllure allure;

    @BeforeEach
    void setUp() {
        allure = new InMemoryAllure().install();
    }

    @AfterEach
    void tearDown() {
        allure.uninstall();
    }

    /** Фабрика, отдающая канонический ответ без сети (на каждый запрос — заданные статус/тело). */
    private static ClientHttpRequestFactory responding(int status, String body) {
        return (uri, method) -> {
            MockClientHttpRequest request = new MockClientHttpRequest(method, uri);
            MockClientHttpResponse response = new MockClientHttpResponse(
                    body.getBytes(StandardCharsets.UTF_8), HttpStatus.valueOf(status));
            response.getHeaders().add("Content-Type", "application/json");
            request.setResponse(response);
            return request;
        };
    }

    /** RestClient с навешенным (через onBuild) перехватом и фейковой фабрикой. */
    private static RestClient instrumented(int status, String body) {
        RestClient.Builder builder = RestClient.builder().requestFactory(responding(status, body));
        AllureRestClientInstrumentation.onBuild(builder); // имитируем то, что делает advice в build()
        return builder.build();
    }

    @Test
    @DisplayName("GET даёт шаг «HTTP GET path → 200» с вложениями Request/Response")
    void getProducesStep() {
        RestClient client = instrumented(200, "{\"pong\":true}");

        TestResult result = allure.run("rc-get", () ->
                client.get().uri("http://localhost/api/ping?x=1").retrieve().body(String.class));

        assertThat(result.getSteps().stream().map(s -> s.getName()))
                .anyMatch(n -> n.startsWith("HTTP GET") && n.endsWith("/api/ping?x=1 → 200"));
        assertThat(allure.attachment(result, "HTTP Request").orElseThrow()).contains("GET ").contains("/api/ping");
        assertThat(allure.attachment(result, "HTTP Response").orElseThrow()).contains("pong");
    }

    @Test
    @DisplayName("тело POST-запроса попадает во вложение HTTP Request")
    void postBodyInRequestAttachment() {
        RestClient client = instrumented(200, "{\"productName\":\"laptop\"}");

        TestResult result = allure.run("rc-post", () ->
                client.post().uri("http://localhost/api/echo")
                        .body("{\"productName\":\"laptop\"}").retrieve().body(String.class));

        assertThat(result.getSteps().stream().map(s -> s.getName()))
                .anyMatch(n -> n.startsWith("HTTP POST") && n.endsWith("/api/echo → 200"));
        assertThat(allure.attachment(result, "HTTP Request").orElseThrow()).contains("productName").contains("laptop");
    }

    @Test
    @DisplayName("onBuild не плодит дублей перехватчика при повторном вызове")
    void onBuildIsIdempotentPerBuilder() {
        RestClient.Builder builder = RestClient.builder();
        AllureRestClientInstrumentation.onBuild(builder);
        AllureRestClientInstrumentation.onBuild(builder); // второй раз — не должен добавить второй интерсептор

        List<ClientHttpRequestInterceptor> seen = new ArrayList<>();
        builder.requestInterceptors(seen::addAll);
        long ours = seen.stream().filter(i -> i instanceof AllureRestTemplateInterceptor).count();
        assertThat(ours).isEqualTo(1);
    }

    @Test
    @DisplayName("ошибочный статус (404) тоже даёт шаг (RestClient бросает после записи шага)")
    void errorStatusProducesStep() {
        RestClient client = instrumented(404, "{\"error\":\"nope\"}");

        TestResult result = allure.run("rc-404", () -> {
            try {
                client.get().uri("http://localhost/api/missing").retrieve().body(String.class);
            } catch (RuntimeException expected) {
                // RestClient по умолчанию бросает на 4xx — но интерсептор записал шаг ДО этого
            }
        });

        assertThat(result.getSteps().stream().map(s -> s.getName()))
                .anyMatch(n -> n.startsWith("HTTP GET") && n.endsWith("/api/missing → 404"));
    }

    @Test
    @DisplayName("onBuild на null/чужом типе не бросает (instanceof-гард)")
    void onBuildIsNullSafe() {
        assertThatCode(() -> {
            AllureRestClientInstrumentation.onBuild(null);
            AllureRestClientInstrumentation.onBuild("не билдер");
        }).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("без активного тест-кейса вызов RestClient в отчёт ничего не пишет")
    void noStepWithoutActiveCase() {
        // гейт active() живёт в общем AllureRestTemplateInterceptor; здесь проверяем, что и через
        // путь RestClient (onBuild → интерсептор в build()) вне активного кейса в отчёт ничего не идёт
        RestClient client = instrumented(200, "{\"pong\":true}");
        String body = client.get().uri("http://localhost/api/ping").retrieve().body(String.class);

        assertThat(body).contains("pong");
        assertThat(allure.wroteNothing()).isTrue();
    }
}
