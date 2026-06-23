package io.github.kolomyychenkoai.allure.spring.e2e;

import io.github.kolomyychenkoai.allure.spring.support.CurrentReport;
import io.github.kolomyychenkoai.allure.spring.support.TestApp;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Уровень B: «живой» прогон через РЕАЛЬНУЮ регистрацию (spring.factories →
 * AllureAssertionsListener → байткод AssertJ). Обычные AssertJ-ассерты пишут шаги в
 * НАСТОЯЩИЙ отчёт (showcase), тест читает их через {@link CurrentReport}. Проверки — на
 * JUnit assertTrue (не инструментируется, не засоряет отчёт). Краснеет, если листенер не
 * зарегистрирован, матчер байткода сломан, имя шага съехало — или строковые/коллекционные
 * ассерты снова выпадут из отчёта (баг полноты иерархии AssertJ, чинился Reiterating-discovery).
 */
@SpringBootTest(classes = TestApp.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Epic("allure-spring-test")
@Feature("AssertJ")
class AssertJReportIT {

    @Test
    @DisplayName("AssertJ-ассерты (значение/строки/числа/коллекции) автоматически попадают в отчёт")
    void assertjAppearsInReport() {
        assertThat("laptop").isEqualTo("laptop");
        assertThat("laptop").startsWith("lap");          // строковый — был баг полноты
        assertThat(99).isGreaterThan(0);                 // comparable
        assertThat(List.of("a", "b")).contains("a");     // коллекция — был баг полноты
        assertThat(List.of("a", "b")).hasSize(2);

        List<String> steps = CurrentReport.stepNames();
        assertTrue(steps.contains("Проверка: значение laptop — isEqualTo laptop"), () -> "" + steps);
        assertTrue(steps.contains("Проверка: значение laptop — startsWith lap"), () -> "" + steps);
        assertTrue(steps.contains("Проверка: значение 99 — isGreaterThan 0"), () -> "" + steps);
        assertTrue(steps.contains("Проверка: значение [a, b] — contains [a]"), () -> "" + steps);
        assertTrue(steps.contains("Проверка: значение [a, b] — hasSize 2"), () -> "" + steps);
    }
}
