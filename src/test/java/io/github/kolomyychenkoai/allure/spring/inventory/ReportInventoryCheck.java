package io.github.kolomyychenkoai.allure.spring.inventory;

import io.github.kolomyychenkoai.allure.spring.inventory.ReportInventory.Baseline;
import io.github.kolomyychenkoai.allure.spring.inventory.ReportInventory.Kind;
import io.github.kolomyychenkoai.allure.spring.inventory.ReportInventory.Scan;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Регресс-детектор «отчёт молча обеднел»: сверяет ВИДЫ шагов и вложений, произведённые прогоном,
 * с закоммиченным эталоном {@code src/test/inventory/report-inventory.txt}.
 * <p>
 * Зачем: библиотека ломается ТИХО. При апгрейде Java/Spring матчер просто перестаёт совпадать —
 * ошибок нет, шаг исчезает, все остальные тесты зелёные. Адресные тесты ловят это только там,
 * где кто-то заранее написал проверку; этот — везде.
 * <p>
 * <b>Гоняется ВТОРЫМ прогоном surefire, в отдельной JVM</b> (execution {@code report-inventory}
 * в pom): к этому моменту основной прогон завершён и все {@code *-result.json} на диске.
 * Имя класса намеренно НЕ подпадает под {@code *Test}/{@code *Tests}/{@code *IT} — поэтому
 * в основной прогон он не попадает и дважды не запускается.
 * <p>
 * Три сигнала об апгрейде не перекрываются:
 * канарейка — «API библиотеки уехал», {@code InstrumentationDiagnostics} — «перехват упал
 * с ошибкой», этот тест — «шаг исчез, а ошибок не было».
 */
class ReportInventoryCheck {

    private static final String UPDATE_FLAG = "inventory.update";
    private static final String STRICT_FLAG = "inventory.strict";

    @Test
    @DisplayName("инвентарь видов шагов и вложений совпадает с эталоном (ни один модуль не отвалился молча)")
    void inventoryMatchesBaseline() {
        Path resultsDir = Path.of(System.getProperty("allure.results.directory", "target/allure-results"));
        Scan scan = ReportInventory.scan(resultsDir);
        Baseline baseline = ReportInventory.readBaseline(ReportInventory.BASELINE);

        // Гейт №1: детектор не имеет права зеленеть на пустых данных.
        if (scan.owners().isEmpty()) {
            throw new AssertionError("""
                    ИНВЕНТАРЬ ОТЧЁТА: нет данных — проверять нечего, а значит проверка недействительна.
                    Каталог: %s (result.json от демо-классов: 0)
                    Причины: инвентарь стартовал РАНЬШЕ основного прогона (порядок executions surefire)
                    либо каталог результатов не заполнен (см. execution wipe-allure-results).""".formatted(resultsDir));
        }

        if (Boolean.getBoolean(UPDATE_FLAG)) {
            updateBaseline(scan, baseline);
        }

        if (baseline.steps().isEmpty() && baseline.attachments().isEmpty()) {
            throw new AssertionError("ИНВЕНТАРЬ ОТЧЁТА: эталон " + ReportInventory.BASELINE
                    + " пуст или отсутствует. Создай: mvn clean test -D" + UPDATE_FLAG + "=true");
        }

        // Гейт №2: класс-владелец не дал НИ ОДНОГО результата — это «пропал тест», а не «пропал вид».
        List<String> silentClasses = baseline.owners().stream()
                .filter(owner -> !scan.owners().contains(owner))
                .toList();

        List<Kind> missingSteps = ReportInventory.missing(baseline.steps(), baseline.optional(), scan, false);
        List<Kind> missingAttachments = ReportInventory.missing(baseline.attachments(), baseline.optional(), scan, true);
        List<Kind> extraSteps = ReportInventory.extra(baseline.steps(), scan.steps());
        List<Kind> extraAttachments = ReportInventory.extra(baseline.attachments(), scan.attachments());

        if (!extraSteps.isEmpty() || !extraAttachments.isEmpty()) {
            System.out.println(renderExtra(extraSteps, extraAttachments));
        }

        boolean strictFail = Boolean.getBoolean(STRICT_FLAG) && (!extraSteps.isEmpty() || !extraAttachments.isEmpty());
        if (!missingSteps.isEmpty() || !missingAttachments.isEmpty() || !silentClasses.isEmpty() || strictFail) {
            throw new AssertionError(render(missingSteps, missingAttachments, silentClasses, scan, baseline, strictFail));
        }
    }

