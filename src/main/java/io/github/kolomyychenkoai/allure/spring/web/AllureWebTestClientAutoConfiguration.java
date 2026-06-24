package io.github.kolomyychenkoai.allure.spring.web;

import io.github.kolomyychenkoai.allure.spring.web.internal.AllureWebTestClientFilter;
import io.github.kolomyychenkoai.allure.spring.web.internal.AllureWebTestClientLogger;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.test.web.reactive.server.WebTestClientBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * Авто-активация логирования {@code WebTestClient}: регистрирует
 * {@link WebTestClientBuilderCustomizer}, который вешает на каждый собираемый WebTestClient
 * консьюмер результатов обмена ({@link AllureWebTestClientLogger}). Включается сама, если
 * WebTestClient есть на classpath — потребителю код не нужен.
 * Регистрируется через {@code META-INF/spring/...AutoConfiguration.imports}.
 */
@AutoConfiguration
@ConditionalOnClass({WebTestClient.class, WebTestClientBuilderCustomizer.class})
public class AllureWebTestClientAutoConfiguration {

    @Bean
    public WebTestClientBuilderCustomizer allureWebTestClientBuilderCustomizer() {
        // filter ловит КАЖДЫЙ обмен (вкл. статус-онли, без чтения тела) → буфер→replay;
        // consumer полностью логирует обмены с чтением тела (на тест-потоке, вкл. тела).
        return builder -> builder
                .filter(new AllureWebTestClientFilter())
                .entityExchangeResultConsumer(AllureWebTestClientLogger::log);
    }
}
