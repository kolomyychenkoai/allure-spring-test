/**
 * Внутреннее байткод-инструментирование Kafka (producer {@code send} / consumer {@code poll}).
 * <p>
 * Классы здесь объявлены {@code public} лишь потому, что ByteBuddy инлайнит advice и зовёт их
 * {@code public static} методы (а буфер consumer-записей проигрывает листенер из соседнего
 * пакета), — это <b>НЕ публичный API</b>. Не использовать из кода потребителя: обратная
 * совместимость не гарантируется. Публичная точка входа модуля — {@code AllureKafkaListener}.
 */
package io.github.kolomyychenkoai.allure.spring.kafka.internal;
