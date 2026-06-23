package io.github.kolomyychenkoai.allure.spring.internal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Уровень A: контракт проверки наличия класса. На ней висит защита листенеров
 * WireMock/RestAssured от {@link NoClassDefFoundError}, когда опциональной библиотеки
 * нет на classpath у потребителя.
 */
class ClassPresenceTest {

    @Test
    @DisplayName("isPresent(): true для класса, который есть на classpath")
    void presentTrue() {
        assertThat(ClassPresence.isPresent("java.lang.String")).isTrue();
    }

    @Test
    @DisplayName("isPresent(): false для отсутствующего класса (без исключения)")
    void absentFalse() {
        assertThat(ClassPresence.isPresent("com.example.__NoSuchLibrary__")).isFalse();
    }
}
