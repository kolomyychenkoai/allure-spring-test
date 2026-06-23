package io.github.kolomyychenkoai.allure.spring.e2e;

import io.github.kolomyychenkoai.allure.spring.support.TestApp;
import io.github.kolomyychenkoai.allure.spring.support.mock.Pricing;
import io.github.kolomyychenkoai.allure.spring.support.mock.PricingCaller;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.test.context.SpringBootTest;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

/**
 * Уровень B: «живой» прогон. Обычные Mockito-моки — НИКАКОГО Allure.step; шаги
 * «Мок-заглушка/вызов/проверка» появляются сами через кастомный MockMaker (SPI).
 * Несколько сценариев, чтобы приёмка по отчёту видела не только happy-path, но и
 * многоарг вызовы, дефолт нестабленного метода, кратности verify и проваленный verify.
 * Смотреть: mvn allure:serve.
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
    }

    @Test
    @DisplayName("многоарг вызов и нестабленный метод: оба аргумента и дефолт 0.0 в отчёте")
    void multiArgAndUnstubbed() {
        Pricing pricing = Mockito.mock(Pricing.class);

        Mockito.when(pricing.total("laptop", 2)).thenReturn(1999.98);
        new PricingCaller().callTotal(pricing, "laptop", 2);  // многоарг → 1999.98
        new PricingCaller().callPrice(pricing, "mouse");      // нестабленный → дефолт 0.0

        Mockito.verify(pricing).total("laptop", 2);
        Mockito.verify(pricing, never()).price("phone");      // ожидали ×0
    }

    @Test
    @DisplayName("verify(times(2)): кратность вызова в имени шага проверки")
    void verifyCountTwo() {
        Pricing pricing = Mockito.mock(Pricing.class);

        Mockito.when(pricing.price("laptop")).thenReturn(999.99);
        new PricingCaller().callPrice(pricing, "laptop");
        new PricingCaller().callPrice(pricing, "laptop");

        Mockito.verify(pricing, times(2)).price("laptop");    // ожидали ×2
    }
}
