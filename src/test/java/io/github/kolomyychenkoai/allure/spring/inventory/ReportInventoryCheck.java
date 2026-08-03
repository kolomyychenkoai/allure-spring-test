package io.github.kolomyychenkoai.allure.spring.inventory;

import io.qameta.allure.Epic;
import io.github.kolomyychenkoai.allure.spring.inventory.ReportInventory.Baseline;
import io.github.kolomyychenkoai.allure.spring.inventory.ReportInventory.Kind;
import io.github.kolomyychenkoai.allure.spring.inventory.ReportInventory.Scan;
import io.github.kolomyychenkoai.allure.spring.inventory.ReportInventory.UpdateVerdict;
import io.github.kolomyychenkoai.allure.spring.inventory.ReportInventory.Verdict;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Регресс-детектор «отчёт молча обеднел»: сверяет ВИДЫ шагов и вложений, произведённые прогоном,
 * с закоммиченным эталоном {@code src/test/inventory/report-inventory.txt}.
 * <p>
 * Зачем: библиотека ломается ТИХО. При апгрейде Java/Spring матчер просто перестаёт совпадать —
 * ошибок нет, шаг исчезает, все остальные тесты зелёные. Адресные тесты ловят это только там,
 * где кто-то заранее написал проверку; этот — везде.
 * <p>
 * Класс намеренно тонкий: считает {@link ReportInventory#verdict} и рисует сообщение. Вся логика
 * решения — чистые функции в {@link ReportInventory}, покрытые {@code unit/ReportInventoryTest}
 * (сломанный гейт = вечно-зелёный детектор, поэтому гейты обязаны быть под тестами).
 * <p>
 * <b>Гоняется ВТОРЫМ прогоном surefire, в отдельной JVM</b> (профиль {@code report-inventory}):
 * к этому моменту основной прогон завершён и все {@code *-result.json} на диске. Имя класса не
 * подпадает под {@code *Test}/{@code *Tests}/{@code *IT}, и основной прогон его дополнительно
 * исключает — двойного запуска нет.
 */
@Epic("Внутренние проверки библиотеки")
class ReportInventoryCheck {

    private static final String UPDATE_FLAG = "inventory.update";
    private static final String REMOVE_FLAG = "inventory.remove";
    private static final String STRICT_FLAG = "inventory.strict";
    /** Пересев маркеров кратности «×N» по замеру прогона. */
    private static final String COUNTS_FLAG = "inventory.counts";
    /** Каталог дампов — одна константа на двоих с {@link InstrumentationDiagnosticsDump}. */
    private static final Path DIAGNOSTICS_DIR = InstrumentationDiagnosticsDump.DIR;

    @Test
    @DisplayName("инвентарь видов шагов и вложений совпадает с эталоном (ни один модуль не отвалился молча)")
    void inventoryMatchesBaseline() {
        Path resultsDir = Path.of(System.getProperty("allure.results.directory", "target/allure-results"));
        Scan scan = ReportInventory.scan(resultsDir);
        Baseline baseline = ReportInventory.readBaseline(ReportInventory.BASELINE);

        if (Boolean.getBoolean(UPDATE_FLAG)) {
            updateBaseline(scan, baseline);
        }

        Verdict verdict = ReportInventory.verdict(scan, baseline);
        if (verdict.noData()) {
            throw new AssertionError("""
                    ИНВЕНТАРЬ ОТЧЁТА: нет данных — проверять нечего, значит проверка НЕДЕЙСТВИТЕЛЬНА.
                    Каталог: %s (result.json от демо-классов: 0)
                    Причины: инвентарь стартовал РАНЬШЕ основного прогона (порядок executions surefire)
                    либо каталог результатов не заполнен (см. execution wipe-allure-results)."""
                    .formatted(resultsDir));
        }
        if (baseline.steps().isEmpty() && baseline.attachments().isEmpty()) {
            throw new AssertionError("ИНВЕНТАРЬ ОТЧЁТА: эталон " + ReportInventory.BASELINE
                    + " пуст или отсутствует. Создай: mvn clean test -D" + UPDATE_FLAG + "=true");
        }

        // Оба гейта безусловные и НЕ зависят от эталона: это не «отчёт стал другим»,
        // а «отчёт сломан» — обновлением эталона такое не лечится.
        if (!scan.missingFiles().isEmpty()) {
            throw new AssertionError("""
                    ВЛОЖЕНИЯ БЕЗ ФАЙЛА (%d): вложение объявлено в result.json, а байты не записаны —
                    в отчёте оно откроется пустым. Инвентарь гоняется вторым прогоном, всё уже
                    сброшено на диск, так что это дефект, а не гонка.
                    %s""".formatted(scan.missingFiles().size(), "  " + String.join("\n  ", scan.missingFiles())));
        }
        if (!scan.dirtyNames().isEmpty()) {
            throw new AssertionError("""
                    ТЕХНИЧЕСКИЙ МУСОР В ИМЕНАХ (%d): отчёт принимают ВРУЧНУЮ, хэши и синтетические
                    имена в шагах читать невозможно. Чинить рендер значения (AllureAdviceSupport.safe),
                    а не добавлять исключение в StepNameHygiene.
                    %s""".formatted(scan.dirtyNames().size(), "  " + String.join("\n  ", scan.dirtyNames())));
        }
        if (!scan.unreadable().isEmpty()) {
            System.out.println("ИНВЕНТАРЬ: нечитаемые файлы результатов (пропущены, диагноз мог поехать):\n  "
                    + String.join("\n  ", scan.unreadable()));
        }
        if (!verdict.extras().isEmpty()) {
            System.out.println(renderExtra(verdict));
        }
        String instrumentation = InstrumentationFailures.report(DIAGNOSTICS_DIR);
        if (verdict.failed(Boolean.getBoolean(STRICT_FLAG)) || instrumentation != null) {
            throw new AssertionError(render(verdict, scan, baseline, instrumentation));
        }
    }

    /**
     * Перезапись эталона. ВСЕГДА падает после записи: так флаг физически невозможно оставить
     * включённым в автоматическом прогоне — сборка с ним никогда не зелёная, а обновление
     * становится отдельным человеческим шагом (diff → ревью → коммит).
     */
    private void updateBaseline(Scan scan, Baseline baseline) {
        UpdateVerdict update = ReportInventory.updateVerdict(scan, baseline);
        if (update.partialRun()) {
            throw new AssertionError("""
                    ИНВЕНТАРЬ ОТЧЁТА: частичный прогон — обновление эталона ЗАПРЕЩЕНО.
                    Не запускались классы: %s
                    Иначе затрём виды модулей, которых просто не было в прогоне.
                    Гоняй полный сьют: mvn clean test -D%s=true"""
                    .formatted(String.join(", ", update.lostOwners()), UPDATE_FLAG));
        }
        // Удаление видов — отдельное решение: иначе красную сборку можно «починить» обновлением
        // эталона, не заметив, что сетка распустилась. Это самый вероятный способ тихо её потерять.
        if (!update.removed().isEmpty() && !Boolean.getBoolean(REMOVE_FLAG)) {
            StringBuilder out = new StringBuilder("""
                    ИНВЕНТАРЬ ОТЧЁТА: обновление УДАЛИЛО БЫ виды из эталона — это не обновление, а
                    сокращение сетки. Убедись, что каждый вид исчез ОСОЗНАННО, и повтори с
                    -D%s=true -D%s=true

                    Исчезают (%d):
                    """.formatted(UPDATE_FLAG, REMOVE_FLAG, update.removed().size()));
            update.removed().forEach(kind -> out.append("  − ").append(kind).append('\n'));
            throw new AssertionError(out.toString());
        }
        // Посев кратности по ЗАМЕРУ: -Dinventory.counts=true проставит «×N» тем видам, чьё число
        // стабильно во всех кейсах, и снимет маркер с тех, что стали плавать.
        if (Boolean.getBoolean(COUNTS_FLAG)) {
            // Ослабление кратности — такой же способ распустить сетку, как удаление вида,
            // поэтому изменения маркеров печатаем поимённо, а не прячем в diff из 200 строк.
            Map<Kind, ReportInventory.Count> seeded = ReportInventory.seedCounts(scan, baseline);
            baseline.counts().forEach((kind, was) -> {
                ReportInventory.Count now = seeded.get(kind);
                if (now == null || !now.toString().equals(was.toString())) {
                    System.out.println("ИНВЕНТАРЬ: кратность " + kind + " — было " + was
                            + ", стало " + (now == null ? "без маркера" : now));
                }
            });
        }
        Baseline toWrite = Boolean.getBoolean(COUNTS_FLAG)
                ? new Baseline(baseline.steps(), baseline.attachments(), baseline.optional(),
                        baseline.comments(), ReportInventory.seedCounts(scan, baseline))
                : baseline;
        ReportInventory.write(ReportInventory.BASELINE, scan, toWrite);
        // Колонка «кто отвечает» — весь смысл формата: она печатается при пропаже вида как
        // указатель, куда идти. Новые виды приходят голыми, поэтому напоминаем сразу.
        long unsigned = java.util.stream.Stream.concat(scan.steps().stream(), scan.attachments().stream())
                .filter(kind -> !baseline.comments().containsKey(kind))
                .count();
        if (unsigned > 0) {
            System.out.println("ИНВЕНТАРЬ: у " + unsigned + " видов нет подписи «# кто отвечает» — "
                    + "допиши их в эталоне, иначе при пропаже он не скажет, куда идти.");
        }
        throw new AssertionError("""
                ИНВЕНТАРЬ ОТЧЁТА: эталон перезаписан (%d видов шагов, %d вложений; удалено %d).
                Прогон намеренно помечен упавшим: проверь `git diff %s` и закоммить осознанно."""
                .formatted(scan.steps().size(), scan.attachments().size(), update.removed().size(),
                        ReportInventory.BASELINE));
    }

    private String render(Verdict verdict, Scan scan, Baseline baseline, String instrumentation) {
        StringBuilder out = new StringBuilder();
        out.append("ИНВЕНТАРЬ ОТЧЁТА: пропало видов — ").append(verdict.missingCount())
                .append(verdict.missingCount() > 0 ? " (модуль отвалился МОЛЧА)" : "").append('\n')
                .append("Прогон: классов-витрин ").append(scan.owners().size())
                .append(", result.json ").append(scan.resultFiles()).append('\n');

        if (instrumentation != null) {
            out.append('\n').append(instrumentation);
        }
        if (!verdict.unknownOwners().isEmpty()) {
            out.append("\nМОДУЛИ ВНЕ СЕТКИ (класс-витрина есть, в эталоне его нет — виды никто не стережёт):\n");
            verdict.unknownOwners().forEach(owner -> out.append("  ? ").append(owner).append('\n'));
            out.append("  Внеси их в эталон: mvn clean test -D").append(UPDATE_FLAG).append("=true\n");
        }
        if (!verdict.silentOwners().isEmpty()) {
            out.append("\nКЛАССЫ БЕЗ РЕЗУЛЬТАТОВ (не запускались или упали до первого шага):\n");
            verdict.silentOwners().forEach(owner -> out.append("  ✗ ").append(owner).append('\n'));
        }

        if (!verdict.countMismatches().isEmpty()) {
            out.append("\nКРАТНОСТЬ РАЗЪЕХАЛАСЬ — либо перехват ЗАДВОИЛСЯ, либо витрина изменилась осознанно:\n");
            verdict.countMismatches().forEach(mismatch -> out.append("  × ").append(mismatch.kind())
                    .append(" — ждали ").append(mismatch.expected())
                    .append(" в каждом кейсе, увидели ").append(mismatch.seen())
                    .append(responsible(baseline, mismatch.kind())).append('\n'));
        }

        Map<Kind, String> drift = ReportInventory.mimeDrift(verdict.missingAttachments(), scan);
        Map<String, List<String>> byOwner = new TreeMap<>();
        verdict.missingSteps().forEach(k -> byOwner.computeIfAbsent(k.owner(), o -> new ArrayList<>())
                .add("    ✗ шаг      " + k.text() + responsible(baseline, k)));
        verdict.missingAttachments().forEach(k -> {
            String line = "    ✗ вложение " + k.text() + responsible(baseline, k);
            if (drift.containsKey(k)) {
                line += "\n        (вложение с таким именем ЕСТЬ, но «" + drift.get(k) + "» — тихая деградация)";
            }
            byOwner.computeIfAbsent(k.owner(), o -> new ArrayList<>()).add(line);
        });
        if (!byOwner.isEmpty()) {
            out.append('\n');
            byOwner.forEach((owner, lines) -> {
                out.append("  ").append(owner).append(":\n");
                // Пропал ВЕСЬ класс (@Disabled / упал до первого шага) — это «пропал тест», а не
                // «отвалилась инструментация». Не вываливаем десятки строк, даём один диагноз:
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
        if (Boolean.getBoolean(STRICT_FLAG) && !verdict.extras().isEmpty()) {
            out.append("\nСтрогий режим (-D").append(STRICT_FLAG).append("=true): новые виды тоже считаются расхождением.\n");
        }
        out.append("""

                Что делать:
                  1) канарейка InstrumentationApiCanaryTest — уехал ли API чужой библиотеки;
                  2) сбои перехвата — раздел выше (каталог target/instrumentation-diagnostics/);
                  3) если сбоев нет — матчер просто перестал совпадать (типичный апгрейд).
                Если изменение ОСОЗНАННО:
                """);
        out.append("  пропал/появился вид  → mvn clean test -D").append(UPDATE_FLAG)
                .append("=true -D").append(REMOVE_FLAG).append("=true\n");
        if (!verdict.countMismatches().isEmpty()) {
            // Без этого флага маркеры ×N не перезапишутся: обновление пройдёт, git diff останется
            // пустым, а прогон — красным. Рецепт обязан ЧИНИТЬ то падение, под которым напечатан.
            out.append("  изменилась кратность → mvn clean test -D").append(UPDATE_FLAG)
                    .append("=true -D").append(COUNTS_FLAG).append("=true\n");
        }
        out.append("  затем прочитать git diff эталона и закоммитить.\n");
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
    private String responsible(Baseline baseline, Kind kind) {
        String comment = baseline.comments().get(kind);
        return comment == null ? "" : "   → " + comment;
    }

    private String renderExtra(Verdict verdict) {
        StringBuilder out = new StringBuilder("ИНВЕНТАРЬ ОТЧЁТА: новые виды (не падение), всего "
                + verdict.extras().size() + ":\n");
        verdict.extraSteps().forEach(k -> out.append("  + шаг      ").append(k).append('\n'));
        verdict.extraAttachments().forEach(k -> out.append("  + вложение ").append(k).append('\n'));
        out.append("Если это осознанное пополнение отчёта — обнови эталон (-D").append(UPDATE_FLAG).append("=true).\n");
        return out.toString();
    }
}
