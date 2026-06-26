package io.github.kolomyychenkoai.allure.spring.rest.internal;

import io.github.kolomyychenkoai.allure.spring.internal.AllureInstrumentationLogger;
import io.qameta.allure.Allure;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Логирует вызовы {@code RestTemplate}/{@code TestRestTemplate} в Allure-отчёт шагом
 * «HTTP METHOD path → status» с вложениями «HTTP Request»/«HTTP Response» — единообразно с
 * MockMvc и RestAssured. Ставится на каждый RestTemplate байткодом
 * ({@link AllureRestTemplateInstrumentation}), код в тестах не нужен.
 * <p>
 * Тело ответа буферизуется ({@link BufferedClientHttpResponse}), чтобы прочитать его для
 * отчёта и не «съесть» у вызывающего. Всё под проверкой активного тест-кейса и в try/catch —
 * инструментирование не роняет тест.
 */
public class AllureRestTemplateInterceptor implements ClientHttpRequestInterceptor {

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body,
                                        ClientHttpRequestExecution execution) throws IOException {
        ClientHttpResponse response = execution.execute(request, body);
        if (!active()) {
            return response; // вне теста ничего не трогаем — отдаём оригинал как есть
        }
        try {
            byte[] responseBody = response.getBody().readAllBytes();
            BufferedClientHttpResponse buffered = new BufferedClientHttpResponse(response, responseBody);
            logStep(request, body, buffered, responseBody);
            return buffered; // вызывающий читает тело из буфера, ничего не потеряно
        } catch (Throwable t) {
            AllureInstrumentationLogger.warn("RestTemplate", t);
            return response;
        }
    }

    private static boolean active() {
        return Allure.getLifecycle().getCurrentTestCase().isPresent();
    }

    private void logStep(HttpRequest request, byte[] reqBody,
                         ClientHttpResponse response, byte[] respBody) throws IOException {
        String method = request.getMethod().name();
        String url = request.getURI().toString();
        HttpStatusCode status = response.getStatusCode();
        String stepName = AllureHttp.stepName(method, AllureHttp.pathAndQuery(url), status.value());

        final String reqText = format(method + " " + url, request.getHeaders(), reqBody);
        final String respText = format(String.valueOf(status.value()), response.getHeaders(), respBody);
        Allure.step(stepName, step -> {
            Allure.addAttachment("HTTP Request", "text/plain", reqText);
            Allure.addAttachment("HTTP Response", "text/plain", respText);
        });
    }

    private static String format(String firstLine, Map<String, List<String>> headers, byte[] body) {
        StringBuilder sb = new StringBuilder(firstLine).append('\n');
        if (headers != null) {
            headers.forEach((name, values) -> values.forEach(v -> sb.append(name).append(": ").append(v).append('\n')));
        }
        if (body != null && body.length > 0) {
            sb.append('\n').append(new String(body, StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    /** Обёртка ответа с буферизованным телом: статус/заголовки делегируем, тело отдаём из байтов. */
    private static final class BufferedClientHttpResponse implements ClientHttpResponse {

        private final ClientHttpResponse delegate;
        private final byte[] body;

        BufferedClientHttpResponse(ClientHttpResponse delegate, byte[] body) {
            this.delegate = delegate;
            this.body = body;
        }

        @Override
        public HttpStatusCode getStatusCode() throws IOException {
            return delegate.getStatusCode();
        }

        @Override
        public String getStatusText() throws IOException {
            return delegate.getStatusText();
        }

        @Override
        public org.springframework.http.HttpHeaders getHeaders() {
            return delegate.getHeaders();
        }

        @Override
        public InputStream getBody() {
            return new ByteArrayInputStream(body);
        }

        @Override
        public void close() {
            delegate.close();
        }
    }
}