    /**
     * Перезапись эталона. ВСЕГДА падает после записи: так флаг физически невозможно оставить
     * включённым в автоматическом прогоне — сборка с ним никогда не зелёная, а обновление
     * становится отдельным человеческим шагом (diff → ревью → коммит).
     */
    private void updateBaseline(Scan scan, Baseline baseline) {
        Set<String> lost = new TreeSet<>(baseline.owners());
        scan.owners().forEach(lost::remove);
        if (!lost.isEmpty()) {
            throw new AssertionError("""
                    ИНВЕНТАРЬ ОТЧЁТА: частичный прогон — обновление эталона ЗАПРЕЩЕНО.
                    Не запускались классы: %s
                    Иначе затрём виды модулей, которых просто не было в прогоне.
                    Гоняй полный сьют: mvn clean test -D%s=true""".formatted(String.join(", ", lost), UPDATE_FLAG));
        }
        ReportInventory.write(ReportInventory.BASELINE, scan, baseline);
        throw new AssertionError("""
                ИНВЕНТАРЬ ОТЧЁТА: эталон перезаписан (%d видов шагов, %d вложений).
                Прогон намеренно помечен упавшим: проверь `git diff %s` и закоммить осознанно."""
                .formatted(scan.steps().size(), scan.attachments().size(), ReportInventory.BASELINE));
    }

    private String render(List<Kind> missingSteps, List<Kind> missingAttachments, List<String> silentClasses,
                          Scan scan, Baseline baseline, boolean strictFail) {
        int total = missingSteps.size() + missingAttachments.size();
        StringBuilder out = new StringBuilder();
        out.append("ИНВЕНТАРЬ ОТЧЁТА: пропало видов — ").append(total)
                .append(total > 0 ? " (модуль отвалился МОЛЧА)" : "").append('\n')
                .append("Прогон: классов-витрин ").append(scan.owners().size())
                .append(", result.json ").append(scan.resultFiles()).append('\n');

        if (!silentClasses.isEmpty()) {
            out.append("\nКЛАССЫ БЕЗ РЕЗУЛЬТАТОВ (не запускались или упали до первого шага):\n");
            silentClasses.forEach(c -> out.append("  ✗ ").append(c).append('\n'));
        }

        Map<Kind, String> drift = ReportInventory.mimeDrift(missingAttachments, scan);
        Map<String, List<String>> byOwner = new TreeMap<>();
        missingSteps.forEach(k -> byOwner.computeIfAbsent(k.owner(), o -> new java.util.ArrayList<>())
                .add("    ✗ шаг      " + k.text() + owner(baseline, k)));
        missingAttachments.forEach(k -> {
            String line = "    ✗ вложение " + k.text() + owner(baseline, k);
            if (drift.containsKey(k)) {
                line += "\n        (вложение с таким именем ЕСТЬ, но тип " + drift.get(k) + " — тихая деградация)";
            }
            byOwner.computeIfAbsent(k.owner(), o -> new java.util.ArrayList<>()).add(line);
        });
        if (!byOwner.isEmpty()) {
            out.append('\n');
            byOwner.forEach((owner, lines) -> {
                out.append("  ").append(owner).append(":\n");
                // Пропал ВЕСЬ класс (@Disabled / упал до первого шага) — это «пропал тест»,
                // а не «пропала инструментация». Не вываливаем десятки строк, даём один диагноз:
                // @Disabled-класс всё равно пишет result.json, поэтому по списку владельцев не видно.
                int expected = countOwned(baseline, owner);
                if (expected > 0 && lines.size() == expected) {
                    out.append("    ✗ НЕ ДАЛ НИ ОДНОГО вида из ").append(expected)
                            .append(" — класс пропущен (@Disabled) либо упал до первого шага.\n")
                            .append("      Это «пропал тест», а не «отвалилась инструментация».\n");
                } else {
                    lines.forEach(l -> out.append(l).append('\n'));
                }
            });
        }
        if (strictFail) {
            out.append("\nСтрогий режим (-D").append(STRICT_FLAG).append("=true): новые виды тоже считаются расхождением.\n");
        }
        out.append("""

                Что делать:
                  1) канарейка InstrumentationApiCanaryTest — уехал ли API чужой библиотеки;
                  2) InstrumentationDiagnostics в логе прогона — падал ли перехват с ошибкой;
                  3) если ошибок нет — матчер просто перестал совпадать (типичный апгрейд).
                Если пропажа ОСОЗНАННА: mvn clean test -D""").append(UPDATE_FLAG).append("=true, затем закоммить эталон.\n");
        return out.toString();
    }

    /** Сколько видов эталон ждёт от этого класса (шаги + вложения). */
    private int countOwned(Baseline baseline, String owner) {
        return (int) java.util.stream.Stream.concat(baseline.steps().stream(), baseline.attachments().stream())
                .filter(k -> k.owner().equals(owner))
                .filter(k -> !baseline.optional().contains(k))
                .count();
    }

    /** Подпись «за это отвечает такой-то класс» из комментария эталона. */
    private String owner(Baseline baseline, Kind kind) {
        String comment = baseline.comments().get(kind);
        return comment == null ? "" : "   → " + comment;
    }

    private String renderExtra(List<Kind> steps, List<Kind> attachments) {
        StringBuilder out = new StringBuilder("ИНВЕНТАРЬ ОТЧЁТА: новые виды (не падение), всего "
                + (steps.size() + attachments.size()) + ":\n");
        steps.forEach(k -> out.append("  + шаг      ").append(k).append('\n'));
        attachments.forEach(k -> out.append("  + вложение ").append(k).append('\n'));
        out.append("Если это осознанное пополнение отчёта — обнови эталон (-D").append(UPDATE_FLAG).append("=true).\n");
        return out.toString();
    }
}
