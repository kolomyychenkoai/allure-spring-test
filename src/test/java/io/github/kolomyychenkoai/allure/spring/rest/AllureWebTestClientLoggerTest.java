package io.github.kolomyychenkoai.allure.spring.rest;

import io.github.kolomyychenkoai.allure.spring.rest.internal.AllureWebTestClientLogger;
import io.github.kolomyychenkoai.allure.spring.support.InMemoryAllure;
import io.qameta.allure.model.TestResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.reactive.server.EntityExchangeResult;

import java.net.URI;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/**
 * Уровень A: логика логгера WebTestClient без поднятия Spring-контекста — собираем
 * {@code EntityExchangeResult} (webflux на classpath нет, поэтому мокаем результат обмена) и
 * зовём {@code log(result)} напрямую. Дополняет уровень B ({@code WebTestClientReportIT})
 * детерминированной проверкой шага/вложений и гейта активного кейса.
 */
class AllureWebTestClientLoggerTest {

    private InMemoryAllure allure;

    @BeforeEach
    void setUp() {
        allure = new InMemoryAllure().install();
    }

    @AfterEach
    void tearDown() {
        allure.uninstall();
    }

    private static EntityExchangeResult<byte[]> result() {
        HttpHeaders responseHeaders = new HttpHeaders();
        responseHeaders.add("Content-Type", "application/json");

        @SuppressWarnings("unchecked")
        EntityExchangeResult<byte[]> result = mock(EntityExchangeResult.class);
        lenient().when(result.getMethod()).thenReturn(HttpMethod.GET);
        lenient().when(result.getUrl()).thenReturn(URI.create("http://localhost/api/hello/world"));
        lenient().when(result.getStatus()).thenReturn(HttpStatus.OK);
        lenient().when(result.getRequestHeaders()).thenReturn(new HttpHeaders());
        lenient().when(result.getResponseHeaders()).thenReturn(responseHeaders);
        lenient().when(result.getRequestBodyContent()).thenReturn(new byte[0]);
        lenient().when(result.getResponseBodyContent())
                .thenReturn("{\"greeting\":\"hello world\"}".getBytes(StandardCharsets.UTF_8));
        return result;
    }

    @Test
    @DisplayName("обмен даёт шаг «HTTP GET path → 200» с телом ответа во вложении")
    void logsExchange() {
        EntityExchangeResult<byte[]> result = result();

        TestResult report = allure.run("wtc", () -> AllureWebTestClientLogger.log(result));

        assertThat(report.getSteps().stream().map(s -> s.getName()))
                .anyMatch(n -> n.startsWith("HTTP GET") && n.endsWith("/api/hello/world → 200"));
        assertThat(allure.attachment(report, "HTTP Response").orElseThrow()).contains("hello world");
    }

    @Test
    @DisplayName("без активного тест-кейса логгер в отчёт ничего не пишет")
    void noStepWithoutActiveCase() {
        // вне allure.run(...) активного кейса нет → log должен тихо вернуться
        AllureWebTestClientLogger.log(result());

        assertThat(allure.wroteNothing()).isTrue(); // убери гейт isPresent() → запишет вложения → покраснеет
    }
}
