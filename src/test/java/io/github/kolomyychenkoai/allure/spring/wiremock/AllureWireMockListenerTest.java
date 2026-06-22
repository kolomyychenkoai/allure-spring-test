package io.github.kolomyychenkoai.allure.spring.wiremock;

import com.github.tomakehurst.wiremock.http.HttpHeaders;
import com.github.tomakehurst.wiremock.http.Request;
import com.github.tomakehurst.wiremock.http.RequestMethod;
import com.github.tomakehurst.wiremock.http.Response;
import io.github.kolomyychenkoai.allure.spring.support.InMemoryAllure;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.model.TestResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Уровень A: детерминированная проверка содержимого отчёта для WireMock-листенера. */
@Epic("allure-spring-test")
@Feature("WireMock (заглушки внешних сервисов)")
class AllureWireMockListenerTest {

    private InMemoryAllure allure;

    @BeforeEach
    void setUp() {
        allure = new InMemoryAllure().install();
        AllureWireMockListener.clear();
    }

    @AfterEach
    void tearDown() {
        AllureWireMockListener.clear();
        allure.uninstall();
    }

    @Test
    @DisplayName("запрос к заглушке даёт шаг «WireMock request …» с request/response")
    void logsRequestAndResponse() {
        Request req = mock(Request.class);
        when(req.getMethod()).thenReturn(RequestMethod.GET);
        when(req.getUrl()).thenReturn("/api/prices");
        when(req.getHeaders()).thenReturn(new HttpHeaders());
        when(req.getBodyAsString()).thenReturn("");

        Response resp = mock(Response.class);
        when(resp.getStatus()).thenReturn(200);
        when(resp.getHeaders()).thenReturn(new HttpHeaders());
        when(resp.getBodyAsString()).thenReturn("{\"price\":9.99}");

        TestResult result = allure.run("wm", () -> {
            AllureWireMockListener.onRequestReceived(req, resp);
            AllureWireMockListener.flushToAllure();
        });

        assertThat(allure.hasStep(result, "Запрос к заглушке: GET /api/prices → 200")).isTrue();
        assertThat(allure.attachment(result, "WireMock Request").orElseThrow())
                .contains("GET /api/prices");
        assertThat(allure.attachment(result, "WireMock Response").orElseThrow())
                .contains("price");
    }
}
