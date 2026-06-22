package io.github.kolomyychenkoai.allure.spring.support.mock;

/** Простой сервис для мока в тестах Mockito-модуля. */
public interface Pricing {

    double price(String product);

    double total(String product, int quantity);
}
