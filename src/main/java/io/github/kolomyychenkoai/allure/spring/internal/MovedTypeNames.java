package io.github.kolomyychenkoai.allure.spring.internal;

import java.util.List;

/**
 * Имена типов Spring Boot, которые ПЕРЕЕХАЛИ между мажорами. Только строки — ни одного
 * импорта, чтобы этот класс линковался где угодно.
 * <p>
 * <b>Зачем список, а не имя.</b> Пока автоконфиг был скомпилирован против КОНКРЕТНОГО имени,
 * артефакт физически не мог обслуживать оба мажора: собранный под Boot 4 он у потребителя на
 * Boot 3 молча выключал модуль ({@code @ConditionalOnClass} читается ASM-ом, класса нет —
 * условие ложно, автоконфиг не применяется, ошибок ноль). У WebTestClient это означало полную
 * потерю шагов: байткод-фолбэка, как у MockMvc, у него нет.
 * <p>
 * Теперь интерфейс поднимается ПО ИМЕНИ из этого списка, и один и тот же jar работает на 3.x
 * и на 4.x. Апгрейд перестал требовать правок в {@code src/main}.
 * <p>
 * Порядок в списке — порядок поиска. Появился ТРЕТИЙ мажор с новым именем — дописать сюда,
 * и это единственное место: {@code ActivationDiagnostics}, регистраторы автоконфигов и
 * канарейка {@code canary/InstrumentationApiCanaryTest} читают отсюда.
 */
public final class MovedTypeNames {

    /** {@code MockMvcBuilderCustomizer}: Boot 3.x (spring-boot-test-autoconfigure) → Boot 4.x (spring-boot-webmvc-test). */
    public static final List<String> MOCKMVC_CUSTOMIZER = List.of(
            "org.springframework.boot.test.autoconfigure.web.servlet.MockMvcBuilderCustomizer",
            "org.springframework.boot.webmvc.test.autoconfigure.MockMvcBuilderCustomizer");

    /** {@code WebTestClientBuilderCustomizer}: Boot 3.x (spring-boot-test-autoconfigure) → Boot 4.x (spring-boot-webtestclient). */
    public static final List<String> WEBTESTCLIENT_CUSTOMIZER = List.of(
            "org.springframework.boot.test.web.reactive.server.WebTestClientBuilderCustomizer",
            "org.springframework.boot.webtestclient.autoconfigure.WebTestClientBuilderCustomizer");

    /** Имя бина нашего MockMvc-кастомайзера. Строка, от версии Spring не зависит. */
    public static final String MOCKMVC_CUSTOMIZER_BEAN = "allureMockMvcBuilderCustomizer";

    /** Имя бина нашего WebTestClient-кастомайзера. */
    public static final String WEBTESTCLIENT_CUSTOMIZER_BEAN = "allureWebTestClientBuilderCustomizer";

    private MovedTypeNames() {
    }
}
