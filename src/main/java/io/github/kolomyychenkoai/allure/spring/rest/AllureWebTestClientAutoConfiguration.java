package io.github.kolomyychenkoai.allure.spring.rest;

import io.github.kolomyychenkoai.allure.spring.rest.internal.AllureWebTestClientFilter;
import io.github.kolomyychenkoai.allure.spring.rest.internal.AllureWebTestClientLogger;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.test.web.reactive.server.WebTestClientBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;

/**
 * Авто-активация логирования {@code WebTestClient}: регистрирует
 * {@link WebTestClientBuilderCustomizer}, который вешает на каждый собираемый WebTestClient
 * консьюмер результатов обмена ({@link AllureWebTestClientLogger}). Включается сама, если
 * WebTestClient есть на classpath — потребителю код не нужен.
 * Регистрируется через {@code META-INF/spring/...AutoConfiguration.imports}.
 */
@AutoConfiguration
// ExchangeFilterFunction (webflux) ОБЯЗАТЕЛЕН: WebTestClient есть в spring-test и в чисто
// сервлетном приложении, но наш фильтр реализует webflux-тип. Без него @AutoConfigureMockMvc
// у сервлет-потребителя падал бы NoClassDefFoundError при создании кастомайзера.
@ConditionalOnClass({WebTestClient.class, WebTestClientBuilderCustomizer.class, ExchangeFilterFunction.class})
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
