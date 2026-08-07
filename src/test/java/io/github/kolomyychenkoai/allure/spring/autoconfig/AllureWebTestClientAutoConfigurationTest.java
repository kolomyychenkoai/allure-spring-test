package io.github.kolomyychenkoai.allure.spring.autoconfig;

import io.github.kolomyychenkoai.allure.spring.rest.AllureWebTestClientAutoConfiguration;
import io.qameta.allure.Epic;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.webtestclient.autoconfigure.WebTestClientBuilderCustomizer;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Уровень A: авто-активация WebTestClient-модуля. Кастомайзер — ЕДИНСТВЕННЫЙ канал этого
 * модуля: байткод-фолбэка, как у MockMvc, у него нет, поэтому тихое выключение означает
 * полную потерю его шагов — проверять активацию обязательно.
 */
@Epic("Внутренние проверки библиотеки")
class AllureWebTestClientAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AllureWebTestClientAutoConfiguration.class));

    @Test
    @DisplayName("кастомайзер WebTestClient регистрируется по умолчанию")
    void customizerPresentByDefault() {
        runner.run(ctx -> assertThat(ctx).hasSingleBean(WebTestClientBuilderCustomizer.class));
    }

    @Test
    @DisplayName("СЦЕНАРИЙ BOOT 4: нет WebTestClientBuilderCustomizer → модуль выключен")
    void customizerAbsentWithoutBootTestModule() {
        // В Boot 4 класс уехал в spring-boot-webtestclient. Проверяем будущую реальность сейчас.
        runner.withClassLoader(new FilteredClassLoader(WebTestClientBuilderCustomizer.class))
                .run(ctx -> assertThat(ctx).doesNotHaveBean(WebTestClientBuilderCustomizer.class));
    }

    @Test
    @DisplayName("сервлетный потребитель без webflux (ExchangeFilterFunction): модуль выключен")
    void customizerAbsentWithoutWebflux() {
        // Без этого гейта у чисто сервлетного потребителя был бы NoClassDefFoundError —
        // случай описан в javadoc автоконфига, но тестом не закреплялся.
        runner.withClassLoader(new FilteredClassLoader(ExchangeFilterFunction.class))
                .run(ctx -> assertThat(ctx).doesNotHaveBean(WebTestClientBuilderCustomizer.class));
    }
}
