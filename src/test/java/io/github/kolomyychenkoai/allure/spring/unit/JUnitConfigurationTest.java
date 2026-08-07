package io.github.kolomyychenkoai.allure.spring.unit;

import io.qameta.allure.Epic;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;

import java.util.Optional;

/**
 * Читает ли JUnit наш {@code junit-platform.properties}.
 * <p>
 * <b>Зачем гейт.</b> Файл включает СЛУЧАЙНЫЙ порядок тест-методов; вместе с surefire
 * {@code runOrder=random} (порядок классов) это и есть гарантия, что тесты не зависят от порядка
 * выполнения. Если при апгрейде JUnit Platform механизм конфигурации переедет, файл перестанет
 * читаться МОЛЧА: тесты позеленеют, порядок станет фиксированным, а скрытая завязка на порядок
 * перестанет ловиться. Ручной пункт в чек-листе такое не ловит — нужен гейт.
 * <p>
 * <b>Почему через расширение.</b> {@code ExtensionContext} в тест-метод не инжектится, а
 * {@code getConfigurationParameter} — единственный штатный способ спросить у самой Платформы,
 * что она вычитала. Читать файл из classpath бессмысленно: он лежит на месте и когда его никто
 * не разбирает — проверка была бы вечно-зелёной.
 */
@Epic("Внутренние проверки библиотеки")
@ExtendWith(JUnitConfigurationTest.ConfigProbe.class)
class JUnitConfigurationTest {

    private static final String METHOD_ORDER = "junit.jupiter.testmethod.order.default";

    /** Снимает то, что Платформа реально вычитала из конфигурации, до тест-метода. */
    static final class ConfigProbe implements BeforeEachCallback {

        static volatile Optional<String> methodOrder = Optional.empty();

        @Override
        public void beforeEach(ExtensionContext context) {
            methodOrder = context.getConfigurationParameter(METHOD_ORDER);
        }
    }

    /** Немой ассерт: Jupiter Assertions перехвачены, обычный assert сам стал бы шагом в отчёте. */
    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    @Test
    @DisplayName("junit-platform.properties читается Платформой (случайный порядок методов жив)")
    void configurationFileIsRead() {
        Optional<String> order = ConfigProbe.methodOrder;
        require(order.isPresent(),
                "Платформа не видит " + METHOD_ORDER + " → src/test/resources/junit-platform.properties "
                        + "не читается. Порядок тест-методов стал фиксированным, и скрытая завязка "
                        + "на порядок больше не ловится. Типовая причина — апгрейд JUnit Platform "
                        + "сменил механизм конфигурации");
        require(order.get().contains("Random"),
                "порядок тест-методов больше не случайный (" + order.get() + ") — либо правку сделали "
                        + "осознанно и надо обновить этот гейт, либо файл конфигурации перезаписали");
    }
}
