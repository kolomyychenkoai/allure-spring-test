package io.github.kolomyychenkoai.allure.spring.e2e;

import io.github.kolomyychenkoai.allure.spring.support.TestApp;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Уровень B: «живой» прогон. Обычные AssertJ-ассерты — НИКАКОГО Allure.step;
 * шаги «Проверка: …» появляются сами через байткод-инструментирование. Смотреть: mvn allure:serve.
 */
@SpringBootTest(classes = TestApp.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Epic("allure-spring-test")
@Feature("AssertJ")
class AssertJReportIT {

    @Test
    @DisplayName("AssertJ-ассерты автоматически попадают в отчёт шагами")
    void assertjAppearsInReport() {
        assertThat("laptop").isEqualTo("laptop");
        assertThat("laptop").startsWith("lap").endsWith("top");
        assertThat(99).isGreaterThan(0);
        assertThat(List.of("a", "b")).contains("a").hasSize(2);
    }
}
