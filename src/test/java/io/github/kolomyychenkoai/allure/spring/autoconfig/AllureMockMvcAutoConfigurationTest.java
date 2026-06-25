package io.github.kolomyychenkoai.allure.spring.autoconfig;
import io.github.kolomyychenkoai.allure.spring.rest.AllureMockMvcAutoConfiguration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcBuilderCustomizer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Уровень A: авто-активация HTTP-модуля. Падает, если кастомайзер MockMvc перестанет
 * регистрироваться по умолчанию (когда MockMvc есть на classpath).
 */
class AllureMockMvcAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AllureMockMvcAutoConfiguration.class));

    @Test
    @DisplayName("кастомайзер MockMvc регистрируется по умолчанию")
    void customizerPresentByDefault() {
        runner.run(ctx -> assertThat(ctx).hasSingleBean(MockMvcBuilderCustomizer.class));
    }
}
