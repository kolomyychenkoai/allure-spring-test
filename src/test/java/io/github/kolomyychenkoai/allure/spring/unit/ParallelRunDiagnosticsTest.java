package io.github.kolomyychenkoai.allure.spring.unit;

import io.github.kolomyychenkoai.allure.spring.config.AllureConfigurationListener;
import io.github.kolomyychenkoai.allure.spring.internal.ActivationDiagnostics;
import io.github.kolomyychenkoai.allure.spring.internal.ConcurrencyWitness;
import io.github.kolomyychenkoai.allure.spring.internal.DiagnosticsReset;
import io.github.kolomyychenkoai.allure.spring.support.LibraryLog;
import io.github.kolomyychenkoai.allure.spring.support.TestContexts;
import io.qameta.allure.Epic;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.LogRecord;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Уровень A: библиотека замечает потоковую параллель и говорит о ней ровно там, где та правда
 * перемешивает данные.
 * <p>
 * Ось задачи #102: под {@code @Execution(CONCURRENT)} записи модулей с общим буфером могут
 * уехать в соседний тест-кейс. Перемешанные данные хуже пропавших — пропажу видно, а эти
 * выглядят настоящими, и потребитель не узнаёт о риске ниоткуда.
 * <p>
 * Параллель определяется ФАКТОМ, а не конфигурацией: спросить JUnit нельзя, разбор каналов —
 * в javadoc {@link ConcurrencyWitness}.
 */
@Epic("Внутренние проверки библиотеки")
class ParallelRunDiagnosticsTest {

    /** Только новости потребителю: следы идут на FINE и от соседних тест-классов зависят. */
    private static List<String> новости(List<LogRecord> records) {
        return records.stream()
                .filter(record -> record.getLevel() == Level.WARNING)
                .map(LogRecord::getMessage)
                .toList();
    }

    @BeforeEach
    void забытьГлобальное() {
        DiagnosticsReset.forget();
        DiagnosticsReset.forgetConcurrency();
    }

    @AfterEach
    void убратьЗаСобой() {
        // Пик глобален на JVM: оставленный здесь, он объявил бы параллель всем соседям
        // и посадил бы новость в случайный витринный тест.
        DiagnosticsReset.forgetConcurrency();
    }

    @Test
    @DisplayName("последовательный прогон параллелью не считается")
    void последовательныйПрогонНеПараллель() {
        // Мутация: убрать парный afterTestMethod (testFinished) → RED. Почему парность
        // обязательна — в javadoc ConcurrencyWitness#testStarted.
        for (int i = 0; i < 5; i++) {
            ConcurrencyWitness.testStarted();
            ConcurrencyWitness.testFinished();
        }

        assertThat(ConcurrencyWitness.concurrentSeen())
                .as("пять тестов подряд объявлены параллелью — это шум в каждой сборке")
                .isFalse();
    }

    @Test
    @DisplayName("листенер закрывает окно теста — иначе последовательный прогон станет «параллелью»")
    void листенерЗакрываетОкно() {
        // Тест выше проверяет свидетеля напрямую и мимо проводки: он остался бы зелёным, если
        // бы листенер перестал звать testFinished. Здесь ось — сама проводка.
        //
        // Мутация: убрать ConcurrencyWitness.testFinished() из afterTestMethod → RED.
        AllureConfigurationListener listener = new AllureConfigurationListener();

        ConcurrencyWitness.testStarted();
        listener.afterTestMethod(TestContexts.withEnvironment(new org.springframework.mock.env.MockEnvironment()));
        ConcurrencyWitness.testStarted();
        listener.afterTestMethod(TestContexts.withEnvironment(new org.springframework.mock.env.MockEnvironment()));

        assertThat(ConcurrencyWitness.concurrentSeen())
                .as("окно теста не закрылось — два последовательных теста объявлены параллелью")
                .isFalse();
    }

    @Test
    @DisplayName("два окна пересеклись — это параллель, и она помнится до конца JVM")
    void пересечениеОконЭтоПараллель() {
        ConcurrencyWitness.testStarted();
        ConcurrencyWitness.testStarted();
        ConcurrencyWitness.testFinished();
        ConcurrencyWitness.testFinished();

        assertThat(ConcurrencyWitness.concurrentSeen())
                .as("пересечение окон не замечено — детектор слеп к тому, ради чего заведён")
                .isTrue();
    }

    @Test
    @DisplayName("говорим только когда параллель ЕСТЬ и есть чему перемешаться")
    void говоримТолькоПоДелу() {
        Set<String> сОбщимБуфером = Set.of(DiagnosticsReset.sharedBufferMarkers().get(0));

        assertThat(ActivationDiagnostics.parallelMixesData(сОбщимБуфером::contains, true))
                .as("параллель и модуль с общим буфером — а библиотека молчит")
                .isTrue();
        assertThat(ActivationDiagnostics.parallelMixesData(сОбщимБуфером::contains, false))
                .as("параллели нет, а мы уже пугаем — предупреждение без повода обесценивает канал")
                .isFalse();
        assertThat(ActivationDiagnostics.parallelMixesData(name -> false, true))
                .as("параллель есть, но перемешивать нечему — молчать")
                .isFalse();
        assertThat(ActivationDiagnostics.parallelMixesData(name -> false, false))
                .as("пустой вход выдан за «всё плохо»")
                .isFalse();
    }

    @Test
    @DisplayName("сказано вслух и ровно один раз на JVM")
    void сказаноОдинРаз() {
        Set<String> сОбщимБуфером = Set.of(DiagnosticsReset.sharedBufferMarkers().get(0));

        List<LogRecord> said = LibraryLog.capture(() -> {
            ActivationDiagnostics.noteConcurrentRunOnce(сОбщимБуфером::contains, true);
            ActivationDiagnostics.noteConcurrentRunOnce(сОбщимБуфером::contains, true);
        });

        assertThat(новости(said))
                .as("отчёт может врать, а потребитель об этом не узнаёт ниоткуда")
                .anyMatch(message -> message.contains("несколько потоков ОДНОЙ JVM"));
        assertThat(новости(said))
                .as("сказано дважды — это шум в каждой сборке")
                .hasSize(1);
    }
}
