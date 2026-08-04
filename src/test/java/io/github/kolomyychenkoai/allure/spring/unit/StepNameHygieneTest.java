package io.github.kolomyychenkoai.allure.spring.unit;

import io.github.kolomyychenkoai.allure.spring.inventory.StepNameHygiene;
import io.qameta.allure.Epic;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Тесты стража гигиены имён. Половина — АНТИ-правила на РЕАЛЬНЫХ именах из отчёта:
 * страж, который краснеет на законных именах, отключат в первый же день, и тогда мусор
 * в отчёт вернётся насовсем.
 */
@Epic("Внутренние проверки библиотеки")
class StepNameHygieneTest {

    @Nested
    @Epic("Внутренние проверки библиотеки")
    @DisplayName("ловит мусор")
    class Catches {

        @Test
        @DisplayName("синтетическая лямбда (AssertJ satisfies/returns/matches)")
        void lambda() {
            assertThat(StepNameHygiene.defect(
                    "Проверка: значение x — satisfies AssertJReportIT$$Lambda/0x00007f8@1a2b3c"))
                    .get().asString().contains("лямбда");
        }

        @Test
        @DisplayName("identity-хэш вместо значения")
        void identityHash() {
            assertThat(StepNameHygiene.defect("Мок-вызов: Svc.handle(io.demo.Dto@7ab1c2d3)"))
                    .get().asString().contains("хэш");
        }

        @Test
        @DisplayName("toString массива — и объектного, и примитивного")
        void arrayToString() {
            assertThat(StepNameHygiene.defect("Проверка: значение [B@4a3f2b1c — containsExactly [I@6d06d69c"))
                    .isPresent();
            assertThat(StepNameHygiene.defect("Проверка: значение [Ljava.lang.String;@1f2e3d4c — hasSize 2"))
                    .isPresent();
        }

        @Test
        @DisplayName("тело вложения: структурный мусор ловится, техножаргон имени — нет")
        void bodyRulesAreNarrower() {
            // Структурный мусор не в порядке НИГДЕ
            assertThat(StepNameHygiene.bodyDefect("Arguments:\n  [0]: [B@4a3f2b1c"))
                    .get().asString().contains("массива");
            assertThat(StepNameHygiene.bodyDefect("Widget{owner=io.demo.User@7ab1c2d3}")).isPresent();

            // А вот сырое описание Awaitility во ВЛОЖЕНИИ «Условие ожидания» лежит НАМЕРЕННО
            // (AllureAwaitilityConditionListener кладёт его как источник правды и чистит только
            // ИМЯ шага). Правило про форму имени не должно роняться на теле.
            String raw = "Condition with alias результат готов defined as a Lambda expression in Demo returned true";
            assertThat(StepNameHygiene.defect(raw)).as("в ИМЕНИ шага это мусор").isPresent();
            assertThat(StepNameHygiene.bodyDefect(raw)).as("в ТЕЛЕ вложения это содержание").isEmpty();
        }

        @Test
        @DisplayName("динамический прокси и техножаргон Awaitility")
        void proxyAndAwaitility() {
            assertThat(StepNameHygiene.defect("Мок-вызов: $Proxy42.handle()")).isPresent();
            assertThat(StepNameHygiene.defect("Ожидание: alias x defined as условие — выполнено за 5 мс"))
                    .isPresent();
        }
    }

    @Nested
    @Epic("Внутренние проверки библиотеки")
    @DisplayName("АНТИ-правила: настоящие имена из отчёта чистые")
    class DoesNotCatchLegitimate {

        @Test
        @DisplayName("реальные имена всех модулей проходят")
        void realNamesAreClean() {
            for (String name : new String[]{
                    "Проверка: значение [a, b] — contains [a]",
                    "Проверка: значение [1, 2, 3] — containsExactly [1, 2, 3]",
                    "Проверка обращений к заглушке (×2)",
                    "Мок-вызов: Pricing.total(laptop, 2) → 1999.98",
                    "Мок-проверка: Pricing.price(laptop) (ожидали ×1)",
                    "Проверка ответа: время ответа a value less than <600000L>",
                    "SQL SELECT PUBLIC.DATABASECHANGELOG",
                    "DB WidgetRepository.findById",
                    "HTTP GET /api/search?q=laptop → 200",
                    "Kafka: отправлено → order-events [k1]",
                    "WireMock: сервер поднят (:56513)",
                    "Near-miss: GET /api/does-not-exist ≉ заглушка GET /api/flaky",
                    "🛢️ Liquibase: схема БД (2 changeset)",
                    "Проверка: значение Order[name=laptop] — hasFieldOrPropertyWithValue name, laptop",
                    "Проверка: значение user@example.com — isEqualTo user@example.com"}) {
                assertThat(StepNameHygiene.defect(name))
                        .as("законное имя не должно считаться мусором: " + name)
                        .isEmpty();
            }
        }

        @Test
        @DisplayName("пустое имя не ломает страж")
        void blankName() {
            assertThat(StepNameHygiene.defect(null)).isEmpty();
            assertThat(StepNameHygiene.defect("")).isEmpty();
        }
    }
}
