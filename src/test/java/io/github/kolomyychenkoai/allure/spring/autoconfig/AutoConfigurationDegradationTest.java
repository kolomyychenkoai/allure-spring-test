package io.github.kolomyychenkoai.allure.spring.autoconfig;

import io.github.kolomyychenkoai.allure.spring.internal.MovedCustomizerRegistrar;
import io.github.kolomyychenkoai.allure.spring.internal.MovedTypeNames;
import io.qameta.allure.Epic;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Инвариант «библиотека не роняет чужой контекст» для пути АВТОКОНФИГА.
 * <p>
 * У листенеров такой инвариант стережёт {@code unit/ListenerDegradationTest}, и он же требует,
 * чтобы КАЖДЫЙ листенер был покрыт сценарием. Этот тест — его аналог для автоконфигов:
 * {@code ActivationRegistrationTest} проверяет лишь то, что они перечислены в
 * {@code AutoConfiguration.imports}, но не то, что они переживают отсутствие библиотеки.
 * <p>
 * Разница принципиальная. Листенер падает в хуке — падает один тест-класс. Регистратор бинов
 * падает в РАЗБОРЕ КОНФИГУРАЦИИ — не поднимается контекст, и у потребителя падает ВЕСЬ прогон.
 * Это худший из возможных отказов библиотеки для отчётов.
 */
@Epic("Внутренние проверки библиотеки")
class AutoConfigurationDegradationTest {

    @Test
    @DisplayName("сбой регистрации НЕ выходит наружу — контекст живёт, модуль просто выключен")
    void registrationFailureDoesNotEscape() {
        // Реестр, который бросает на любую попытку зарегистрировать бин — так выглядит любая
        // неожиданность внутри разбора конфигурации (конфликт имён, ограничение модулей, чужой
        // BeanDefinitionRegistry со своими правилами).
        DefaultListableBeanFactory hostile = new DefaultListableBeanFactory() {
            @Override
            public void registerBeanDefinition(String name, org.springframework.beans.factory.config.BeanDefinition bd) {
                throw new IllegalStateException("мутация: регистрация запрещена");
            }
        };

        assertThatCode(() -> MovedCustomizerRegistrar.register(hostile, getClass().getClassLoader(),
                MovedTypeNames.MOCKMVC_CUSTOMIZER_BEAN, MovedTypeNames.MOCKMVC_CUSTOMIZER, builder -> { }))
                .as("исключение отсюда уходит в старт контекста и роняет ВСЕ тесты потребителя")
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("имя бина занято потребителем — НЕ затираем его своим")
    void doesNotOverrideExistingBean() {
        // Переопределение РАЗРЕШЕНО намеренно. При запрете (умолчание Spring Boot) наш бин и так
        // не пройдёт — попытку съест общий guard, и тест не отличил бы «мы проверили имя» от
        // «мы попробовали и проглотили ошибку». А вот когда переопределение разрешено, единственное,
        // что стоит между потребителем и потерей его бина, — проверка занятости имени.
        DefaultListableBeanFactory registry = new DefaultListableBeanFactory();
        registry.setAllowBeanDefinitionOverriding(true);
        registry.registerBeanDefinition(MovedTypeNames.MOCKMVC_CUSTOMIZER_BEAN,
                BeanDefinitionBuilder.genericBeanDefinition(String.class, () -> "бин потребителя")
                        .getBeanDefinition());

        MovedCustomizerRegistrar.register(registry, getClass().getClassLoader(),
                MovedTypeNames.MOCKMVC_CUSTOMIZER_BEAN, MovedTypeNames.MOCKMVC_CUSTOMIZER, builder -> { });

        assertThat(registry.getBean(MovedTypeNames.MOCKMVC_CUSTOMIZER_BEAN))
                .as("со старым @Bean побеждала пользовательская конфигурация — ведём себя так же")
                .isEqualTo("бин потребителя");
    }

    @Test
    @DisplayName("имена дополнительных бинов без «#» — у решётки в Spring особый смысл")
    void extraBeanNamesAreSpringSafe() {
        DefaultListableBeanFactory registry = new DefaultListableBeanFactory();
        List<String> bothAlive = List.of(
                MovedCustomizerRegistrar.resolve(getClass().getClassLoader(),
                        MovedTypeNames.MOCKMVC_CUSTOMIZER).orElseThrow().getName(),
                MovedCustomizerRegistrar.resolve(getClass().getClassLoader(),
                        MovedTypeNames.WEBTESTCLIENT_CUSTOMIZER).orElseThrow().getName());

        MovedCustomizerRegistrar.register(registry, getClass().getClassLoader(),
                "allureCustomizer", bothAlive, builder -> { });

        assertThat(registry.getBeanDefinitionNames())
                .allSatisfy(name -> assertThat(name)
                        .as("«#» зарезервирована Spring под внутренние бины и getBean(\"name#0\")")
                        .doesNotContain("#"));
    }
}
