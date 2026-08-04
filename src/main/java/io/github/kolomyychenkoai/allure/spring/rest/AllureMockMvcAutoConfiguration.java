package io.github.kolomyychenkoai.allure.spring.rest;

import io.github.kolomyychenkoai.allure.spring.internal.MovedCustomizerRegistrar;
import io.github.kolomyychenkoai.allure.spring.internal.MovedTypeNames;
import io.github.kolomyychenkoai.allure.spring.rest.internal.AllureMockMvcResultHandler;
import org.springframework.beans.factory.BeanClassLoaderAware;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar;
import org.springframework.core.type.AnnotationMetadata;
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
 * {@code MockMvcBuilderCustomizer} между Boot 3.x и 4.x ПЕРЕЕХАЛ. Типизированная сигнатура
 * {@code @Bean} — это компайл-тайм привязка к одному имени, и jar, собранный под него, у
 * потребителя с другим мажором молча не активировался бы. Интерфейс поднимается по имени
 * из {@link MovedTypeNames#MOCKMVC_CUSTOMIZER}, поэтому артефакт работает на обоих.
 */
@AutoConfiguration
// Оба типа НЕ переезжали между мажорами, поэтому здесь литералы безопасны. Наличие самого
// кастомайзера проверяет регистратор — строкой, потому что его имя от мажора и зависит.
@ConditionalOnClass({ResultHandler.class, ConfigurableMockMvcBuilder.class})
@Import(AllureMockMvcAutoConfiguration.Registrar.class)
public class AllureMockMvcAutoConfiguration {

    /** Резолвит переехавший интерфейс и регистрирует наш кастомайзер прокси-бином. */
    public static class Registrar implements ImportBeanDefinitionRegistrar, BeanClassLoaderAware {

        private ClassLoader loader = getClass().getClassLoader();

        @Override
        public void setBeanClassLoader(ClassLoader classLoader) {
            this.loader = classLoader;
        }

        @Override
        public void registerBeanDefinitions(AnnotationMetadata metadata, BeanDefinitionRegistry registry) {
            MovedCustomizerRegistrar.register(registry, loader,
                    MovedTypeNames.MOCKMVC_CUSTOMIZER_BEAN, MovedTypeNames.MOCKMVC_CUSTOMIZER,
                    builder -> ((ConfigurableMockMvcBuilder<?>) builder)
                            .alwaysDo(new AllureMockMvcResultHandler()));
        }
    }
}
