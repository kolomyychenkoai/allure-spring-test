package io.github.kolomyychenkoai.allure.spring.unit;

import io.qameta.allure.Epic;
import io.github.kolomyychenkoai.allure.spring.inventory.StepTemplates;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Тесты нормализатора имён шагов для инвентаря отчёта.
 * <p>
 * Половина тестов здесь — АНТИ-правила: проверяют, что нормализация НЕ схлопывает лишнего.
 * Переусердствовавшая регулярка (напр. глобальное {@code \d+ → <N>}) сделала бы
 * {@code ReportInventoryCheck} вечно-зелёным — детектор, который не краснеет никогда,
 * хуже отсутствия детектора.
 */
@Epic("Внутренние проверки библиотеки")
class StepTemplatesTest {

    @Nested
    @DisplayName("маскирует рантайм-мусор (одинаковые прогоны дают один вид)")
    class MasksRuntimeNoise {

        @Test
        void портыWireMock() {
            assertThat(StepTemplates.step("WireMock: сервер поднят (:56513)"))
                    .isEqualTo(StepTemplates.step("WireMock: сервер поднят (:61832)"))
                    .isEqualTo("WireMock: сервер поднят (:<PORT>)");
        }

        @Test
        void миллисекундыAwaitility() {
            assertThat(StepTemplates.step("Ожидание: результат готов — выполнено за 102 мс"))
                    .isEqualTo(StepTemplates.step("Ожидание: результат готов — выполнено за 111 мс"))
                    .isEqualTo("Ожидание: результат готов — выполнено за <MS> мс");
        }

        @Test
        void сгенерённыеIdСущностей() {
            assertThat(StepTemplates.step("Проверка: значение Optional[Widget{name=gadget, id=1}] — isPresent"))
                    .isEqualTo(StepTemplates.step("Проверка: значение Optional[Widget{name=gadget, id=7}] — isPresent"))
                    .isEqualTo("Проверка: значение <V> — isPresent");
        }

        @Test
        void количествоChangesetИСообщений() {
            assertThat(StepTemplates.step("🛢️ Liquibase: схема БД (2 changeset)"))
                    .isEqualTo(StepTemplates.step("🛢️ Liquibase: схема БД (5 changeset)"));
            assertThat(StepTemplates.step("Kafka: получено 1 сообщ."))
                    .isEqualTo(StepTemplates.step("Kafka: получено 3 сообщ."))
                    .isEqualTo("Kafka: получено <N> сообщ.");
        }

        @Test
        void значенияQueryВПути() {
            assertThat(StepTemplates.step("HTTP GET /api/search?q=laptop → 200"))
                    .isEqualTo(StepTemplates.step("HTTP GET /api/search?q=dq42 → 200"))
                    .isEqualTo("HTTP GET /api/search?<QUERY> → 200");
        }

        @Test
        void многострочноеИмяСхлопываетсяВОднуСтроку() {
            String out = StepTemplates.step("Проверка: значение {\n  \"a\": 1\n} — isEqualTo {\n  \"a\": 1\n}");
            assertThat(out).doesNotContain("\n").startsWith("Проверка: значение <V> — isEqualTo");
        }

        @Test
        void аргументыИВозвратМока() {
            assertThat(StepTemplates.step("Мок-вызов: Pricing.price(laptop) → 999.99"))
                    .isEqualTo(StepTemplates.step("Мок-вызов: Pricing.price(mouse) → 0.0"))
                    .isEqualTo("Мок-вызов: Pricing.price(<ARGS>) → <V>");
            assertThat(StepTemplates.step("Мок-проверка: Pricing.price(laptop) (ожидали ×1)"))
                    .isEqualTo(StepTemplates.step("Мок-проверка: Pricing.price(phone) (ожидали ×0)"))
                    .isEqualTo("Мок-проверка: Pricing.price(<ARGS>) (ожидали ×<N>)");
        }
    }

    @Nested
    @DisplayName("АНТИ-правила: не схлопывает то, по чему видно, что модуль жив")
    class KeepsDiscriminatingPower {

        @Test
        void sqlОперацияИТаблицаРазличаются() {
            assertThat(StepTemplates.step("SQL INSERT widget"))
                    .isEqualTo("SQL INSERT widget")
                    .isNotEqualTo(StepTemplates.step("SQL SELECT widget"))
                    .isNotEqualTo(StepTemplates.step("SQL INSERT PUBLIC.DATABASECHANGELOG"));
        }

        @Test
        @DisplayName("оператор не теряется, если в самом значении есть « — »")
        void операторВыживаетТиреВЗначении() {
            // жадное «(.+)» цеплялось бы за ПОСЛЕДНЕЕ тире и дало бы «— готово» вместо «— isEqualTo»:
            // вид шага начал бы зависеть от ДАННЫХ теста
            assertThat(StepTemplates.step("Проверка: значение итог — готово — isEqualTo итог — готово"))
                    .isEqualTo("Проверка: значение <V> — isEqualTo <V>");
        }

        @Test
        void операторыAssertJРазличаются() {
            assertThat(StepTemplates.step("Проверка: значение [a, b] — contains [a]"))
                    .isNotEqualTo(StepTemplates.step("Проверка: значение [a, b] — hasSize 2"))
                    .isNotEqualTo(StepTemplates.step("Проверка: значение laptop — isEqualTo laptop"));
        }

        @Test
        void арностьОператораРазличается() {
            // isEmpty без операнда vs isEqualTo со значением — разные виды
            assertThat(StepTemplates.step("Проверка: значение Optional.empty — isEmpty"))
                    .isEqualTo("Проверка: значение <V> — isEmpty")
                    .isNotEqualTo(StepTemplates.step("Проверка: значение laptop — isEqualTo laptop"));
        }

