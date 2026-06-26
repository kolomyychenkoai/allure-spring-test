package io.github.kolomyychenkoai.allure.spring.awaitility.internal;

import io.github.kolomyychenkoai.allure.spring.internal.AllureInstrumentationLogger;
import io.qameta.allure.Allure;
import org.awaitility.core.ConditionEvaluationListener;
import org.awaitility.core.EvaluatedCondition;

/**
 * Слушатель Awaitility: когда условие ожидания ВЫПОЛНИЛОСЬ, пишет в отчёт шаг
 * «Ожидание: &lt;описание&gt; — выполнено за N мс». Без кода в тестах — регистрируется
 * глобально из {@code AllureAwaitilityListener} через официальный SPI Awaitility
 * ({@code Awaitility.setDefaultConditionEvaluationListener}).
 * <p>
 * Ожидание Awaitility блокирует ТЕСТ-поток и опрашивает условие на нём же — активный
 * Allure-кейс на месте, шаг пишется сразу (буфер/afterTestMethod не нужны).
 * <p>
 * Шаг — ТОЛЬКО на успешно выполненное ожидание ({@code isSatisfied()}); промежуточные
 * не-выполненные опросы не логируем. Таймаут шага НЕ даёт — падение покажет Allure на уровне
 * теста (как у всех модулей). Всё в try/catch — сбой инструментирования не роняет тест.
 */
public final class AllureAwaitilityConditionListener implements ConditionEvaluationListener<Object> {

    @Override
    public void conditionEvaluated(EvaluatedCondition<Object> condition) {
        try {
            // только выполненное условие и только при активном тест-кейсе
            if (condition == null || !condition.isSatisfied()
                    || !Allure.getLifecycle().getCurrentTestCase().isPresent()) {
                return;
            }
            String description = condition.getDescription();
            // имя шага — краткое и читаемое; ПОЛНОЕ описание условия (что именно ждали) кладём
            // во вложение — детали по запросу, как HTTP Request/SQL у других модулей
            Allure.step(stepName(description, condition.getElapsedTimeInMS()), step -> {
                if (description != null && !description.isBlank()) {
                    Allure.addAttachment("Условие ожидания", "text/plain", description);
                }
            });
        } catch (Throwable t) {
            AllureInstrumentationLogger.warn("Awaitility", t); // не роняем тест, сбой видно на WARNING
        }
    }

    /**
     * Человекочитаемое имя шага. Сырой {@code getDescription()} у Awaitility — технический текст
     * («Condition with alias X defined as a Lambda expression in &lt;FQCN&gt; returned true»),
     * для ручной приёмки это мусор. Берём только АЛИАС (то, что задал тест в {@code await("…")}):
     * с алиасом — «Ожидание: &lt;алиас&gt; — выполнено за N мс»; без — нейтральное «Ожидание
     * выполнено за N мс» (без лямбды/FQCN). Парсинг детерминированный, как {@code pathAndQuery}
     * в HTTP-модуле; формат описания — Awaitility 4.x.
     */
    private static String stepName(String description, long elapsedMs) {
        String alias = alias(description);
        return alias != null
                ? "Ожидание: " + alias + " — выполнено за " + elapsedMs + " мс"
                : "Ожидание выполнено за " + elapsedMs + " мс";
    }

    /** Алиас из описания Awaitility («Condition with alias &lt;ALIAS&gt; defined as …») или null. */
    private static String alias(String description) {
        if (description == null) {
            return null;
        }
        int from = description.indexOf("alias ");
        if (from < 0) {
            return null; // алиас не задан — без него лямбду в отчёт не тащим
        }
        from += "alias ".length();
        int to = description.indexOf(" defined as", from);
        String alias = (to < 0 ? description.substring(from) : description.substring(from, to)).trim();
        return alias.isEmpty() ? null : alias;
    }
}
