package io.github.kolomyychenkoai.allure.spring.rest.internal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Уровень A: чистые хелперы формата HTTP-шага. От {@code pathAndQuery} зависит
 * детерминизм имени шага (эфемерный порт не должен попадать в имя) — поэтому ветки
 * (норм/без пути/null/битый URL) под тестами.
 */
class AllureHttpTest {

    @Test
    @DisplayName("stepName: единый формат «HTTP METHOD path → status»")
    void stepNameFormat() {
        assertThat(AllureHttp.stepName("GET", "/api/x", 200)).isEqualTo("HTTP GET /api/x → 200");
    }

    @Test
    @DisplayName("pathAndQuery: из полного URL убирает scheme://host:port, оставляет путь и query")
    void stripsHostAndPort() {
        assertThat(AllureHttp.pathAndQuery("http://localhost:8080/api/x?q=1")).isEqualTo("/api/x?q=1");
    }

    @Test
    @DisplayName("pathAndQuery: путь без host остаётся как есть")
    void keepsBarePath() {
        assertThat(AllureHttp.pathAndQuery("/api/x")).isEqualTo("/api/x");
    }

    @Test
    @DisplayName("pathAndQuery: URL без пути → пустая строка")
    void emptyPathWhenNone() {
        assertThat(AllureHttp.pathAndQuery("http://localhost:8080")).isEmpty();
    }

    @Test
    @DisplayName("pathAndQuery: null → пустая строка")
    void nullUrl() {
        assertThat(AllureHttp.pathAndQuery(null)).isEmpty();
    }

    @Test
    @DisplayName("pathAndQuery: битый URL → исходная строка (фоллбэк, без падения)")
    void fallbackOnMalformedUrl() {
        // пробел — недопустимый символ → URI.create бросит → возвращаем исходное целиком
        // (если бы распарсилось, host бы отрезался; раз host остался — сработал фоллбэк)
        String malformed = "http://localhost:8080/a b?q=1";
        assertThat(AllureHttp.pathAndQuery(malformed)).isEqualTo(malformed);
    }
}
