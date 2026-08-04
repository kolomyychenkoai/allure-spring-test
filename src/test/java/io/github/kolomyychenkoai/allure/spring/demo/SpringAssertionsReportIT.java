package io.github.kolomyychenkoai.allure.spring.demo;

import io.github.kolomyychenkoai.allure.spring.support.CurrentReport;
import io.github.kolomyychenkoai.allure.spring.support.TestApp;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.util.AssertionErrors;

import java.util.List;

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
        CurrentReport.assertStep("Проверка: имя продукта — ожидалось laptop = laptop");
        CurrentReport.assertStep("Проверка: количество положительно — верно");
        CurrentReport.assertStep("Проверка: у заказа есть id — значение id-1 не null");
    }

    @Test
    @DisplayName("остальные три advice (assertNotEquals/assertFalse/assertNull) тоже дают шаги")
    void remainingAdvicesAppearInReport() {
        // Модуль вешает 6 advice, витрина показывала 3: у трёх матчеры takesArguments(N)
        // не проверялись на живой цепочке ни разу. Заодно пиннится дедуп по глубине —
        // assertNull внутри делегирует в assertTrue, и шаг должен остаться ОДИН.
        AssertionErrors.assertNotEquals("разные имена", "laptop", "phone");
        AssertionErrors.assertFalse("единица не больше двух", 1 > 2);
        AssertionErrors.assertNull("значения нет", null);

        List<String> steps = CurrentReport.stepNames();
        CurrentReport.check(steps.stream().anyMatch(n -> n.contains("разные имена")),
                () -> "нет шага assertNotEquals: " + steps);
        CurrentReport.check(steps.stream().anyMatch(n -> n.contains("единица не больше двух")),
                () -> "нет шага assertFalse: " + steps);
        long nullSteps = steps.stream().filter(n -> n.contains("значения нет")).count();
        CurrentReport.check(nullSteps == 1,
                () -> "assertNull должен дать РОВНО один шаг (делегат assertTrue подавлен): " + steps);
    }
}
