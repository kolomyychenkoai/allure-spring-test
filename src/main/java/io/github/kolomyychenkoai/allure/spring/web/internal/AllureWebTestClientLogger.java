package io.github.kolomyychenkoai.allure.spring.web.internal;

import io.github.kolomyychenkoai.allure.spring.internal.AllureInstrumentationLogger;
import io.qameta.allure.Allure;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.test.web.reactive.server.EntityExchangeResult;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Логирует обмен {@code WebTestClient} в Allure-отчёт шагом «HTTP METHOD path → status» с
 * вложениями «HTTP Request»/«HTTP Response» — единообразно с MockMvc/RestAssured/RestTemplate.
 * Подключается через {@code AllureWebTestClientAutoConfiguration} (builder-кастомайзер
 * Spring Boot), код в тестах не нужен.
 * <p>
 * ДВА пути захвата (у WebTestClient нет единой точки, ловящей и тело, и статус-онли):
 * <ul>
 *   <li>{@link #log} — consumer результата обмена: срабатывает, когда тест ЧИТАЕТ тело
 *       ({@code expectBody()/returnResult()/...}). Полный захват (вкл. тело запроса/ответа),
 *       на тест-потоке — пишется СРАЗУ;</li>
 *   <li>{@link #capture} — {@link AllureWebTestClientFilter} (ExchangeFilterFunction): ловит
 *       КАЖДЫЙ обмен, в т.ч. чисто статусные ({@code expectStatus()} без чтения тела), но БЕЗ
 *       чтения тела (чтобы не «съесть» его у теста) — только метод/url/статус/заголовки. Идёт
 *       на реактивном потоке без активного кейса → буферизуется и проигрывается на тест-потоке
 *       в {@code afterTestMethod} ({@code AllureWebTestClientListener}).</li>
 * </ul>
 * Дедуп: обмены, уже показанные consumer'ом (с телом), фильтр НЕ задваивает — при проигрывании
 * вычитаются по ключу {@code METHOD url}. Окно привязки = тест-метод (буфер чистится в
 * {@code beforeTestMethod}). Всё под проверкой активного кейса и в try/catch.
 */
public final class AllureWebTestClientLogger {

    // Метаданные ВСЕХ обменов (из фильтра, разные потоки) — ждут проигрывания на тест-потоке.
    private static final Queue<Captured> BUFFER = new ConcurrentLinkedQueue<>();
    // Ключи обменов, уже залогированных consumer'ом (с телом) — их фильтр не задваивает.
    private static final Queue<String> HANDLED = new ConcurrentLinkedQueue<>();

    private record Captured(String method, String url, int status, String reqText, String respText) {
        String key() {
            return method + " " + url;
        }
    }

    private AllureWebTestClientLogger() {
    }

    /** Consumer-путь: обмен с ЧТЕНИЕМ тела — полный захват (вкл. тела), сразу, на тест-потоке. */
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
            HANDLED.add(method + " " + url); // фильтр этот обмен повторно не покажет
        } catch (Throwable t) {
            AllureInstrumentationLogger.warn("WebTestClient", t);
        }
    }

    /**
     * Фильтр-путь: метаданные обмена БЕЗ чтения тела (тело останется тесту). Никогда не бросает —
     * вызывается из реактивной цепочки, исключение сломало бы обмен пользователя.
     */
    public static void capture(HttpMethod method, URI url, HttpStatusCode status,
                               HttpHeaders reqHeaders, HttpHeaders respHeaders) {
        try {
            String m = method != null ? method.name() : "";
            String u = url != null ? url.toString() : "";
            int s = status != null ? status.value() : 0;
            String reqText = format(m + " " + u, reqHeaders, null);
            String respText = format(String.valueOf(s), respHeaders, null);
            BUFFER.add(new Captured(m, u, s, reqText, respText));
        } catch (Throwable t) {
            AllureInstrumentationLogger.warn("WebTestClientCapture", t);
        }
    }

    /** Проигрывает статус-онли обмены (буфер минус показанное consumer'ом). Зовётся на тест-потоке. */
    public static void flush() {
        if (!Allure.getLifecycle().getCurrentTestCase().isPresent()) {
            clear(); // нет активного кейса — привязать не к чему
            return;
        }
        List<String> handled = new ArrayList<>(HANDLED);
        Captured captured;
        while ((captured = BUFFER.poll()) != null) {
            if (handled.remove(captured.key())) {
                continue; // этот обмен уже показал consumer (с телом) — не дублируем
            }
            try {
                emit(captured);
            } catch (Throwable t) {
                AllureInstrumentationLogger.warn("WebTestClientFlush", t);
            }
        }
        HANDLED.clear();
    }

    /** Чистит буфер между тестами (окно привязки = тест-метод). */
    public static void clear() {
        BUFFER.clear();
        HANDLED.clear();
    }

    private static void emit(Captured captured) {
        Allure.step(AllureHttp.stepName(captured.method(), AllureHttp.pathAndQuery(captured.url()), captured.status()),
                step -> {
                    Allure.addAttachment("HTTP Request", "text/plain", captured.reqText());
                    Allure.addAttachment("HTTP Response", "text/plain", captured.respText());
                });
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
