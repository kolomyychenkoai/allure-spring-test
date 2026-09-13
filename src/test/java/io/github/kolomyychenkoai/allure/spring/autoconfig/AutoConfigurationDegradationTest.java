package io.github.kolomyychenkoai.allure.spring.autoconfig;

import io.github.kolomyychenkoai.allure.spring.internal.MovedCustomizerRegistrar;
import io.github.kolomyychenkoai.allure.spring.rest.AllureMockMvcAutoConfiguration;
import io.github.kolomyychenkoai.allure.spring.rest.AllureWebTestClientAutoConfiguration;
import io.github.kolomyychenkoai.allure.spring.internal.MovedTypeNames;
import io.qameta.allure.Epic;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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
 * падает внутри refresh контекста — контекст не поднимается, и у потребителя падает ВЕСЬ прогон.
 * Это худший из возможных отказов библиотеки для отчётов.
 */
@Epic("Внутренние проверки библиотеки")
class AutoConfigurationDegradationTest {

    @Test
    @DisplayName("сбой регистрации НЕ выходит наружу — контекст живёт, модуль просто выключен")
    void registrationFailureDoesNotEscape() {
        // Реестр, который бросает на любую попытку зарегистрировать бин — так выглядит любая
        // неожиданность внутри refresh контекста (конфликт имён, ограничение модулей, чужой
        // BeanDefinitionRegistry со своими правилами).
        DefaultListableBeanFactory hostile = new DefaultListableBeanFactory() {
            @Override
            public void registerBeanDefinition(String name, org.springframework.beans.factory.config.BeanDefinition bd) {
                throw new IllegalStateException("мутация: регистрация запрещена");
            }
        };

        assertThatCode(() -> MovedCustomizerRegistrar.register(hostile, getClass().getClassLoader(), AutoConfigurationDegradationTest.class.getName(),
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

        MovedCustomizerRegistrar.register(registry, getClass().getClassLoader(), AutoConfigurationDegradationTest.class.getName(),
                MovedTypeNames.MOCKMVC_CUSTOMIZER_BEAN, MovedTypeNames.MOCKMVC_CUSTOMIZER, builder -> { });

        assertThat(registry.getBean(MovedTypeNames.MOCKMVC_CUSTOMIZER_BEAN))
                .as("со старым @Bean побеждала пользовательская конфигурация — ведём себя так же")
                .isEqualTo("бин потребителя");
    }

    /** Бин потребителя, по которому видно, что спорное имя досталось именно ему. */
    private static final Object CONSUMER_BEAN = new Object();

    @Test
    @DisplayName("MockMvc: имя занято ПОЗДНИМ постпроцессором потребителя — старт жив, имя за ним")
    void lateConsumerRegistrationWinsForMockMvc() {
        lateCollisionLeavesConsumerBean(AllureMockMvcAutoConfiguration.class,
                LateMockMvcName.class, MovedTypeNames.MOCKMVC_CUSTOMIZER_BEAN);
    }

    @Test
    @DisplayName("WebTestClient: имя занято ПОЗДНИМ постпроцессором потребителя — старт жив, имя за ним")
    void lateConsumerRegistrationWinsForWebTestClient() {
        lateCollisionLeavesConsumerBean(AllureWebTestClientAutoConfiguration.class,
                LateWebTestClientName.class, MovedTypeNames.WEBTESTCLIENT_CUSTOMIZER_BEAN);
    }

    /**
     * Ось: потребитель занимает наше имя ПОЗЖЕ разбора конфигураций — постпроцессором реестра.
     * Пока мы регистрировались на разборе, падала его регистрация (переопределение по умолчанию
     * запрещено), и вместе с ней весь его прогон — issue #73.
     * <p>
     * ⚠️ Ассерт «не упало» тут недостаточен: он зеленеет и когда постпроцессор потребителя не
     * позвали вовсе, и когда он отработал раньше разбора. Поэтому якорь — сам бин: спорное имя
     * обязано указывать на объект ПОТРЕБИТЕЛЯ.
     * <p>
     * Мутация: вернуть регистрацию на разбор конфигурации (или сделать наш постпроцессор
     * PriorityOrdered) → контекст не поднимается → RED.
     */
    private void lateCollisionLeavesConsumerBean(Class<?> autoConfiguration, Class<?> lateRegistrar, String beanName) {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(autoConfiguration))
                .withUserConfiguration(lateRegistrar)
                .run(context -> {
                    assertThat(context)
                            .as("поздняя коллизия имени уронила контекст потребителя")
                            .hasNotFailed();
                    assertThat(context.getBean(beanName))
                            .as("имя досталось нам, а пользовательская конфигурация обязана побеждать")
                            .isSameAs(CONSUMER_BEAN);
                });
    }

    @Test
    @DisplayName("имя занято постпроцессором, которого завёл ДРУГОЙ постпроцессор — старт всё равно жив")
    void registrationFromAnotherRegistrarStillWins() {
        // Эта ось и отличает наше решение от промежуточного. Постпроцессор РЕЕСТРА видит не всё:
        // определения, которые заведёт другой такой же постпроцессор после него, ему не видны
        // (та же граница, что у аспекта — issue #86). Фаза BeanFactoryPostProcessor идёт после
        // ВСЕХ них, поэтому здесь зелено.
        //
        // Мутация: зарегистрировать кастомайзер из BeanDefinitionRegistryPostProcessor → RED.
        lateCollisionLeavesConsumerBean(AllureMockMvcAutoConfiguration.class,
                LateNameViaAnotherRegistrar.class, MovedTypeNames.MOCKMVC_CUSTOMIZER_BEAN);
    }

    @Test
    @DisplayName("наше определение помечено инфраструктурным и называет своё происхождение")
    void ourDefinitionNamesItself() {
        // Без этого бин выглядит прикладным бином потребителя, а в тексте падения стоит
        // «defined in null» — решение библиотеки нечем аудировать.
        DefaultListableBeanFactory registry = new DefaultListableBeanFactory();

        MovedCustomizerRegistrar.register(registry, getClass().getClassLoader(), "origin.Marker",
                MovedTypeNames.MOCKMVC_CUSTOMIZER_BEAN, MovedTypeNames.MOCKMVC_CUSTOMIZER, builder -> { });

        BeanDefinition definition = registry.getBeanDefinition(MovedTypeNames.MOCKMVC_CUSTOMIZER_BEAN);
        assertThat(definition.getRole())
                .as("наш бин виден потребителю как прикладной — он кандидат на автовайринг и на /actuator/beans")
                .isEqualTo(BeanDefinition.ROLE_INFRASTRUCTURE);
        assertThat(definition.getResourceDescription())
                .as("в тексте падения будет «defined in null», и чей это бин — не понять")
                .isEqualTo("origin.Marker");
    }

    /** Потребитель, занимающий наше имя ПОСЛЕ разбора конфигураций. */
    @Configuration(proxyBeanMethods = false)
    static class LateMockMvcName {
        @Bean
        static BeanDefinitionRegistryPostProcessor lateMockMvcName() {
            return registry -> registry.registerBeanDefinition(MovedTypeNames.MOCKMVC_CUSTOMIZER_BEAN,
                    BeanDefinitionBuilder.genericBeanDefinition(Object.class, () -> CONSUMER_BEAN)
                            .getBeanDefinition());
        }
    }

    /** То же для второго кастомайзера: болезнь у них общая, регистратор один. */
    @Configuration(proxyBeanMethods = false)
    static class LateWebTestClientName {
        @Bean
        static BeanDefinitionRegistryPostProcessor lateWebTestClientName() {
            return registry -> registry.registerBeanDefinition(MovedTypeNames.WEBTESTCLIENT_CUSTOMIZER_BEAN,
                    BeanDefinitionBuilder.genericBeanDefinition(Object.class, () -> CONSUMER_BEAN)
                            .getBeanDefinition());
        }
    }

    /** Потребитель, заводящий занимающий постпроцессор из ДРУГОГО постпроцессора. */
    @Configuration(proxyBeanMethods = false)
    static class LateNameViaAnotherRegistrar {
        @Bean
        static BeanDefinitionRegistryPostProcessor registrarOfRegistrar() {
            return registry -> registry.registerBeanDefinition("lateNameRegistrar",
                    BeanDefinitionBuilder.genericBeanDefinition(BeanDefinitionRegistryPostProcessor.class,
                            () -> later -> later.registerBeanDefinition(MovedTypeNames.MOCKMVC_CUSTOMIZER_BEAN,
                                    BeanDefinitionBuilder.genericBeanDefinition(Object.class, () -> CONSUMER_BEAN)
                                            .getBeanDefinition()))
                            .getBeanDefinition());
        }
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

        MovedCustomizerRegistrar.register(registry, getClass().getClassLoader(), AutoConfigurationDegradationTest.class.getName(),
                "allureCustomizer", bothAlive, builder -> { });

        assertThat(registry.getBeanDefinitionNames())
                .allSatisfy(name -> assertThat(name)
                        .as("«#» зарезервирована Spring под внутренние бины и getBean(\"name#0\")")
                        .doesNotContain("#"));
    }
}
