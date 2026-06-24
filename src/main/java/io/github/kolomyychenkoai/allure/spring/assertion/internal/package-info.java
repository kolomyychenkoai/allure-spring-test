/**
 * Внутреннее байткод-инструментирование ассертов (AssertJ / Hamcrest / Spring AssertionErrors).
 * <p>
 * Классы здесь объявлены {@code public} лишь потому, что ByteBuddy инлайнит advice и зовёт их
 * {@code public static} методы, — это <b>НЕ публичный API</b>. Не использовать из кода
 * потребителя: обратная совместимость не гарантируется, сигнатуры могут меняться без
 * предупреждения. Публичная точка входа модуля — {@code AllureAssertionsListener}.
 */
package io.github.kolomyychenkoai.allure.spring.assertion.internal;
