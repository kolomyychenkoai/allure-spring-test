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

    private record Rule(Pattern pattern, String diagnosis) {
    }

    private static final List<Rule> DEFECTS = List.of(
            // Demo$$Lambda/0x00007f…@1a2b3c, CGLIB-подклассы
            rule("\\$\\$", "синтетическое имя (лямбда/CGLIB) — почини рендер в AllureAdviceSupport.safe"),
            // Class@1a2b3c4d — toString не переопределён либо мы печатаем не то
            rule("@[0-9a-f]{6,}\\b", "identity-хэш вместо значения"),
            // [B@…, [I@…, [Ljava.lang.String;@…
            rule("\\[[BIJSCDFZ]@|\\[L[\\w.]+;@", "toString массива вместо поэлементного вывода"),
            rule("\\$Proxy\\d|HibernateProxy", "динамический прокси вместо доменного значения"),
            // техножаргон Awaitility: «alias X defined as …» — человеку читать нечего
            rule(" defined as ", "необработанное описание Awaitility"));

    private StepNameHygiene() {
    }

    /** Диагноз, если имя грязное; иначе пусто. */
    public static Optional<String> defect(String rawName) {
        if (rawName == null || rawName.isBlank()) {
            return Optional.empty();
        }
        return DEFECTS.stream()
                .filter(rule -> rule.pattern().matcher(rawName).find())
                .findFirst()
                .map(Rule::diagnosis);
    }

    private static Rule rule(String regex, String diagnosis) {
        return new Rule(Pattern.compile(regex), diagnosis);
    }
}
