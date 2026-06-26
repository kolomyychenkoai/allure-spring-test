package io.github.kolomyychenkoai.allure.spring.unit;

import io.github.kolomyychenkoai.allure.spring.wiremock.internal.AllureWireMockListener;
import com.github.tomakehurst.wiremock.http.HttpHeaders;
import com.github.tomakehurst.wiremock.http.Request;
import com.github.tomakehurst.wiremock.http.RequestMethod;
import com.github.tomakehurst.wiremock.http.Response;
import io.github.kolomyychenkoai.allure.spring.support.InMemoryAllure;
import io.qameta.allure.model.TestResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Уровень A: детерминированная проверка содержимого отчёта для WireMock-листенера. */
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

    @Test
    @DisplayName("flush БЕЗ активного кейса чистит буфер — обмен не утекает в следующий тест")
    void bufferedExchangeDoesNotLeakWhenFlushedWithoutActiveCase() {
        // общий статический буфер изолируем от чужих записей прогона
        AllureWireMockListener.clear();
        try {
            Request req = mock(Request.class);
            when(req.getMethod()).thenReturn(RequestMethod.GET);
            when(req.getUrl()).thenReturn("/api/leak");
            when(req.getHeaders()).thenReturn(new HttpHeaders());
            when(req.getBodyAsString()).thenReturn("");
            Response resp = mock(Response.class);
            when(resp.getStatus()).thenReturn(200);
            when(resp.getHeaders()).thenReturn(new HttpHeaders());
            when(resp.getBodyAsString()).thenReturn("LEAK-MARKER");

            // приём на «чужом» Jetty-потоке (активного кейса нет) — обмен уходит в буфер, в отчёт пока ничего
            AllureWireMockListener.onRequestReceived(req, resp);
            assertThat(allure.wroteNothing()).isTrue();

            // flush БЕЗ активного кейса ОБЯЗАН очистить буфер (привязать обмен не к чему).
            // Мутация: убери clear() в ветке «нет кейса» во flushToAllure — обмен доживёт до след. теста.
            AllureWireMockListener.flushToAllure();

            // следующий тест-кейс: буфер уже пуст → ни шага обмена, ни маркера во вложении
            TestResult next = allure.run("next-test", AllureWireMockListener::flushToAllure);
            assertThat(allure.hasStep(next, "Запрос к заглушке: GET /api/leak → 200"))
                    .as("обмен из буфера не должен утечь в следующий тест")
                    .isFalse();
            assertThat(allure.attachment(next, "WireMock Response")).isEmpty();
        } finally {
            AllureWireMockListener.clear();
        }
    }
}
