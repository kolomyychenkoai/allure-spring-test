package io.github.kolomyychenkoai.allure.spring.rest;

import io.github.kolomyychenkoai.allure.spring.internal.MovedCustomizerRegistrar;
import io.github.kolomyychenkoai.allure.spring.internal.MovedTypeNames;
import io.github.kolomyychenkoai.allure.spring.rest.internal.AllureWebTestClientFilter;
import io.github.kolomyychenkoai.allure.spring.rest.internal.AllureWebTestClientLogger;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;

/**
 * Авто-активация логирования {@code WebTestClient}: регистрирует
 * {@code WebTestClientBuilderCustomizer}, который вешает на каждый собираемый WebTestClient
 * консьюмер результатов обмена ({@link AllureWebTestClientLogger}). Включается сама, если
 * WebTestClient есть на classpath — потребителю код не нужен.
 * Регистрируется через {@code META-INF/spring/...AutoConfiguration.imports}.
 * <p>
 * <b>Почему бин регистрируется программно, а не через {@code @Bean}.</b>
 * {@code WebTestClientBuilderCustomizer} между Boot 3.x и 4.x ПЕРЕЕХАЛ, поэтому интерфейс
 * поднимается по имени из {@link MovedTypeNames#WEBTESTCLIENT_CUSTOMIZER} (общий разбор — в
 * javadoc {@link MovedCustomizerRegistrar}). Здесь цена ошибки максимальная: кастомайзер —
 * ЕДИНСТВЕННЫЙ канал этого модуля, байткод-фолбэка, как у MockMvc, у него нет, то есть
 * привязка к одному имени означала бы полную и молчаливую потерю шагов.
 */
@AutoConfiguration
// ExchangeFilterFunction (webflux) ОБЯЗАТЕЛЕН: WebTestClient есть в spring-test и в чисто
// сервлетном приложении, но наш фильтр реализует webflux-тип. Без него @AutoConfigureMockMvc
// у сервлет-потребителя падал бы NoClassDefFoundError при создании кастомайзера.
// Оба типа НЕ переезжали, поэтому здесь литералы безопасны; имя самого кастомайзера от
// мажора зависит, и его проверяет регистратор — строкой.
@ConditionalOnClass({WebTestClient.class, ExchangeFilterFunction.class})
public class AllureWebTestClientAutoConfiguration {

    /**
     * Резолвит переехавший интерфейс и регистрирует наш кастомайзер прокси-бином — после всех
     * постпроцессоров реестра, чтобы поздняя регистрация того же имени у потребителя не роняла
     * старт (issue #73, разбор — в javadoc {@link MovedCustomizerRegistrar#postProcessor}).
     * <p>
     * ⚠️ {@code static} и без аргументов — иначе конфигурация уедет в раннюю инициализацию.
     */
    @Bean
    static BeanFactoryPostProcessor allureWebTestClientCustomizerRegistrar() {
        return MovedCustomizerRegistrar.postProcessor(AllureWebTestClientAutoConfiguration.class.getName(),
                MovedTypeNames.WEBTESTCLIENT_CUSTOMIZER_BEAN, MovedTypeNames.WEBTESTCLIENT_CUSTOMIZER,
                // filter ловит КАЖДЫЙ обмен (вкл. статус-онли, без чтения тела) → буфер→replay;
                // consumer полностью логирует обмены с чтением тела (на тест-потоке, вкл. тела).
                builder -> ((WebTestClient.Builder) builder)
                        .filter(new AllureWebTestClientFilter())
                        .entityExchangeResultConsumer(AllureWebTestClientLogger::log));
    }
}
