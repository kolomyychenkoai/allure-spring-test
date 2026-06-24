package io.github.kolomyychenkoai.allure.spring.web;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.test.web.servlet.ResultHandler;

/**
 * Авто-активация HTTP-логирования MockMvc: регистрирует
 * {@link MockMvcBuilderCustomizer}, который вешает {@link AllureMockMvcResultHandler}
 * на каждый собираемый MockMvc (через {@code alwaysDo}). Включается сама, если MockMvc
 * есть на classpath — потребителю писать ничего не нужно.
 * Регистрируется через {@code META-INF/spring/...AutoConfiguration.imports}.
 * <p>
 * Ограничение: handler цепляется через {@code MockMvcBuilderCustomizer.alwaysDo} — это
 * работает для {@code @AutoConfigureMockMvc}/Spring Boot фикстур. MockMvc, собранный
 * ВРУЧНУЮ ({@code MockMvcBuilders.standaloneSetup(...)} мимо кастомайзера), не перехватится.
 */
@AutoConfiguration
@ConditionalOnClass({MockMvcBuilderCustomizer.class, ResultHandler.class})
public class AllureMockMvcAutoConfiguration {

    @Bean
    public MockMvcBuilderCustomizer allureMockMvcBuilderCustomizer() {
        return builder -> builder.alwaysDo(new AllureMockMvcResultHandler());
    }
}
