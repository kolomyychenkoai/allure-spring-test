package io.github.kolomyychenkoai.allure.spring.web;

import io.github.kolomyychenkoai.allure.spring.internal.AllureSpringSettings;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.test.web.servlet.ResultHandler;

/**
 * Авто-активация HTTP-логирования MockMvc: регистрирует
 * {@link MockMvcBuilderCustomizer}, который вешает {@link AllureMockMvcResultHandler}
 * на каждый собираемый MockMvc (через {@code alwaysDo}). Включается сама, если MockMvc
 * есть на classpath — потребителю писать ничего не нужно. Выключить —
 * {@code allure.spring.web.enabled=false} (общий тумблер HTTP-модуля, с RestAssured).
 * Регистрируется через {@code META-INF/spring/...AutoConfiguration.imports}.
 */
@AutoConfiguration
@ConditionalOnProperty(name = AllureSpringSettings.WEB_ENABLED, matchIfMissing = true)
@ConditionalOnClass({MockMvcBuilderCustomizer.class, ResultHandler.class})
public class AllureMockMvcAutoConfiguration {

    @Bean
    public MockMvcBuilderCustomizer allureMockMvcBuilderCustomizer() {
        return builder -> builder.alwaysDo(new AllureMockMvcResultHandler());
    }
}
