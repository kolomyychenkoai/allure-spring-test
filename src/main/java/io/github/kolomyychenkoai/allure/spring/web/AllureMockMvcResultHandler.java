package io.github.kolomyychenkoai.allure.spring.web;

import io.github.kolomyychenkoai.allure.spring.internal.AllureInstrumentationLogger;
import io.qameta.allure.Allure;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultHandler;

import java.io.PrintWriter;
import java.io.StringWriter;

/**
 * Прицепляет каждый {@code mockMvc.perform(...)} к Allure-отчёту шагом
 * «HTTP METHOD uri → status» с вложениями «HTTP Request» и «HTTP Response».
 * Ставится автоматически через {@link AllureMockMvcAutoConfiguration}
 * (MockMvcBuilderCustomizer.alwaysDo) — код в тестах не нужен.
 */
public class AllureMockMvcResultHandler implements ResultHandler {

    @Override
    public void handle(MvcResult result) {
        // всё в try — инструментирование не должно ронять тест потребителя (§4 кодекса)
        try {
            if (!Allure.getLifecycle().getCurrentTestCase().isPresent()) {
                return; // нет активного тест-кейса — не пишем шаг «в никуда» (единообразно с прочими модулями)
            }
            MockHttpServletRequest req = result.getRequest();
            MockHttpServletResponse resp = result.getResponse();

            String query = req.getQueryString();
            String uri = (query != null) ? req.getRequestURI() + "?" + query : req.getRequestURI();
            String stepName = AllureHttp.stepName(req.getMethod(), uri, resp.getStatus());

            Allure.step(stepName, step -> {
                Allure.addAttachment("HTTP Request", "text/plain", formatRequest(req));
                Allure.addAttachment("HTTP Response", "text/plain", formatResponse(resp));
                Exception resolved = result.getResolvedException();
                if (resolved != null) {
                    // при ошибке контроллера — причина в отчёт (иначе виден «→ 500» без объяснения)
                    Allure.addAttachment("HTTP Exception", "text/plain", stackTrace(resolved));
                }
            });
        } catch (Throwable t) {
            AllureInstrumentationLogger.warn("MockMvc", t);
        }
    }

    private static String stackTrace(Throwable ex) {
        StringWriter sw = new StringWriter();
        ex.printStackTrace(new PrintWriter(sw));
        return sw.toString();
    }

    private static String formatRequest(MockHttpServletRequest req) {
        StringBuilder sb = new StringBuilder();
        sb.append(req.getMethod()).append(' ').append(req.getRequestURI());
        if (req.getQueryString() != null) {
            sb.append('?').append(req.getQueryString());
        }
        sb.append('\n');

        var headerNames = req.getHeaderNames();
        while (headerNames.hasMoreElements()) {
            String name = headerNames.nextElement();
            // все значения мультизначного заголовка (Cookie/Accept и т.п.), не только первое
            var values = req.getHeaders(name);
            while (values.hasMoreElements()) {
                sb.append(name).append(": ").append(values.nextElement()).append('\n');
            }
        }

        String body = bodyOf(req);
        if (!body.isEmpty()) {
            sb.append('\n').append(body);
        }
        return sb.toString();
    }

    private static String formatResponse(MockHttpServletResponse resp) {
        StringBuilder sb = new StringBuilder();
        sb.append(resp.getStatus()).append('\n');

        for (String name : resp.getHeaderNames()) {
            // все значения (напр. несколько Set-Cookie), а не только первое
            for (String value : resp.getHeaders(name)) {
                sb.append(name).append(": ").append(value).append('\n');
            }
        }

        String body = bodyOf(resp);
        if (!body.isEmpty()) {
            sb.append('\n').append(body);
        }
        return sb.toString();
    }

    private static String bodyOf(MockHttpServletRequest req) {
        // getContentAsString() бросает IllegalStateException, если тело уже прочитано
        // контроллером (через input stream) — поэтому берём сырые байты напрямую.
        try {
            byte[] content = req.getContentAsByteArray();
            if (content == null || content.length == 0) {
                return "";
            }
            String enc = req.getCharacterEncoding();
            return new String(content, enc != null ? enc : "UTF-8");
        } catch (Exception e) {
            return "<body unavailable: " + e.getClass().getSimpleName() + ">";
        }
    }

    private static String bodyOf(MockHttpServletResponse resp) {
        try {
            return resp.getContentAsString();
        } catch (Exception e) {
            return "<body unavailable: " + e.getClass().getSimpleName() + ">";
        }
    }
}
