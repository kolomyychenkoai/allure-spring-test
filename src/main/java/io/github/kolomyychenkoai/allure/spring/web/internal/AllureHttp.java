package io.github.kolomyychenkoai.allure.spring.web.internal;

import java.net.URI;

/**
 * Общие хелперы HTTP-модуля (MockMvc и RestAssured) — единый формат имени шага и
 * извлечение пути, чтобы два источника не разъезжались по формату. НЕ публичный API.
 */
final class AllureHttp {

    private AllureHttp() {
    }

    /** Единый формат имени шага: «HTTP METHOD path → status». */
    static String stepName(String method, String path, Object status) {
        return "HTTP " + method + " " + path + " → " + status;
    }

    /**
     * Путь+query из полного URL (без scheme://host:port) — чтобы имя шага было
     * детерминированным (эфемерный порт RANDOM_PORT не попадал в имя). Полный URL
     * остаётся в теле вложения «HTTP Request». При сбое разбора — исходная строка.
     */
    static String pathAndQuery(String url) {
        if (url == null) {
            return "";
        }
        try {
            URI u = URI.create(url);
            String path = u.getRawPath() != null ? u.getRawPath() : "";
            return u.getRawQuery() != null ? path + "?" + u.getRawQuery() : path;
        } catch (Exception e) {
            return url;
        }
    }
}
