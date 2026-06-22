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

/**
 * Уровень B: «живой» прогон. Обычные Mockito-моки — НИКАКОГО Allure.step; шаги
 * «Мок-заглушка/вызов/проверка» появляются сами через кастомный MockMaker (SPI).
 * Смотреть: mvn allure:serve.
 */
@SpringBootTest(classes = TestApp.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Epic("allure-spring-test")
@Feature("Mockito (моки)")
class AllureMockitoReportIT {

    @Test
    @DisplayName("взаимодействия с моком (заглушка/вызов/проверка) автоматически в отчёте")
    void mockInteractionsAppearInReport() {
        Pricing pricing = Mockito.mock(Pricing.class);

        Mockito.when(pricing.price("laptop")).thenReturn(999.99);
        new PricingCaller().callPrice(pricing, "laptop");
        Mockito.verify(pricing).price("laptop");
    }
}
