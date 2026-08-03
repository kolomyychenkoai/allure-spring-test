package io.github.kolomyychenkoai.allure.spring.unit;

import io.github.kolomyychenkoai.allure.spring.rest.internal.AllureRestTemplateInstrumentation;
import io.github.kolomyychenkoai.allure.spring.rest.internal.AllureRestTemplateInterceptor;
import io.qameta.allure.Epic;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Уровень A: перехватчик RestTemplate обязан ПЕРЕЖИВАТЬ перестройку списка интерсепторов.
 * <p>
 * Наш интерсептор ставится из advice на конструкторе. Всё, что зовёт {@code setInterceptors(...)}
 * ПОСЛЕ конструктора (прикладной код, {@code RestTemplateCustomizer}-бины — они исполняются
 * последними), заменяет список целиком и выбрасывает нас: HTTP-шаги у потребителя молча исчезают.
 */
@Epic("Внутренние проверки библиотеки")
class AllureRestTemplateInstrumentationTest {

    @BeforeAll
    static void install() {
        AllureRestTemplateInstrumentation.install(); // CAS-идемпотентно
    }

    private static boolean instrumented(RestTemplate template) {
        return template.getInterceptors().stream().anyMatch(AllureRestTemplateInterceptor.class::isInstance);
    }

    @Test
    @DisplayName("новый RestTemplate инструментирован (базовый путь)")
    void freshTemplateIsInstrumented() {
        assertThat(instrumented(new RestTemplate())).isTrue();
    }

    @Test
    @DisplayName("setInterceptors(...) НЕ выбрасывает наш перехватчик")
    void survivesSetInterceptors() {
        RestTemplate template = new RestTemplate();
        List<ClientHttpRequestInterceptor> own = new ArrayList<>();
        own.add((request, body, execution) -> execution.execute(request, body));

        template.setInterceptors(own); // так делают прикладной код и RestTemplateCustomizer-бины

        assertThat(instrumented(template)).isTrue();
    }

    @Test
    @DisplayName("КАНАРЕЙКА: RestTemplateBuilder ДОБАВЛЯЕТ интерсепторы, а не заменяет список")
    void builderAppendsInterceptors() {
        // Зелёный намеренно: фиксируем поведение Spring, на которое опирается решение НЕ вешать
        // ничего на билдер. Переедет Boot на замену списка — покраснеет здесь, а не у потребителя.
        RestTemplate built = new RestTemplateBuilder()
                .additionalInterceptors((request, body, execution) -> execution.execute(request, body))
                .build();

        assertThat(instrumented(built)).isTrue();
    }
}
