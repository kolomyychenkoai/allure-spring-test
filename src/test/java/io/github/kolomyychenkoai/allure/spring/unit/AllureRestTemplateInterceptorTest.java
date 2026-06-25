package io.github.kolomyychenkoai.allure.spring.unit;

import io.github.kolomyychenkoai.allure.spring.rest.internal.AllureRestTemplateInterceptor;
import io.github.kolomyychenkoai.allure.spring.support.InMemoryAllure;
import io.qameta.allure.model.TestResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.mock.http.client.MockClientHttpResponse;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.io.UncheckedIOException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Уровень A: логика интерсептора RestTemplate без поднятия Spring и без байткода —
 * зовём {@code intercept(...)} напрямую с фейковым execution. Дополняет уровень B
 * ({@code RestTemplateReportIT}) детерминированной проверкой шага/вложений и гейта активного кейса.
 */
class AllureRestTemplateInterceptorTest {

    private InMemoryAllure allure;

    @BeforeEach
    void setUp() {
        allure = new InMemoryAllure().install();
    }

    @AfterEach
    void tearDown() {
        allure.uninstall();
    }

    /** Лёгкий HttpRequest без Mockito (чтобы мок-вызовы не сыпали лишних шагов в отчёт). */
    private static HttpRequest request(HttpMethod method, String uri) {
        return new HttpRequest() {
            @Override
            public HttpMethod getMethod() {
                return method;
            }

            @Override
            public URI getURI() {
                return URI.create(uri);
            }

            @Override
            public HttpHeaders getHeaders() {
                return new HttpHeaders();
            }

            @Override
            public java.util.Map<String, Object> getAttributes() {
                return new java.util.HashMap<>();
            }
        };
    }

    private static ClientHttpRequestExecution responding(int status, String body) {
        return (req, b) -> {
            MockClientHttpResponse response = new MockClientHttpResponse(
                    body.getBytes(StandardCharsets.UTF_8), HttpStatus.valueOf(status));
            response.getHeaders().add("Content-Type", "application/json");
            return response;
        };
    }

    private void intercept(AllureRestTemplateInterceptor interceptor, HttpRequest request,
                           byte[] body, ClientHttpRequestExecution execution) {
        try {
            interceptor.intercept(request, body, execution);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    @DisplayName("GET даёт шаг «HTTP GET path → 200» с вложениями Request/Response")
    void getProducesStep() {
        AllureRestTemplateInterceptor interceptor = new AllureRestTemplateInterceptor();
        HttpRequest request = request(HttpMethod.GET, "http://localhost/api/ping?x=1");

        TestResult result = allure.run("rt-get", () ->
                intercept(interceptor, request, new byte[0], responding(200, "{\"pong\":true}")));

        assertThat(result.getSteps().stream().map(s -> s.getName()))
                .anyMatch(n -> n.startsWith("HTTP GET") && n.endsWith("/api/ping?x=1 → 200"));
        assertThat(allure.attachment(result, "HTTP Request").orElseThrow()).contains("GET ").contains("/api/ping");
        assertThat(allure.attachment(result, "HTTP Response").orElseThrow()).contains("pong");
    }

    @Test
    @DisplayName("тело POST-запроса попадает во вложение HTTP Request")
    void postBodyInRequestAttachment() {
        AllureRestTemplateInterceptor interceptor = new AllureRestTemplateInterceptor();
        HttpRequest request = request(HttpMethod.POST, "http://localhost/api/echo");

        TestResult result = allure.run("rt-post", () ->
                intercept(interceptor, request, "{\"productName\":\"laptop\"}".getBytes(StandardCharsets.UTF_8),
                        responding(200, "{\"productName\":\"laptop\"}")));

        assertThat(result.getSteps().stream().map(s -> s.getName()))
                .anyMatch(n -> n.startsWith("HTTP POST") && n.endsWith("/api/echo → 200"));
        assertThat(allure.attachment(result, "HTTP Request").orElseThrow()).contains("productName").contains("laptop");
    }

    @Test
    @DisplayName("без активного тест-кейса интерсептор в отчёт ничего не пишет (ответ не трогает)")
    void noStepWithoutActiveCase() {
        // setUp установил InMemoryAllure, но allure.run не вызывали → активного кейса нет
        AllureRestTemplateInterceptor interceptor = new AllureRestTemplateInterceptor();
        ClientHttpResponse response = interceptOutsideCase(interceptor);

        assertThat(response).isNotNull();
        assertThat(allure.wroteNothing()).isTrue(); // убери гейт active() → запишет вложения → покраснеет
    }

    private ClientHttpResponse interceptOutsideCase(AllureRestTemplateInterceptor interceptor) {
        try {
            return interceptor.intercept(request(HttpMethod.GET, "http://localhost/api/ping"),
                    new byte[0], responding(200, "{\"pong\":true}"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
