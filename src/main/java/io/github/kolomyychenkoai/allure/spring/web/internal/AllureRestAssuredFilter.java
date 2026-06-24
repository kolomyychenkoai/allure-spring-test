package io.github.kolomyychenkoai.allure.spring.web.internal;

import io.github.kolomyychenkoai.allure.spring.internal.AllureInstrumentationLogger;
import io.qameta.allure.Allure;
import io.restassured.filter.Filter;
import io.restassured.filter.FilterContext;
import io.restassured.response.Response;
import io.restassured.specification.FilterableRequestSpecification;
import io.restassured.specification.FilterableResponseSpecification;

/**
 * RestAssured-фильтр: каждый запрос даёт в Allure-отчёте шаг
 * «HTTP METHOD path → status» с вложениями «HTTP Request» и «HTTP Response»
 * (имена едины с MockMvc-модулем). Ставится глобально автоматически —
 * см. {@code AllureRestAssuredListener}, код в тестах не нужен.
 * <p>
 * В имени шага — путь без {@code host:port} (детерминированно, без эфемерного RANDOM_PORT);
 * полный URL остаётся в теле вложения «HTTP Request». Фильтр глобальный и переживает
 * {@code reset()}, поэтому пишем шаг ТОЛЬКО при активном Allure тест-кейсе (вызов из
 * {@code @BeforeAll}/util-кода без активного теста — молча пропускаем).
 * <p>
 * Отдельного вложения «HTTP Exception» (как у MockMvc) тут нет намеренно: у
 * RestAssured тело ответа об ошибке уже доступно в Response и попадает во вложение.
 */
public class AllureRestAssuredFilter implements Filter {

    @Override
    public Response filter(FilterableRequestSpecification requestSpec,
                           FilterableResponseSpecification responseSpec,
                           FilterContext ctx) {

        Response response = ctx.next(requestSpec, responseSpec);

        // всё построение шага в try: даже getURI()/getMethod() не должны уронить HTTP-вызов теста
        try {
            if (!Allure.getLifecycle().getCurrentTestCase().isPresent()) {
                return response; // нет активного тест-кейса — не пишем шаг «в никуда»
            }
            String stepName = AllureHttp.stepName(requestSpec.getMethod(),
                    AllureHttp.pathAndQuery(requestSpec.getURI()), response.getStatusCode());
            Allure.step(stepName, step -> {
                Allure.addAttachment("HTTP Request", "text/plain", formatRequest(requestSpec));
                Allure.addAttachment("HTTP Response", "text/plain", formatResponse(response));
            });
        } catch (Throwable t) {
            AllureInstrumentationLogger.warn("RestAssured", t);
        }

        return response;
    }

    private static String formatRequest(FilterableRequestSpecification req) {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append(req.getMethod()).append(' ').append(req.getURI()).append('\n');
            req.getHeaders().forEach(h ->
                    sb.append(h.getName()).append(": ").append(h.getValue()).append('\n'));
            Object body = req.getBody();
            if (body != null) {
                sb.append('\n').append(body);
            }
            return sb.toString();
        } catch (Exception e) {
            return "<request unavailable: " + e.getClass().getSimpleName() + ">";
        }
    }

    private static String formatResponse(Response resp) {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append(resp.getStatusCode()).append(' ').append(resp.getStatusLine()).append('\n');
            resp.getHeaders().forEach(h ->
                    sb.append(h.getName()).append(": ").append(h.getValue()).append('\n'));
            String body = resp.getBody().asString();
            if (body != null && !body.isEmpty()) {
                sb.append('\n').append(body);
            }
            return sb.toString();
        } catch (Exception e) {
            return "<response unavailable: " + e.getClass().getSimpleName() + ">";
        }
    }
}
