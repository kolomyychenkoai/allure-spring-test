package io.github.kolomyychenkoai.allure.spring.e2e;

import io.github.kolomyychenkoai.allure.spring.support.CurrentReport;
import io.github.kolomyychenkoai.allure.spring.support.TestApp;
import io.github.kolomyychenkoai.allure.spring.support.mock.Pricing;
import io.github.kolomyychenkoai.allure.spring.support.mock.PricingCaller;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

/**
 * Уровень B: «живой» прогон через РЕАЛЬНЫЙ кастомный MockMaker (SPI). Mockito-моки пишут
 * шаги в настоящий отчёт (showcase); тест читает их через {@link CurrentReport}. Краснеет,
 * если MockMaker не подхватился (SPI снят) или формат «Мок-заглушка/вызов/проверка» съехал.
 */
@SpringBootTest(classes = TestApp.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Epic("allure-spring-test")
@Feature("Mockito (моки)")
class AllureMockitoReportIT {

    @Test
    @DisplayName("заглушка, вызов через прод-код и проверка ×1 — три фазы в отчёте")
    void mockInteractionsAppearInReport() {
        Pricing pricing = Mockito.mock(Pricing.class);
        Mockito.when(pricing.price("laptop")).thenReturn(999.99);
        new PricingCaller().callPrice(pricing, "laptop");
        Mockito.verify(pricing).price("laptop");

        List<String> steps = CurrentReport.stepNames();
        assertTrue(steps.stream().anyMatch(n -> n.startsWith("Мок-заглушка:") && n.contains("Pricing.price")),
                () -> "" + steps);
        assertTrue(steps.stream().anyMatch(n -> n.startsWith("Мок-вызов:") && n.contains("Pricing.price")
                && n.contains("999.99")), () -> "" + steps);
        assertTrue(steps.stream().anyMatch(n -> n.startsWith("Мок-проверка:") && n.contains("ожидали ×1")),
                () -> "" + steps);

        // содержимое вложений (метод+аргументы / результат) через реальную цепочку
        String call = CurrentReport.attachmentContent("Mock Call").orElse("");
        assertTrue(call.contains("Pricing.price") && call.contains("laptop"), () -> "Mock Call: " + call);
        String res = CurrentReport.attachmentContent("Mock Result").orElse("");
        assertTrue(res.contains("999.99"), () -> "Mock Result: " + res);
    }

    @Test
    @DisplayName("многоарг вызов и нестабленный метод: оба аргумента и дефолт 0.0 в отчёте")
    void multiArgAndUnstubbed() {
        Pricing pricing = Mockito.mock(Pricing.class);
        Mockito.when(pricing.total("laptop", 2)).thenReturn(1999.98);
        new PricingCaller().callTotal(pricing, "laptop", 2);
        new PricingCaller().callPrice(pricing, "mouse");
        Mockito.verify(pricing).total("laptop", 2);
        Mockito.verify(pricing, never()).price("phone");

        List<String> steps = CurrentReport.stepNames();
        assertTrue(steps.stream().anyMatch(n -> n.contains("total") && n.contains("laptop") && n.contains("1999.98")),
                () -> "" + steps);
        assertTrue(steps.stream().anyMatch(n -> n.contains("mouse") && n.contains("0.0")), () -> "" + steps);
        assertTrue(steps.stream().anyMatch(n -> n.startsWith("Мок-проверка:") && n.contains("ожидали ×0")),
                () -> "" + steps);
    }

    @Test
    @DisplayName("verify(times(2)): кратность вызова в имени шага проверки")
    void verifyCountTwo() {
        Pricing pricing = Mockito.mock(Pricing.class);
        Mockito.when(pricing.price("laptop")).thenReturn(999.99);
        new PricingCaller().callPrice(pricing, "laptop");
        new PricingCaller().callPrice(pricing, "laptop");
        Mockito.verify(pricing, times(2)).price("laptop");

        List<String> steps = CurrentReport.stepNames();
        assertTrue(steps.stream().anyMatch(n -> n.startsWith("Мок-проверка:") && n.contains("ожидали ×2")),
                () -> "" + steps);
    }
}
