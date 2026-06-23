package io.github.kolomyychenkoai.allure.spring.e2e;

import io.github.kolomyychenkoai.allure.spring.support.TestApp;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.util.AssertionErrors;

/**
 * Уровень B: «живой» прогон. В тесте обычные Spring-ассерты (AssertionErrors),
 * НИКАКОГО Allure.step — шаги «Проверка: …» появляются сами через байткод-инструментирование
 * (AllureAssertionsListener из spring.factories). Смотреть: {@code mvn allure:serve}.
 */
@SpringBootTest(classes = TestApp.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Epic("allure-spring-test")
@Feature("Spring-ассерты")
class SpringAssertionsReportIT {

    @Test
    @DisplayName("Spring-ассерты автоматически попадают в отчёт шагами")
    void springAssertionsAppearInReport() {
        AssertionErrors.assertEquals("имя продукта", "laptop", "laptop");
        AssertionErrors.assertTrue("количество положительно", 2 > 0);
        AssertionErrors.assertNotNull("у заказа есть id", "id-1");
    }
}
