package io.github.kolomyychenkoai.allure.spring.unit;

import io.github.kolomyychenkoai.allure.spring.internal.AllureAdviceSupport;
import io.github.kolomyychenkoai.allure.spring.support.InMemoryAllure;
import io.qameta.allure.model.TestResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Уровень A: разделение смешанного вложения на метаданные (text/plain) + ТЕЛО отдельным
 * вложением (application/json, если похоже на JSON). Проверяем выбор content-type
 * ({@code bodyContentType}) и раскладку двух вложений ({@code attach}) — на это опираются
 * все HTTP/WireMock/Kafka-модули, чтобы Allure сам форматировал JSON красиво.
 */
class AllureAdviceSupportAttachTest {

    @Test
    @DisplayName("bodyContentType: JSON-объект/массив → application/json")
    void jsonBodies() {
        assertThat(AllureAdviceSupport.bodyContentType("{\"a\":1}")).isEqualTo("application/json");
        assertThat(AllureAdviceSupport.bodyContentType("[1,2]")).isEqualTo("application/json");
        // ведущие пробелы/переносы не мешают распознаванию
        assertThat(AllureAdviceSupport.bodyContentType("  \n {\"a\":1}")).isEqualTo("application/json");
    }

    @Test
    @DisplayName("bodyContentType: не-JSON → text/plain")
    void nonJsonBody() {
        assertThat(AllureAdviceSupport.bodyContentType("<html>")).isEqualTo("text/plain");
        assertThat(AllureAdviceSupport.bodyContentType("plain text")).isEqualTo("text/plain");
    }

    @Test
    @DisplayName("bodyContentType: null и пустая строка → text/plain (без падения)")
    void nullAndEmptyBody() {
        assertThat(AllureAdviceSupport.bodyContentType(null)).isEqualTo("text/plain");
        assertThat(AllureAdviceSupport.bodyContentType("")).isEqualTo("text/plain");
    }

    @Test
    @DisplayName("attach: метаданные — text/plain, JSON-тело — отдельным application/json")
    void attachSplitsJsonBody() {
        InMemoryAllure allure = new InMemoryAllure().install();
        try {
            TestResult result = allure.run("attach-json", () ->
                    AllureAdviceSupport.attach("Meta", "200\nContent-Type: application/json",
                            "Body", "{\"id\":7}"));

            assertThat(allure.attachment(result, "Meta").orElseThrow()).contains("200");
            assertThat(allure.attachmentType(result, "Meta").orElseThrow()).isEqualTo("text/plain");
            assertThat(allure.attachment(result, "Body").orElseThrow()).contains("\"id\":7");
            assertThat(allure.attachmentType(result, "Body").orElseThrow()).isEqualTo("application/json");
        } finally {
            allure.uninstall();
        }
    }

    @Test
    @DisplayName("attach: пустое/пробельное тело НЕ кладём отдельным вложением")
    void attachSkipsBlankBody() {
        InMemoryAllure allure = new InMemoryAllure().install();
        try {
            TestResult resultEmpty = allure.run("attach-empty", () ->
                    AllureAdviceSupport.attach("Meta", "meta", "Body", ""));
            assertThat(allure.attachment(resultEmpty, "Meta")).isPresent();
            assertThat(allure.attachment(resultEmpty, "Body")).isEmpty();

            TestResult resultNull = allure.run("attach-null", () ->
                    AllureAdviceSupport.attach("Meta", "meta", "Body", null));
            assertThat(allure.attachment(resultNull, "Body")).isEmpty();
        } finally {
            allure.uninstall();
        }
    }

    @Test
    @DisplayName("attach: не-JSON тело кладём отдельным вложением, но как text/plain")
    void attachPlainBody() {
        InMemoryAllure allure = new InMemoryAllure().install();
        try {
            TestResult result = allure.run("attach-plain", () ->
                    AllureAdviceSupport.attach("Meta", "meta", "Body", "just text"));

            assertThat(allure.attachment(result, "Body").orElseThrow()).contains("just text");
            assertThat(allure.attachmentType(result, "Body").orElseThrow()).isEqualTo("text/plain");
        } finally {
            allure.uninstall();
        }
    }
}
