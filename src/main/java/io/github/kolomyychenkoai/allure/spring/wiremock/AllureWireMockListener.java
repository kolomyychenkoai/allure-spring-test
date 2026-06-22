package io.github.kolomyychenkoai.allure.spring.wiremock;

import com.github.tomakehurst.wiremock.http.HttpHeaders;
import com.github.tomakehurst.wiremock.http.Request;
import com.github.tomakehurst.wiremock.http.Response;
import io.qameta.allure.Allure;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Собирает каждый запрос, попавший в WireMock, и выкладывает их как шаги Allure
 * на тест-потоке (через {@link AllureWireMockTestListener#afterTestMethod}).
 * <p>
 * WireMock вызывает листенер на своём Jetty-потоке, где активного Allure-тест-кейса
 * нет — поэтому буферизуем и проигрываем на правильном потоке.
 * Шаг «WireMock request METHOD url → status» + вложения «WireMock Request/Response».
 * <p>
 * Буфер — общий статический; модуль рассчитан на ПОСЛЕДОВАТЕЛЬНЫЙ прогон тест-классов.
 * При параллельном запуске обмены разных тестов могут перемешаться.
 */
public final class AllureWireMockListener {

    private static final Queue<CapturedExchange> EXCHANGES = new ConcurrentLinkedQueue<>();

    private AllureWireMockListener() {
    }

    public static void onRequestReceived(Request request, Response response) {
        try {
            EXCHANGES.add(new CapturedExchange(
                    request.getMethod().getName(),
                    request.getUrl(),
                    response.getStatus(),
                    formatRequest(request),
                    formatResponse(response)));
        } catch (Throwable ignored) {
            // инструментирование не должно ронять тест
        }
    }

    /** Вызывается из {@link AllureWireMockTestListener#afterTestMethod} на тест-потоке. */
    static void flushToAllure() {
        if (!Allure.getLifecycle().getCurrentTestCase().isPresent()) {
            clear();
            return;
        }
        CapturedExchange exchange;
        while ((exchange = EXCHANGES.poll()) != null) {
            String stepName = "Запрос к заглушке: " + exchange.method() + " " + exchange.url()
                    + " → " + exchange.status();
            String req = exchange.requestDetails();
            String resp = exchange.responseDetails();
            try {
                Allure.step(stepName, () -> {
                    Allure.addAttachment("WireMock Request", "text/plain", req);
                    Allure.addAttachment("WireMock Response", "text/plain", resp);
                });
            } catch (Throwable ignored) {
                // не роняем тест
            }
        }
    }

    /** Чистит буфер между тестами. */
    static void clear() {
        EXCHANGES.clear();
    }

    private static String formatRequest(Request request) {
        StringBuilder sb = new StringBuilder();
        sb.append(request.getMethod()).append(' ').append(request.getUrl()).append('\n');
        appendHeaders(sb, request.getHeaders());
        String body = request.getBodyAsString();
        if (body != null && !body.isEmpty()) {
            sb.append('\n').append(body);
        }
        return sb.toString();
    }

    private static String formatResponse(Response response) {
        StringBuilder sb = new StringBuilder();
        sb.append(response.getStatus()).append('\n');
        appendHeaders(sb, response.getHeaders());
        String body = response.getBodyAsString();
        if (body != null && !body.isEmpty()) {
            sb.append('\n').append(body);
        }
        return sb.toString();
    }

    private static void appendHeaders(StringBuilder sb, HttpHeaders headers) {
        // выводим ВСЕ значения заголовка (multi-value: Set-Cookie, Cache-Control и т.п.)
        headers.all().forEach(h ->
                h.values().forEach(v -> sb.append(h.key()).append(": ").append(v).append('\n')));
    }

    private record CapturedExchange(String method, String url, int status,
                                    String requestDetails, String responseDetails) {
    }
}
