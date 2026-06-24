/**
 * Внутренности Mockito-логирования: обёртка {@code MockHandler} и фасад над приватными полями
 * Mockito (рефлексия по версионно-зависимым внутренностям).
 * <p>
 * Классы здесь объявлены {@code public} лишь потому, что их зовут публичный
 * {@code AllureMockitoMockMaker} и канарейка-тест из соседних пакетов, — это
 * <b>НЕ публичный API</b>. Не использовать из кода потребителя: обратная совместимость не
 * гарантируется. Публичная точка входа модуля — {@code AllureMockitoMockMaker} (opt-in через SPI).
 */
package io.github.kolomyychenkoai.allure.spring.mock.internal;
