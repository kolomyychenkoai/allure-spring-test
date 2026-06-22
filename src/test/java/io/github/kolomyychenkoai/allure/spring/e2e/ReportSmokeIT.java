package io.github.kolomyychenkoai.allure.spring.e2e;

import io.github.kolomyychenkoai.allure.spring.support.TestApp;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Уровень B: «живой» прогон. Листенеры подключаются САМИ (только через
 * {@code META-INF/spring.factories}, в этом классе ноль настройки Allure) и пишут
 * реальные allure-results. Смотреть результат: {@code mvn allure:serve}.
 */
@SpringBootTest(classes = TestApp.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Epic("allure-spring-test")
@Feature("Захват логов и конфигурации приложения")
class ReportSmokeIT {

    private static final Logger log = LoggerFactory.getLogger(ReportSmokeIT.class);

    @Test
    @DisplayName("Логи и конфигурация автоматически прикрепляются к отчёту")
    void attachesLogsAndConfigAutomatically() {
        log.info("Привет из ReportSmokeIT — эта строка должна оказаться в Application Logs");
        log.warn("Предупреждение со значением value={}", 42);
        log.debug("debug-строка для проверки уровня логирования");
    }
}
