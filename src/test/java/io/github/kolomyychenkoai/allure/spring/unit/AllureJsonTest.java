package io.github.kolomyychenkoai.allure.spring.unit;

import io.github.kolomyychenkoai.allure.spring.internal.AllureJson;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Разворачивание JSON в столбик (только пробелы; значения не трогаем). */
class AllureJsonTest {

    @Test
    @DisplayName("объект → в столбик с отступом 2 пробела, значения байт-в-байт")
    void indentsObject() {
        assertThat(AllureJson.indent("{\"productName\":\"laptop\",\"price\":9.99}"))
                .isEqualTo("{\n  \"productName\": \"laptop\",\n  \"price\": 9.99\n}");
    }

    @Test
    @DisplayName("массив и вложенность")
    void indentsNested() {
        assertThat(AllureJson.indent("{\"items\":[1,2],\"n\":{\"a\":true}}"))
                .isEqualTo("{\n  \"items\": [\n    1,\n    2\n  ],\n  \"n\": {\n    \"a\": true\n  }\n}");
    }

    @Test
    @DisplayName("пустые {} и [] НЕ разворачиваются")
    void keepsEmptyContainers() {
        assertThat(AllureJson.indent("{\"a\":{},\"b\":[]}"))
                .isEqualTo("{\n  \"a\": {},\n  \"b\": []\n}");
    }

    @Test
    @DisplayName("скобки/запятые/двоеточия ВНУТРИ строки не ломают раскладку")
    void respectsStringLiterals() {
        assertThat(AllureJson.indent("{\"m\":\"a{b},c:d[e]\"}"))
                .isEqualTo("{\n  \"m\": \"a{b},c:d[e]\"\n}");
    }

    @Test
    @DisplayName("escape-кавычка внутри строки сохраняется")
    void respectsEscapes() {
        assertThat(AllureJson.indent("{\"m\":\"say \\\"hi\\\"\"}"))
                .isEqualTo("{\n  \"m\": \"say \\\"hi\\\"\"\n}");
    }

    @Test
    @DisplayName("уже многострочный JSON перенормализуется (идемпотентно по структуре)")
    void reindentsPrettyInput() {
        String pretty = "{\n  \"a\": 1\n}";
        assertThat(AllureJson.indent(pretty)).isEqualTo(pretty);
    }

    @Test
    @DisplayName("не-JSON, HTML, null, пусто → как есть, без падения")
    void passthroughNonJson() {
        assertThat(AllureJson.indent("не json")).isEqualTo("не json");
        assertThat(AllureJson.indent("<html><body>x</body></html>")).isEqualTo("<html><body>x</body></html>");
        assertThat(AllureJson.indent("")).isEqualTo("");
        assertThat(AllureJson.indent(null)).isNull();
        assertThat(AllureJson.indent("   ")).isEqualTo("   ");
    }
}
