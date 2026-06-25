/**
 * Внутренняя реализация HTTP-логирования (MockMvc / RestAssured / RestTemplate / WebTestClient):
 * байткод-инструментирование, обработчики/интерсепторы/фильтры и общий хелпер имени шага.
 * <p>
 * Классы здесь объявлены {@code public} лишь потому, что ByteBuddy инлайнит advice и их зовут
 * листенеры/авто-конфиги из соседнего пакета, — это <b>НЕ публичный API</b>. Не использовать из
 * кода потребителя: обратная совместимость не гарантируется. Публичные точки входа модуля —
 * {@code Allure*AutoConfiguration} / {@code Allure*Listener} в пакете {@code web}.
 */
package io.github.kolomyychenkoai.allure.spring.rest.internal;
