/**
 * Внутренняя реализация логирования БД: AOP-аспект репозиториев и слушатель datasource-proxy
 * (реальный SQL).
 * <p>
 * Классы здесь объявлены {@code public} лишь потому, что их инстанцируют авто-конфиги из
 * соседнего пакета, — это <b>НЕ публичный API</b>. Не использовать из кода потребителя:
 * обратная совместимость не гарантируется. Публичные точки входа модуля —
 * {@code AllureDataJpaAutoConfiguration} / {@code AllureDataSourceAutoConfiguration}.
 */
package io.github.kolomyychenkoai.allure.spring.data.internal;
