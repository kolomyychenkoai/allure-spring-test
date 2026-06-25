package io.github.kolomyychenkoai.allure.spring.unit;
import io.github.kolomyychenkoai.allure.spring.internal.AllureInstrumentation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static net.bytebuddy.matcher.ElementMatchers.named;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/** Уровень A: контракт общей байткод-базы (на ней стоят все модули). */
class AllureInstrumentationTest {

    @Test
    @DisplayName("available(): true, когда byte-buddy на classpath (канарейка на имя класса)")
    void availableTrue() {
        // в тестовом classpath byte-buddy есть (mockito/spring-test). Если строка-имя
        // 'net.bytebuddy.agent.ByteBuddyAgent' уедет при апгрейде — упадёт осмысленно.
        assertThat(AllureInstrumentation.available()).isTrue();
    }

    @Test
    @DisplayName("retransform(): сбой/отсутствие совпадений НЕ роняет вызывающего (контракт)")
    void retransformNeverThrows() {
        // матчер не совпадает ни с чем → no-op; контракт «сбой инструментирования не
        // пробрасывается» — вызов обязан вернуться без исключения.
        assertThatCode(() -> AllureInstrumentation.retransform(
                named("io.github.kolomyychenkoai.allure.spring.internal.__NoSuchType__"),
                (builder, type, classLoader, module, pd) -> builder))
                .doesNotThrowAnyException();
    }
}
