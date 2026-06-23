package io.github.kolomyychenkoai.allure.spring.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcBuilderCustomizer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Уровень A: тумблер HTTP-модуля. Падает, если кастомайзер MockMvc перестанет
 * включаться по умолчанию или выключаться по {@code allure.spring.web.enabled=false}.
 */
class AllureMockMvcAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AllureMockMvcAutoConfiguration.class));

    @Test
    @DisplayName("кастомайзер MockMvc регистрируется по умолчанию")
    void customizerPresentByDefault() {
        runner.run(ctx -> assertThat(ctx).hasSingleBean(MockMvcBuilderCustomizer.class));
    }

    @Test
    @DisplayName("allure.spring.web.enabled=false выключает кастомайзер")
    void customizerDisabledByProperty() {
        runner.withPropertyValues("allure.spring.web.enabled=false")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(MockMvcBuilderCustomizer.class));
    }
}
