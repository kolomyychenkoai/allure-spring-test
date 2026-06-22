package io.github.kolomyychenkoai.allure.spring.web;

import io.qameta.allure.Allure;
import io.restassured.filter.Filter;
import io.restassured.filter.FilterContext;
import io.restassured.response.Response;
import io.restassured.specification.FilterableRequestSpecification;
import io.restassured.specification.FilterableResponseSpecification;

/**
 * RestAssured-фильтр: каждый запрос даёт в Allure-отчёте шаг
 * «HTTP METHOD uri → status» с вложениями «HTTP Request» и «HTTP Response»
 * (имена едины с MockMvc-модулем). Ставится глобально автоматически —
 * см. {@link AllureRestAssuredListener}, код в тестах не нужен.
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

        String stepName = "HTTP " + requestSpec.getMethod() + " " + requestSpec.getURI()
                + " → " + response.getStatusCode();

        Allure.step(stepName, step -> {
            Allure.addAttachment("HTTP Request", "text/plain", formatRequest(requestSpec));
            Allure.addAttachment("HTTP Response", "text/plain", formatResponse(response));
        });

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
