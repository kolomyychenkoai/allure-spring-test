package io.github.kolomyychenkoai.allure.spring.rest;

import io.github.kolomyychenkoai.allure.spring.internal.MovedCustomizerRegistrar;
import io.github.kolomyychenkoai.allure.spring.internal.MovedTypeNames;
import io.github.kolomyychenkoai.allure.spring.rest.internal.AllureMockMvcResultHandler;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.test.web.servlet.ResultHandler;
import org.springframework.test.web.servlet.setup.ConfigurableMockMvcBuilder;

/**
 * Авто-активация HTTP-логирования MockMvc: регистрирует {@code MockMvcBuilderCustomizer},
 * который вешает {@link AllureMockMvcResultHandler} на каждый собираемый MockMvc (через
 * {@code alwaysDo}). Включается сама, если MockMvc есть на classpath — потребителю писать
 * ничего не нужно. Регистрируется через {@code META-INF/spring/...AutoConfiguration.imports}.
 * <p>
 * Ограничение: handler цепляется через {@code alwaysDo} — это работает для
 * {@code @AutoConfigureMockMvc}/Spring Boot фикстур. MockMvc, собранный ВРУЧНУЮ
 * ({@code MockMvcBuilders.standaloneSetup(...)} мимо кастомайзера), не перехватится
 * (для него есть байткод-канал {@code MockMvc.perform}).
 * <p>
 * <b>Почему бин регистрируется программно, а не через {@code @Bean}.</b>
 * {@code MockMvcBuilderCustomizer} между Boot 3.x и 4.x ПЕРЕЕХАЛ, поэтому интерфейс поднимается
 * по имени из {@link MovedTypeNames#MOCKMVC_CUSTOMIZER} — разбор цены типизированной сигнатуры
 * в javadoc {@link MovedCustomizerRegistrar}.
 */
@AutoConfiguration
// Оба типа НЕ переезжали между мажорами, поэтому здесь литералы безопасны. Наличие самого
// кастомайзера проверяет регистратор — строкой, потому что его имя от мажора и зависит.
@ConditionalOnClass({ResultHandler.class, ConfigurableMockMvcBuilder.class})
public class AllureMockMvcAutoConfiguration {

    /**
     * Резолвит переехавший интерфейс и регистрирует наш кастомайзер прокси-бином — после всех
     * постпроцессоров реестра, чтобы поздняя регистрация того же имени у потребителя не роняла
     * старт (issue #73, разбор — в javadoc {@link MovedCustomizerRegistrar#postProcessor}).
     * <p>
     * ⚠️ {@code static} и без аргументов — иначе конфигурация уедет в раннюю инициализацию.
     */
    @Bean
    static BeanFactoryPostProcessor allureMockMvcCustomizerRegistrar() {
        return MovedCustomizerRegistrar.postProcessor(AllureMockMvcAutoConfiguration.class.getName(),
                MovedTypeNames.MOCKMVC_CUSTOMIZER_BEAN, MovedTypeNames.MOCKMVC_CUSTOMIZER,
                builder -> ((ConfigurableMockMvcBuilder<?>) builder)
                        .alwaysDo(new AllureMockMvcResultHandler()));
    }
}
