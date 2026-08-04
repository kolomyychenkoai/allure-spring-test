package io.github.kolomyychenkoai.allure.spring.inventory;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Гигиена ИМЁН шагов и вложений: в отчёте, который принимают вручную, не должно быть
 * технического мусора — хэшей, синтетических имён лямбд, {@code toString} массивов.
 * <p>
 * Проверяется по СЫРЫМ именам, до нормализации: {@link StepTemplates} сама маскирует хэши и
 * порты (`@<HASH>`, `(:<PORT>)`), то есть частично ПРЯЧЕТ ровно то, что здесь ищется.
 * <p>
 * Раньше такой страж был ровно у одного модуля ({@code demo/AwaitilityReportIT}). Мусор же
 * рождается в общей точке рендера значений ({@code AllureAdviceSupport.safe}) и течёт во все
 * модули сразу — поэтому и страж общий.
 * <p>
 * На момент включения нарушителей НОЛЬ (проверено по всем именам прогона), так что гейт
 * заводится «в зелёном» и без единой глушилки. Появился мусор — чинить рендер в {@code src/main},
 * а не добавлять исключение сюда.
 */
public final class StepNameHygiene {

    /**
     * @param namesOnly правило про ФОРМУ ИМЕНИ, а не про мусор как таковой — к телам вложений
     *                  не применяется (там развёрнутое техническое описание бывает по делу)
     */
    private record Rule(Pattern pattern, String diagnosis, boolean namesOnly) {
    }

    // Порядок ЗНАЧИМ: выигрывает первое совпавшее правило, и оно же станет диагнозом. Правила
    // пересекаются («[B@4a3f2b1c» — и массив, и identity-хэш), поэтому УЗКИЕ идут раньше общих:
    // «toString массива» говорит, что чинить, а «identity-хэш» — только что сломано.
    private static final List<Rule> DEFECTS = List.of(
            // Demo$$Lambda/0x00007f…@1a2b3c, CGLIB-подклассы
            rule("\\$\\$", "синтетическое имя (лямбда/CGLIB) — почини рендер в AllureAdviceSupport.safe"),
            // [B@…, [I@…, [Ljava.lang.String;@… — частный случай identity-хэша, поэтому ВЫШЕ него
            rule("\\[[BIJSCDFZ]@|\\[L[\\w.]+;@", "toString массива вместо поэлементного вывода"),
            // Class@1a2b3c4d — toString не переопределён либо мы печатаем не то
            rule("@[0-9a-f]{6,}\\b", "identity-хэш вместо значения"),
            rule("\\$Proxy\\d|HibernateProxy", "динамический прокси вместо доменного значения"),
            // Техножаргон Awaitility: «alias X defined as …». В ИМЕНИ шага читать нечего, поэтому
            // модуль достаёт оттуда алиас. А во ВЛОЖЕНИИ «Условие ожидания» сырое описание лежит
            // НАМЕРЕННО (AllureAwaitilityConditionListener) — это источник правды, не мусор.
            // Отсюда namesOnly: правило про форму имени, а не про мусор как таковой.
            nameRule(" defined as ", "необработанное описание Awaitility"));

    private StepNameHygiene() {
    }

    /** Диагноз, если ИМЯ грязное; иначе пусто. Применяются ВСЕ правила. */
    public static Optional<String> defect(String rawName) {
        return find(DEFECTS, rawName).map(Rule::diagnosis);
    }

    /**
     * Диагноз, если грязное ТЕЛО вложения; иначе пусто.
     * <p>
     * Правила с {@code namesOnly} исключены: они про форму ИМЕНИ шага (его читают беглым взглядом
     * в дереве), а во вложении развёрнутое техническое описание — это содержание, за которым туда
     * и заглядывают. Структурный мусор ({@code [B@…}, {@code $$Lambda}, identity-хэш, прокси)
     * не в порядке НИГДЕ, и эти правила действуют в обоих местах.
     */
    public static Optional<String> bodyDefect(String body) {
        return find(STRUCTURAL, body).map(Rule::diagnosis);
    }

    /**
     * Позиция первого структурного нарушения в теле либо {@code 0}. Нужна показу: тело целиком
     * печатать нечего, поэтому вырезаем кусок ВОКРУГ находки.
     */
    public static int bodyDefectAt(String body) {
        return find(STRUCTURAL, body)
                .map(rule -> {
                    java.util.regex.Matcher m = rule.pattern().matcher(body);
                    return m.find() ? m.start() : 0;
                })
                .orElse(0);
    }

    /** Правила, действующие ВЕЗДЕ — и в именах, и в телах. */
    private static final List<Rule> STRUCTURAL = DEFECTS.stream().filter(rule -> !rule.namesOnly()).toList();

    private static Optional<Rule> find(List<Rule> rules, String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        return rules.stream().filter(rule -> rule.pattern().matcher(text).find()).findFirst();
    }

    private static Rule rule(String regex, String diagnosis) {
        return new Rule(Pattern.compile(regex), diagnosis, false);
    }

    /** Правило только для имён — см. {@link #bodyDefect}. */
    private static Rule nameRule(String regex, String diagnosis) {
        return new Rule(Pattern.compile(regex), diagnosis, true);
    }
}
