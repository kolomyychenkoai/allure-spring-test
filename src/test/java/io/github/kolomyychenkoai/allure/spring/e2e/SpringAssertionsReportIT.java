package io.github.kolomyychenkoai.allure.spring.e2e;

import io.github.kolomyychenkoai.allure.spring.support.CurrentReport;
import io.github.kolomyychenkoai.allure.spring.support.TestApp;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.util.AssertionErrors;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Уровень B: «живой» прогон через РЕАЛЬНУЮ регистрацию (spring.factories →
 * AllureAssertionsListener → байткод AssertionErrors). Spring-ассерты пишут шаги в
 * настоящий отчёт (showcase); тест читает их через {@link CurrentReport}. Краснеет при
 * снятии регистрации, поломке матчера байткода или регрессе имени шага.
 */
@SpringBootTest(classes = TestApp.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Epic("allure-spring-test")
@Feature("Spring-ассерты")
class SpringAssertionsReportIT {

    @Test
    @DisplayName("Spring-ассерты автоматически попадают в отчёт шагами (полная цепочка)")
    void springAssertionsAppearInReport() {
        AssertionErrors.assertEquals("имя продукта", "laptop", "laptop");
        AssertionErrors.assertTrue("количество положительно", 2 > 0);
        AssertionErrors.assertNotNull("у заказа есть id", "id-1");

        List<String> steps = CurrentReport.stepNames();
        assertTrue(steps.contains("Проверка: имя продукта — ожидалось laptop = laptop"), () -> "" + steps);
        assertTrue(steps.contains("Проверка: количество положительно — верно"), () -> "" + steps);
        assertTrue(steps.contains("Проверка: у заказа есть id — значение id-1 не null"), () -> "" + steps);
    }
}
