package io.github.kolomyychenkoai.allure.spring.support.mock;

/** Простой сервис для мока в тестах Mockito-модуля. */
public interface Pricing {

    double price(String product);

    double total(String product, int quantity);

    /**
     * Аргумент-МАССИВ заведён намеренно: без чистки значения он рендерится как {@code [B@4a3f},
     * то есть даёт в теле вложения ровно тот мусор, который стережёт гигиена тел
     * ({@code ReportInventory.bodyHygiene}). Без такого значения в витрине гейт был бы
     * форвардным — доказать его сквозной мутацией нечем.
     */
    double bulk(byte[] sku);
}
