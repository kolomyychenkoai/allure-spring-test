package io.github.kolomyychenkoai.allure.spring.e2e;

import io.github.kolomyychenkoai.allure.spring.support.CurrentReport;
import io.github.kolomyychenkoai.allure.spring.support.TestApp;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Уровень B: «живой» прогон. Листенеры конфигурации/логов подключаются САМИ (только через
 * {@code META-INF/spring.factories}, в классе ноль настройки Allure).
 * <p>
 * Шаг «Configuration» config-листенер пишет в {@code beforeTestMethod} — он уже в текущем
 * тест-кейсе во время теста, поэтому проверяем его наличие здесь (это и есть проверка
 * РЕАЛЬНОЙ регистрации config-модуля: снимут запись из spring.factories — шаг исчезнет,
 * тест покраснеет). Вложение «Application Logs» logs-листенер добавляет в
 * {@code afterTestMethod} (ПОСЛЕ тела теста), прочитать его отсюда нельзя — его содержимое
 * проверяется на уровне A ({@code AllureApplicationLogsListenerTest}); строки логов ниже
 * нужны, чтобы вложение было не пустым в showcase-отчёте.
 */
@SpringBootTest(classes = TestApp.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Epic("allure-spring-test")
@Feature("Захват логов и конфигурации приложения")
class ReportSmokeIT {

    private static final Logger log = LoggerFactory.getLogger(ReportSmokeIT.class);

    @Test
    @DisplayName("config-листенер (spring.factories) добавляет шаг Configuration; логи идут в отчёт")
    void attachesConfigAndLogsAutomatically() {
        log.info("Привет из ReportSmokeIT — эта строка должна оказаться в Application Logs");
        log.warn("Предупреждение со значением value={}", 42);

        assertTrue(CurrentReport.stepNames().contains("Configuration"),
                () -> "нет шага Configuration (config-листенер не зарегистрирован?): " + CurrentReport.stepNames());
    }
}
