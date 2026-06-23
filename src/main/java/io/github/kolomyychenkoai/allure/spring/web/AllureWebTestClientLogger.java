package io.github.kolomyychenkoai.allure.spring.web;

import io.github.kolomyychenkoai.allure.spring.internal.AllureInstrumentationLogger;
import io.qameta.allure.Allure;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.test.web.reactive.server.EntityExchangeResult;

import java.nio.charset.StandardCharsets;

/**
 * Логирует обмен {@code WebTestClient} в Allure-отчёт шагом «HTTP METHOD path → status» с
 * вложениями «HTTP Request»/«HTTP Response» — единообразно с MockMvc/RestAssured/RestTemplate.
 * Подключается через {@link AllureWebTestClientAutoConfiguration} (builder-кастомайзер
 * Spring Boot), код в тестах не нужен. Всё под проверкой активного тест-кейса и в try/catch.
 * <p>
 * Шаг пишется на каждый {@code EntityExchangeResult} — он создаётся, когда тест читает тело
 * ({@code expectBody()/returnResult()/...}). Чисто статусные проверки без чтения тела
 * результат не создают — для них шага не будет (особенность WebTestClient).
 */
public final class AllureWebTestClientLogger {

    private AllureWebTestClientLogger() {
    }

    public static void log(EntityExchangeResult<?> result) {
        try {
            if (!Allure.getLifecycle().getCurrentTestCase().isPresent() || result == null) {
                return;
            }
            String method = result.getMethod().name();
            String url = result.getUrl().toString();
            HttpStatusCode status = result.getStatus();
            String stepName = AllureHttp.stepName(method, AllureHttp.pathAndQuery(url), status.value());

            final String reqText = format(method + " " + url, result.getRequestHeaders(), result.getRequestBodyContent());
            final String respText = format(String.valueOf(status.value()),
                    result.getResponseHeaders(), result.getResponseBodyContent());
            Allure.step(stepName, step -> {
                Allure.addAttachment("HTTP Request", "text/plain", reqText);
                Allure.addAttachment("HTTP Response", "text/plain", respText);
            });
        } catch (Throwable t) {
            AllureInstrumentationLogger.warn("WebTestClient", t);
        }
    }

    private static String format(String firstLine, HttpHeaders headers, byte[] body) {
        StringBuilder sb = new StringBuilder(firstLine).append('\n');
        if (headers != null) {
            headers.forEach((name, values) -> values.forEach(v -> sb.append(name).append(": ").append(v).append('\n')));
        }
        if (body != null && body.length > 0) {
            sb.append('\n').append(new String(body, StandardCharsets.UTF_8));
        }
        return sb.toString();
    }
}
