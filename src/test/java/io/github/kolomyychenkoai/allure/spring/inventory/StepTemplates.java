package io.github.kolomyychenkoai.allure.spring.inventory;

import java.util.List;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Имя шага/вложения → ШАБЛОН ВИДА для инвентаря отчёта ({@link ReportInventoryCheck}).
 * Чистая функция: ни I/O, ни Allure, ни JUnit — тестируется отдельно
 * ({@code unit/StepTemplatesTest}).
 * <p>
 * <b>Философия нормализации.</b> Маскируем ТОЛЬКО то, что порождает рантайм:
 * порты, миллисекунды, хэши, сгенерённые id сущностей, счётчики, сравниваемые ЗНАЧЕНИЯ.
 * НЕ маскируем идентификаторы контракта, по которым видно, что модуль жив:
 * HTTP-метод, путь и статус; SQL-операцию и имя таблицы; имя оператора ассерта
 * ({@code isEqualTo} vs {@code contains}); класс.метод в {@code DB …}/{@code Мок-…};
 * топик Kafka; текст сообщения ассерта.
 * <p>
 * <b>Анти-правила</b> (каждое покрыто тестом на то, что оно НЕ применяется):
 * нет глобального {@code \d+ → <N>} (убил бы {@code → 200}, {@code assertEquals});
 * нет схлопывания {@code SQL INSERT widget} до {@code SQL <OP> <TABLE>};
 * нет схлопывания разных операторов AssertJ в один {@code <OP>}.
 * Переусердствовавшая регулярка делает детектор ВЕЧНО-ЗЕЛЁНЫМ — это худший исход,
 * хуже отсутствия детектора.
 */
public final class StepTemplates {

    /** Предел длины шаблона: имена шагов бывают многострочными (AssertJ-сравнение JSON). */
    private static final int MAX_LEN = 160;

    private record Rule(Pattern pattern, Function<Matcher, String> template) {
    }

    /** Порядок ЗНАЧИМ: выигрывает первое совпавшее правило. */
    private static final List<Rule> RULES = List.of(
            // ── HTTP (MockMvc / RestAssured / RestTemplate / RestClient / WebTestClient) ──
            // путь и статус — литералы теста, сохраняем; маскируем только значения query
            rule("^HTTP ([A-Z]+) ([^ ?]+)(\\?\\S*)? → (\\d{3})$",
                    m -> "HTTP " + m.group(1) + " " + m.group(2) + query(m.group(3)) + " → " + m.group(4)),
            rule("^Запрос к заглушке: ([A-Z]+) ([^ ?]+)(\\?\\S*)? → (\\d{3})$",
                    m -> "Запрос к заглушке: " + m.group(1) + " " + m.group(2) + query(m.group(3)) + " → " + m.group(4)),

            // ── WireMock ──
            // наличие/отсутствие порта — РАЗНЫЕ виды (есть тест деградации https-only без порта)
            rule("^(WireMock: [^(]+) \\(:\\d+\\)$", m -> m.group(1) + " (:<PORT>)"),
            rule("^Проверка обращений к заглушке \\(×\\d+\\)$", m -> "Проверка обращений к заглушке (×<N>)"),

            // ── Kafka ──
            rule("^Kafka: отправлено → (\\S+) \\[(.*)]$", m -> "Kafka: отправлено → " + m.group(1) + " [<KEY>]"),
            rule("^Kafka: получено \\d+ сообщ\\.$", m -> "Kafka: получено <N> сообщ."),

            // ── Liquibase ──
            rule("^(.*)Liquibase: схема БД \\(\\d+ changeset\\)$", m -> m.group(1) + "Liquibase: схема БД (<N> changeset)"),

            // ── Awaitility ──
            rule("^Ожидание: (.+) — выполнено за \\d+ мс$", m -> "Ожидание: " + m.group(1) + " — выполнено за <MS> мс"),

            // ── Mockito ── класс.метод сохраняем, аргументы и возврат — payload
            // args РЕЛУКТАНТНО: жадное «(.*)» съело бы и хвост «) (ожидали ×1)»
            rule("^Мок-(заглушка|вызов|проверка): ([\\w$.]+)\\((.*?)\\)(?: → (.*?))?( \\(ожидали ×\\d+\\))?$",
                    m -> "Мок-" + m.group(1) + ": " + m.group(2) + "(<ARGS>)"
                            + (m.group(4) == null ? "" : " → <V>")
                            + (m.group(5) == null ? "" : " (ожидали ×<N>)")),

            // ── RestAssured .then() ── ключевое слово = перегрузка из канарейки, дальше значения
            // без «\b»: по умолчанию он ASCII-шный и после кириллицы границы не видит
            rule("^Проверка ответа: (статус|тип содержимого|тело|время ответа|заголовок|cookie)(?: .*)?$",
                    m -> "Проверка ответа: " + m.group(1) + " <V>"),

            // ── Ассерты ──
            // Hamcrest: наличие reason различает 2-арг и 3-арг перегрузку — структуру храним
            rule("^Проверка: (.+): значение (.+), ожидалось (.+)$", m -> "Проверка: <REASON>: значение <V>, ожидалось <MATCHER>"),
            rule("^Проверка: значение (.+), ожидалось (.+)$", m -> "Проверка: значение <V>, ожидалось <MATCHER>"),
            // AssertJ: ОПЕРАТОР — это и есть покрытие, сохраняем; арность (есть операнд или нет) тоже
            rule("^Проверка: значение (.+) — (\\p{L}[\\p{L}\\d]*)( .+)?$",
                    m -> "Проверка: значение <V> — " + m.group(2) + (m.group(3) == null ? "" : " <V>")),
            // Jupiter: assertNotNull / assertInstanceOf
            rule("^Проверка: значение (.+) не null$", m -> "Проверка: значение <V> не null"),
            // тот же ассерт с сообщением (Spring AssertionErrors.assertNotNull)
            rule("^Проверка: (.+) — значение (.+) не null$", m -> "Проверка: " + m.group(1) + " — значение <V> не null"),
            rule("^Проверка: брошено (.+)$", m -> "Проверка: брошено <TYPE>"),
            // сообщение ассерта — литерал теста (Status, JSON path "$.x", «имя продукта»), сохраняем:
            // по нему видно, какой ИМЕННО вид проверки жив. Маскируем только сравниваемые значения.
            rule("^Проверка: (.+) — ожидалось (.+) = (.+)$", m -> "Проверка: " + m.group(1) + " — ожидалось <V> = <V>"),
            rule("^Проверка: ожидалось (.+) = (.+)$", m -> "Проверка: ожидалось <V> = <V>"),
            rule("^Проверка: (.+) — (верно|неверно)$", m -> "Проверка: " + m.group(1) + " — " + m.group(2))
    );

