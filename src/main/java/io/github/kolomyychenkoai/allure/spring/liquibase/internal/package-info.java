/**
 * Внутреннее байткод-инструментирование Liquibase (применение changeset'ов).
 * <p>
 * Классы здесь объявлены {@code public} лишь потому, что ByteBuddy инлайнит advice и зовёт их
 * {@code public static} методы (а снимок старта листенер из соседнего пакета рисует в
 * {@code beforeTestMethod} — в начале каждого теста), —
 * это <b>НЕ публичный API</b>. Не использовать из кода потребителя: обратная совместимость не
 * гарантируется. Публичная точка входа модуля — {@code AllureLiquibaseListener}.
 */
package io.github.kolomyychenkoai.allure.spring.liquibase.internal;
