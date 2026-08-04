package io.github.kolomyychenkoai.allure.spring.unit;

import io.github.kolomyychenkoai.allure.spring.internal.InstrumentationDiagnostics;
import io.github.kolomyychenkoai.allure.spring.support.HiddenClassLoader;
import io.qameta.allure.Epic;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;

/**
 * Временный загрузчик не должен появляться в JVM РАНЬШЕ настоящей инструментации.
 * <p>
 * <b>Что случилось.</b> {@link HiddenClassLoader} тянет весь classpath и заводит собственные копии
 * AssertJ, WireMock, Spring. После закрытия эти классы висят в JVM до сборки мусора. Байткод-агент
 * ставится один раз — при первом Spring-тест-классе — и обходит ВСЕ загруженные классы
 * ({@code RedefinitionStrategy.Reiterating}). Наткнувшись на сироту из закрытого загрузчика, он
 * получает {@code NoSuchTypeException: Cannot resolve type description for
 * org.assertj.core.api.Assert} и срывает трансформацию этих типов. Модули (ассерты, JDBC, WireMock,
 * RestTemplate, MockMvc) молча теряют шаги на ВЕСЬ прогон.
 * <p>
 * <b>Как поймали.</b> 20 полных прогонов со случайным порядком классов: ровно один красный —
 * тот единственный, где {@code ListenerDegradationTest} отработал раньше первого Spring-теста
 * (30 упавших тестов в семи классах). Корреляция 20/20.
 * <p>
 * <b>Что стережёт этот тест.</b> Что гарантия в {@code HiddenClassLoader.hiding(...)} на месте:
 * после создания временного загрузчика инструментация уже установлена и что-то реально перехвачено.
 * Снимут вызов {@code ensureRealInstrumentationInstalled()} — здесь станет красно, а не через
 * полгода одним случайным прогоном из двадцати.
 */
@Epic("Внутренние проверки библиотеки")
class HiddenClassLoaderSafetyTest {

    /** Немой ассерт: Jupiter Assertions перехвачены, обычный assert сам стал бы шагом в отчёте. */
    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    @Test
    @DisplayName("создание временного загрузчика ГАРАНТИРУЕТ, что инструментация уже встала")
    void hiddenLoaderInstallsInstrumentationFirst() throws IOException {
        try (HiddenClassLoader loader = HiddenClassLoader.hiding("нет.такого.пакета.")) {
            require(loader != null, "загрузчик не создан");
        }

        require(InstrumentationDiagnostics.installed(),
                "после создания HiddenClassLoader агент обязан быть установлен — иначе он встанет "
                        + "ПОЗЖЕ и споткнётся о классы закрытого загрузчика (NoSuchTypeException), "
                        + "а модули молча потеряют шаги на весь прогон. Проверь, что "
                        + "HiddenClassLoader.hiding() зовёт ListenerLifecycle"
                        + ".ensureRealInstrumentationInstalled()");
        require(InstrumentationDiagnostics.transformedCount() > 0,
                "агент установлен, но не перехвачено НИ ОДНОГО типа — предустановка листенеров "
                        + "не сработала, значит гарантия фиктивна");
    }
}