    // Fallback-маскеры: только для имён, не покрытых правилами выше.
    private static final Pattern UUID = Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final Pattern HASH = Pattern.compile("@[0-9a-f]{4,}\\b");
    private static final Pattern PORT = Pattern.compile("\\(:\\d{2,5}\\)");
    private static final Pattern MILLIS = Pattern.compile("\\b\\d+([.,]\\d+)? мс\\b");
    private static final Pattern TIMESTAMP = Pattern.compile("\\d{4}-\\d{2}-\\d{2}[T ]\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?");
    private static final Pattern FILE_PATH = Pattern.compile("(/Users|/home|/var|/tmp|file:)\\S+");

    private StepTemplates() {
    }

    /** Имя шага → шаблон вида. */
    public static String step(String rawName) {
        String s = collapse(rawName);
        for (Rule r : RULES) {
            Matcher m = r.pattern().matcher(s);
            if (m.matches()) {
                return truncate(r.template().apply(m));
            }
        }
        return truncate(generic(s));
    }

    /**
     * Вид вложения = имя + mime. Тип обязателен: тихая деградация
     * {@code application/json → text/plain} (сломался притти-принт) иначе пройдёт незамеченной.
     */
    public static String attachment(String name, String type) {
        return step(name == null ? "" : name) + " | " + (type == null || type.isBlank() ? "-" : type);
    }

    private static Rule rule(String regex, Function<Matcher, String> template) {
        return new Rule(Pattern.compile(regex, Pattern.DOTALL), template);
    }

    /** Многострочные имена (AssertJ-сравнение JSON) схлопываем в одну строку. */
    private static String collapse(String raw) {
        return raw == null ? "" : raw.replaceAll("\\s+", " ").trim();
    }

    private static String query(String rawQuery) {
        return rawQuery == null ? "" : "?<QUERY>";
    }

    private static String generic(String s) {
        String out = UUID.matcher(s).replaceAll("<UUID>");
        out = HASH.matcher(out).replaceAll("@<HASH>");
        out = PORT.matcher(out).replaceAll("(:<PORT>)");
        out = MILLIS.matcher(out).replaceAll("<MS> мс");
        out = TIMESTAMP.matcher(out).replaceAll("<TS>");
        out = FILE_PATH.matcher(out).replaceAll("<PATH>");
        return out;
    }

    private static String truncate(String s) {
        return s.length() <= MAX_LEN ? s : s.substring(0, MAX_LEN) + "…";
    }
}
