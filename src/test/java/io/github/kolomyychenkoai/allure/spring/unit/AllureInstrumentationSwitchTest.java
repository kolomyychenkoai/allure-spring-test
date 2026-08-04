package io.github.kolomyychenkoai.allure.spring.unit;

import io.github.kolomyychenkoai.allure.spring.internal.AllureInstrumentation;
import io.qameta.allure.Epic;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Аварийный выключатель байткод-перехвата ({@code -Dallure.spring.instrumentation=off}).
 * <p>
 * Проверяется именно СЕМАНТИКА значения, а не факт «свойство читается»: выключатель, который
 * срабатывает от любой непустой строки, однажды выключит перехват от опечатки — и это будет
 * выглядеть как «шаги молча пропали», самый дорогой в разборе исход.
 */
@Epic("Внутренние проверки библиотеки")
class AllureInstrumentationSwitchTest {

    @AfterEach
    void reset() {
        System.clearProperty(AllureInstrumentation.SWITCH);
    }

    @Test
    @DisplayName("без свойства перехват включён")
    void enabledByDefault() {
        System.clearProperty(AllureInstrumentation.SWITCH);
        assertThat(AllureInstrumentation.disabled()).isFalse();
    }

    @Test
    @DisplayName("выключает ровно «off», регистр не важен")
    void offDisables() {
        System.setProperty(AllureInstrumentation.SWITCH, "off");
        assertThat(AllureInstrumentation.disabled()).isTrue();
        System.setProperty(AllureInstrumentation.SWITCH, "OFF");
        assertThat(AllureInstrumentation.disabled()).isTrue();
    }

    @Test
    @DisplayName("любое другое значение перехват НЕ выключает")
    void otherValuesKeepItOn() {
        for (String value : new String[]{"", " ", "on", "false", "true", "0", "disabled", "of"}) {
            System.setProperty(AllureInstrumentation.SWITCH, value);
            assertThat(AllureInstrumentation.disabled())
                    .as("значение «%s» не должно выключать перехват", value)
                    .isFalse();
        }
    }
}
