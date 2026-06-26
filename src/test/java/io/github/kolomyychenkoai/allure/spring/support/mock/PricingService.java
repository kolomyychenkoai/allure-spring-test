package io.github.kolomyychenkoai.allure.spring.support.mock;

/**
 * Конкретная реализация {@link Pricing} для тестов spy (Mockito.spy требует реального
 * объекта, в отличие от mock интерфейса). Возвращает детерминированные значения, чтобы
 * результат вызова в отчёте не зависел от стаба.
 */
public class PricingService implements Pricing {

    @Override
    public double price(String product) {
        return 100.0;
    }

    @Override
    public double total(String product, int quantity) {
        return 100.0 * quantity;
    }
}
