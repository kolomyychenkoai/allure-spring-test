/**
 * Внутренняя реализация модуля Awaitility: слушатель условий
 * ({@link io.github.kolomyychenkoai.allure.spring.awaitility.internal.AllureAwaitilityConditionListener},
 * официальный SPI Awaitility), пишущий шаг по выполненному ожиданию.
 * <p>
 * Класс здесь {@code public} лишь потому, что его инстанцирует листенер из соседнего пакета, —
 * это <b>НЕ публичный API</b>. Не использовать из кода потребителя: обратная совместимость не
 * гарантируется. Публичная точка входа модуля — {@code AllureAwaitilityListener}.
 */
package io.github.kolomyychenkoai.allure.spring.awaitility.internal;
