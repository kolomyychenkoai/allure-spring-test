package io.github.kolomyychenkoai.allure.spring.support.mock;

/**
 * «Прод-код», который дёргает мок — имя класса НЕ оканчивается на Test, поэтому вызов
 * мока через него детектится как «Мок-вызов» (а не настройка заглушки из теста).
 */
public class PricingCaller {

    public double callPrice(Pricing pricing, String product) {
        return pricing.price(product);
    }

    public double callTotal(Pricing pricing, String product, int quantity) {
        return pricing.total(product, quantity);
    }
}
