package io.github.kolomyychenkoai.allure.spring.unit;

import io.github.kolomyychenkoai.allure.spring.internal.MovedCustomizerRegistrar;
import io.github.kolomyychenkoai.allure.spring.internal.MovedTypeNames;
import io.github.kolomyychenkoai.allure.spring.support.HiddenClassLoader;
import io.qameta.allure.Epic;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Кросс-версионная регистрация кастомайзеров: интерфейс поднимается ПО ИМЕНИ, поэтому один jar
 * работает и на Boot 3.x, и на Boot 4.x. Привязка снимается ТОЛЬКО для кастомайзеров — прочие
 * переезды Spring правок в {@code src/main} требовать могут (см. javadoc {@code MovedTypeNames}).
 * <p>
 * Что ловит тест: автоконфиг, скомпилированный против КОНКРЕТНОГО имени, у потребителя с другим
 * мажором МОЛЧА выключил бы модуль — {@code @ConditionalOnClass} читается ASM-ом, класса нет,
 * условие ложно, ошибок ноль. У WebTestClient это полная потеря шагов (байткод-фолбэка нет).
 */
@Epic("Внутренние проверки библиотеки")
class MovedCustomizerRegistrarTest {

    private static List<String> knownNames() {
        return List.of(
                MovedTypeNames.MOCKMVC_CUSTOMIZER.get(0), MovedTypeNames.MOCKMVC_CUSTOMIZER.get(1),
                MovedTypeNames.WEBTESTCLIENT_CUSTOMIZER.get(0), MovedTypeNames.WEBTESTCLIENT_CUSTOMIZER.get(1));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("knownNames")
    @DisplayName("каждое известное имя — полное имя класса (опечатка не доживёт до апгрейда)")
    void everyKnownNameIsWellFormed(String name) {
        // Прежняя версия этого теста проверяла, что резолв «не бросает» — упасть она не могла
        // ничем, то есть была демонстрацией. Опечатка в имени соседнего мажора молча дожила бы
        // до апгрейда: резолв просто вернул бы пусто, а модуль выключился бы тихо.
        assertThat(name)
                .as("имя из MovedTypeNames должно быть полным именем класса")
                .matches("([a-z][a-zA-Z\\d_]*\\.)+[A-Z][a-zA-Z\\d_$]*");
    }

    @Test
    @DisplayName("ПЕРЕХОДНОЕ СОСТОЯНИЕ: доступны ОБА имени → регистрируются ОБА бина")
    void registersEveryResolvableType() {
        // У потребителя в миграции на classpath могут оказаться оба интерфейса. Spring Boot
        // собирает кастомайзеры СТРОГО своего типа, поэтому «зарегистрируем первый найденный» —
        // лотерея: бин чужого типа Boot просто не увидит, и модуль выключится молча.
        DefaultListableBeanFactory registry = new DefaultListableBeanFactory();
        // Два РАЗНЫХ существующих интерфейса-кастомайзера играют роль «оба мажора рядом».
        List<String> bothAlive = List.of(
                MovedCustomizerRegistrar.resolve(getClass().getClassLoader(),
                        MovedTypeNames.MOCKMVC_CUSTOMIZER).orElseThrow().getName(),
                MovedCustomizerRegistrar.resolve(getClass().getClassLoader(),
                        MovedTypeNames.WEBTESTCLIENT_CUSTOMIZER).orElseThrow().getName());

        MovedCustomizerRegistrar.register(registry, getClass().getClassLoader(),
                "allureCustomizer", bothAlive, builder -> { });

        assertThat(registry.getBeanDefinitionNames())
                .as("на каждый доступный тип должен быть свой бин, иначе Boot соберёт не тот")
                .containsExactlyInAnyOrder("allureCustomizer", "allureCustomizerAlt1");
    }

    @Test
    @DisplayName("на текущем стеке оба кастомайзера резолвятся (иначе модули мертвы)")
    void bothCustomizersResolveHere() {
        ClassLoader loader = getClass().getClassLoader();
        assertThat(MovedCustomizerRegistrar.resolve(loader, MovedTypeNames.MOCKMVC_CUSTOMIZER))
                .as("MockMvc-кастомайзер не найден ни под одним известным именем").isPresent();
        assertThat(MovedCustomizerRegistrar.resolve(loader, MovedTypeNames.WEBTESTCLIENT_CUSTOMIZER))
                .as("WebTestClient-кастомайзер не найден ни под одним известным именем").isPresent();
    }

    @Test
    @DisplayName("СЦЕНАРИЙ ЧУЖОГО МАЖОРА: ни одно имя не найдено → бин не регистрируется и НИЧЕГО не падает")
    void registersNothingWhenNoNameResolves() throws IOException {
        // Именно так выглядит потребитель, у которого нашего интерфейса нет ни под одним именем.
        // Модуль обязан просто выключиться, а не уронить контекст.
        try (HiddenClassLoader loader = HiddenClassLoader.hiding(
                "org.springframework.boot.test.autoconfigure.",
                "org.springframework.boot.webmvc.test.")) {
            DefaultListableBeanFactory registry = new DefaultListableBeanFactory();

            assertThatCode(() -> MovedCustomizerRegistrar.register(registry, loader,
                    MovedTypeNames.MOCKMVC_CUSTOMIZER_BEAN, MovedTypeNames.MOCKMVC_CUSTOMIZER,
                    builder -> { }))
                    .doesNotThrowAnyException();

            assertThat(registry.containsBeanDefinition(MovedTypeNames.MOCKMVC_CUSTOMIZER_BEAN)).isFalse();
        }
    }

    @Test
    @DisplayName("зарегистрированный бин имеет ТИП интерфейса и делегирует customize в наш код")
    void registeredBeanIsTypedAndDelegates() {
        // Тип важен не меньше вызова: Spring Boot собирает кастомайзеры поиском ПО ТИПУ.
        // Зарегистрируй прокси как Object — бин будет, а Boot его не увидит.
        DefaultListableBeanFactory registry = new DefaultListableBeanFactory();
        AtomicReference<Object> got = new AtomicReference<>();
        Class<?> iface = MovedCustomizerRegistrar
                .resolve(getClass().getClassLoader(), MovedTypeNames.MOCKMVC_CUSTOMIZER).orElseThrow();

        MovedCustomizerRegistrar.register(registry, getClass().getClassLoader(),
                MovedTypeNames.MOCKMVC_CUSTOMIZER_BEAN, MovedTypeNames.MOCKMVC_CUSTOMIZER, got::set);

        assertThat(registry.getBeanNamesForType(iface))
                .as("бин обязан находиться поиском по типу интерфейса — иначе Boot его не соберёт")
                .containsExactly(MovedTypeNames.MOCKMVC_CUSTOMIZER_BEAN);

        Object customizer = registry.getBean(MovedTypeNames.MOCKMVC_CUSTOMIZER_BEAN);
        assertThat(iface.isInstance(customizer)).isTrue();
        assertThat(customizer.toString()).contains("AllureCustomizer");

        // сам вызов customize(builder) обязан доехать до нашего кода с тем же аргументом.
        // Билдер подсовываем прокси его же типа: настоящий требует Spring-окружения, а нам
        // важно только, что аргумент проехал насквозь.
        Method customize = java.util.Arrays.stream(iface.getMethods())
                .filter(m -> "customize".equals(m.getName()))
                .findFirst().orElseThrow();
        Object builder = Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{customize.getParameterTypes()[0]}, (p, m, a) -> null);

        assertThatCode(() -> customize.invoke(customizer, builder)).doesNotThrowAnyException();
        assertThat(got.get()).isSameAs(builder);
    }
}
