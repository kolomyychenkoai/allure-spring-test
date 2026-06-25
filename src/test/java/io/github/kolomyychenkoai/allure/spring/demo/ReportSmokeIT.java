package io.github.kolomyychenkoai.allure.spring.demo;

import io.github.kolomyychenkoai.allure.spring.support.CurrentReport;
import io.github.kolomyychenkoai.allure.spring.support.TestApp;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import org.junit.jupiter.api.AfterAll;
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
 * кейсе во время теста (проверяем сразу). Вложение «Application Logs» logs-листенер
 * добавляет в {@code afterTestMethod} — из тела теста его не прочитать. Проверяем его в
 * {@code @AfterAll}: к этому моменту Allure уже записал вложение на диск, а отдельного
 * пустого тест-кейса в отчёте (как было бы у второго @Test-«читателя») не появляется.
 */
@SpringBootTest(classes = TestApp.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Epic("allure-spring-test")
@Feature("Захват логов и конфигурации приложения")
class ReportSmokeIT {

    private static final Logger log = LoggerFactory.getLogger(ReportSmokeIT.class);

    // уникальный маркер — по нему @AfterAll найдёт лог-строку в записанном вложении
    private static final String LOG_MARKER = "ReportSmokeIT-marker-7f3a9c";

    @Test
    @DisplayName("config-листенер даёт шаг Configuration; логи эмитятся")
    void emitsConfigStepAndLogs() {
        log.info("Привет из ReportSmokeIT [{}] — строка для Application Logs", LOG_MARKER);
        log.warn("Предупреждение со значением value={}", 42);

        assertTrue(CurrentReport.stepNames().contains("Configuration"),
                () -> "нет шага Configuration (config-листенер не зарегистрирован?): " + CurrentReport.stepNames());
    }

    @AfterAll
    @DisplayName("logs-листенер реально записал «Application Logs» (end-to-end, без пустого теста)")
    static void logsAttachmentWasWrittenToReport() {
        // вложение из afterTestMethod уже на диске → ищем маркер в записанных результатах
        assertTrue(CurrentReport.anyResultFileContains(LOG_MARKER),
                "лог-строка не попала во вложение Application Logs реального отчёта "
                        + "(logs-листенер не зарегистрирован или не пишет содержимое)");
    }
}
