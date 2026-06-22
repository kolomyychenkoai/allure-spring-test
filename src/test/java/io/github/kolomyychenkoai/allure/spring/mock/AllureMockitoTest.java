package io.github.kolomyychenkoai.allure.spring.mock;

import io.github.kolomyychenkoai.allure.spring.support.InMemoryAllure;
import io.github.kolomyychenkoai.allure.spring.support.mock.Pricing;
import io.github.kolomyychenkoai.allure.spring.support.mock.PricingCaller;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.model.StepResult;
import io.qameta.allure.model.TestResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Уровень A: детерминированная проверка содержимого отчёта для Mockito. */
@Epic("allure-spring-test")
@Feature("Mockito (моки)")
class AllureMockitoTest {

    private InMemoryAllure allure;

    @BeforeEach
    void setUp() {
        allure = new InMemoryAllure().install();
    }

    @AfterEach
    void tearDown() {
        allure.uninstall();
    }

    private List<String> stepNames(TestResult result) {
        return result.getSteps().stream().map(StepResult::getName).toList();
    }

    @Test
    @DisplayName("заглушка, вызов через прод-код и проверка — три разные фазы в отчёте")
    void logsStubCallVerify() {
        Pricing pricing = Mockito.mock(Pricing.class);

        TestResult result = allure.run("mock", () -> {
            Mockito.when(pricing.price("laptop")).thenReturn(999.99);     // настройка заглушки
            double p = new PricingCaller().callPrice(pricing, "laptop");  // вызов через прод-код
            if (p != 999.99) {
                throw new AssertionError("заглушка не сработала: " + p);
            }
            Mockito.verify(pricing).price("laptop");                      // проверка
        });

        List<String> names = stepNames(result);
        assertThat(names).anyMatch(n -> n.startsWith("Мок-заглушка:") && n.contains("price"));
        assertThat(names).anyMatch(n -> n.startsWith("Мок-вызов:") && n.contains("price")
                && n.contains("999.99"));
        assertThat(names).anyMatch(n -> n.startsWith("Мок-проверка:") && n.contains("price"));
    }

    @Test
    @DisplayName("многоарг вызов и нестабленный метод: оба аргумента и дефолт видны")
    void logsMultiArgAndUnstubbed() {
        Pricing pricing = Mockito.mock(Pricing.class);

        TestResult result = allure.run("multi", () -> {
            Mockito.when(pricing.total("laptop", 2)).thenReturn(1999.98);
            new PricingCaller().callTotal(pricing, "laptop", 2);  // многоарг вызов
            new PricingCaller().callPrice(pricing, "mouse");      // нестабленный → дефолт 0.0
        });

        List<String> names = stepNames(result);
        assertThat(names).anyMatch(n -> n.startsWith("Мок-вызов:") && n.contains("total")
                && n.contains("laptop") && n.contains("2") && n.contains("1999.98"));
        assertThat(names).anyMatch(n -> n.startsWith("Мок-вызов:") && n.contains("mouse")
                && n.contains("0.0")); // нестабленный метод вернул дефолт
    }
}