        @Test
        void httpСтатусИМетодСохраняются() {
            assertThat(StepTemplates.step("HTTP GET /api/hello/world → 200"))
                    .isEqualTo("HTTP GET /api/hello/world → 200")
                    .isNotEqualTo(StepTemplates.step("HTTP GET /api/hello/world → 404"))
                    .isNotEqualTo(StepTemplates.step("HTTP POST /api/hello/world → 200"));
        }

        @Test
        void путьСохраняется() {
            assertThat(StepTemplates.step("HTTP GET /api/hello/world → 200"))
                    .isNotEqualTo(StepTemplates.step("HTTP GET /api/echo → 200"));
        }

        @Test
        void классИМетодРепозиторияСохраняются() {
            assertThat(StepTemplates.step("DB WidgetRepository.save"))
                    .isEqualTo("DB WidgetRepository.save")
                    .isNotEqualTo(StepTemplates.step("DB WidgetRepository.findAll"));
        }

        @Test
        void топикKafkaСохраняется() {
            assertThat(StepTemplates.step("Kafka: отправлено → order-events [k1]"))
                    .isEqualTo("Kafka: отправлено → order-events [<KEY>]")
                    .isNotEqualTo(StepTemplates.step("Kafka: отправлено → listener-events [k1]"));
        }

        @Test
        void сообщениеАссертаСохраняется() {
            // по сообщению видно, КАКОЙ вид проверки жив (Status vs JSON path)
            assertThat(StepTemplates.step("Проверка: Status — ожидалось 200 = 200"))
                    .isEqualTo("Проверка: Status — ожидалось <V> = <V>")
                    .isNotEqualTo(StepTemplates.step("Проверка: JSON path \"$.greeting\" — ожидалось hello = hello"));
        }

        @Test
        void наличиеReasonУHamcrestРазличается() {
            // 2-арг и 3-арг перегрузки assertThat — разные допущения о делегации
            assertThat(StepTemplates.step("Проверка: значение 2, ожидалось a value greater than <0>"))
                    .isEqualTo("Проверка: значение <V>, ожидалось <MATCHER>")
                    .isNotEqualTo(StepTemplates.step("Проверка: цена есть: значение 99, ожидалось not null"));
        }

        @Test
        void ключевоеСловоПроверкиОтветаСохраняется() {
            assertThat(StepTemplates.step("Проверка ответа: статус 200"))
                    .isEqualTo("Проверка ответа: статус <V>")
                    .isNotEqualTo(StepTemplates.step("Проверка ответа: тело productName \"laptop\""));
        }

        @Test
        void наличиеПортаОтличаетсяОтЕгоОтсутствия() {
            // https-only деградация: имя без порта — ОТДЕЛЬНЫЙ вид, а не тот же самый
            assertThat(StepTemplates.step("WireMock: сервер поднят (:8080)"))
                    .isNotEqualTo(StepTemplates.step("WireMock: сервер поднят"));
        }

        @Test
        void кратностьПроверкиМокаОтличаетсяОтЕёОтсутствия() {
            assertThat(StepTemplates.step("Проверка обращений к заглушке"))
                    .isEqualTo("Проверка обращений к заглушке")
                    .isNotEqualTo(StepTemplates.step("Проверка обращений к заглушке (×2)"));
        }
    }

    @Nested
    @DisplayName("вложения")
    class Attachments {

        @Test
        void типВходитВВид() {
            // тихая деградация притти-JSON: имя то же, тип другой — ОБЯЗАН быть другой вид
            assertThat(StepTemplates.attachment("HTTP Response Body", "application/json", true))
                    .isEqualTo("HTTP Response Body | application/json")
                    .isNotEqualTo(StepTemplates.attachment("HTTP Response Body", "text/plain", true));
        }

        @Test
        @DisplayName("пустое содержимое — отдельный вид (имя и тип на месте, а толку нет)")
        void пустоеСодержимоеОтдельныйВид() {
            assertThat(StepTemplates.attachment("DB Result", "text/plain", false))
                    .isEqualTo("DB Result | text/plain | пусто")
                    .isNotEqualTo(StepTemplates.attachment("DB Result", "text/plain", true));
        }

        @Test
        void пустойТипНеЛомает() {
            assertThat(StepTemplates.attachment("Свойства", null, true)).isEqualTo("Свойства | -");
        }
    }

    @Nested
    @DisplayName("fallback для непокрытых имён")
    class Fallback {

        @Test
        void маскируетХэшиUuidИПути() {
            assertThat(StepTemplates.step("Странный шаг com.acme.Thing@1a2b3c4d"))
                    .isEqualTo("Странный шаг com.acme.Thing@<HASH>");
            assertThat(StepTemplates.step("Файл /Users/ai/projects/x/y.txt")).isEqualTo("Файл <PATH>");
        }

        @Test
        void неМаскируетЧислаБезЕдиницИзмерения() {
            // глобального «\d+ → <N>» нет: иначе HTTP-статусы и SQL схлопнулись бы
            assertThat(StepTemplates.step("Шаг с числом 200")).isEqualTo("Шаг с числом 200");
        }

        @Test
        void длинноеИмяОбрезается() {
            String out = StepTemplates.step("x".repeat(400));
            assertThat(out).hasSize(161).endsWith("…");
        }

        @Test
        @DisplayName("два разных длинных имени не схлопываются обрезкой в один вид")
        void длинныеИменаОстаютсяРазными() {
            assertThat(StepTemplates.step("Шаг A " + "x".repeat(400)))
                    .isNotEqualTo(StepTemplates.step("Шаг B " + "x".repeat(400)));
        }
    }
}
