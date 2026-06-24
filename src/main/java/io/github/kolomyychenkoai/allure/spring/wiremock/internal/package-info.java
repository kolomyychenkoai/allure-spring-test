/**
 * Внутренняя реализация WireMock-логирования: байткод-инструментирование
 * ({@code stubFor}/{@code verify}/{@code reset}), буфер обслуженных запросов и общие шаги
 * (near-miss, состояния сценариев).
 * <p>
 * Классы здесь объявлены {@code public} лишь потому, что ByteBuddy инлайнит advice и их зовёт
 * листенер из соседнего пакета, — это <b>НЕ публичный API</b>. Не использовать из кода
 * потребителя: обратная совместимость не гарантируется. Публичная точка входа модуля —
 * {@code AllureWireMockTestListener}.
 */
package io.github.kolomyychenkoai.allure.spring.wiremock.internal;
