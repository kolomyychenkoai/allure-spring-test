package io.github.kolomyychenkoai.allure.spring.inventory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Второй сигнал об апгрейде как ГЕЙТ: читает дамп {@link InstrumentationDiagnosticsDump}
 * и говорит, срывались ли трансформации в основном прогоне.
 * <p>
 * Без этого счётчик сбоев был бы не сигналом, а строчкой в логе, который никто не читает:
 * тридцать сорванных трансформаций после апгрейда давали бы ЗЕЛЁНУЮ сборку.
 * <p>
 * Сбои из НЕГАТИВНОГО теста ({@code InstrumentationDiagnosticsTest} намеренно ломает трансформер,
 * чтобы доказать, что счётчик не слеп) отфильтрованы по имени типа-мишени — иначе гейт краснел бы
 * всегда и его бы отключили.
 */
final class InstrumentationFailures {

    /**
     * Что не считается поломкой НАШЕЙ инструментации:
     * <ul>
     *   <li>{@code NegativeProbe} — мишень намеренного сбоя из {@code InstrumentationDiagnosticsTest};
     *       без фильтра гейт краснел бы всегда и его бы отключили;</li>
     *   <li>{@code MockMethodAdvice} — артефакт ЧУЖОГО кода: диспетчер Mockito живёт в отдельном
     *       загрузчике, и обход загруженных классов (стратегия Reiterating) не может разрешить его
     *       тип. Наши матчеры этот класс не трогают, перехват не страдает.</li>
     * </ul>
     * Список намеренно короткий и с обоснованием на каждый пункт: он гасит сигнал, поэтому
     * пополнять его можно только с объяснением, почему сбой НЕ наш.
     */
    private static final List<String> IGNORED = List.of(
            "NegativeProbe",
            "org.mockito.internal.creation.bytebuddy.MockMethodAdvice");

    private InstrumentationFailures() {
    }

    /** Текст для сообщения о падении либо {@code null}, если перехват здоров. */
    static String report(Path dump) {
        if (!Files.isRegularFile(dump)) {
            // Дамп пишет SPI-листенер основного прогона. Нет файла — сигнал не работает, и молчать
            // об этом нельзя: «проверку не удалось выполнить» ≠ «проверка прошла».
            return "СБОИ ПЕРЕХВАТА: дамп " + dump + " не найден — второй сигнал не работает.\n"
                    + "  Проверь SPI META-INF/services/org.junit.platform.launcher.LauncherSessionListener.\n";
        }
        List<String> lines;
        try {
            lines = Files.readAllLines(dump, StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            return "СБОИ ПЕРЕХВАТА: дамп " + dump + " не читается — " + unreadable + "\n";
        }
        boolean installed = lines.stream().anyMatch("installed=true"::equals);
        List<String> failures = new ArrayList<>(lines.stream()
                .filter(line -> line.startsWith("failure: "))
                .map(line -> line.substring("failure: ".length()))
                .filter(failure -> IGNORED.stream().noneMatch(failure::contains))
                .toList());
        if (installed && failures.isEmpty()) {
            return null;
        }
        StringBuilder out = new StringBuilder("СБОИ ПЕРЕХВАТА (байткод-агент):\n");
        if (!installed) {
            out.append("  ✗ агент НЕ установлен — весь байткод-слой мёртв.\n")
                    .append("    Обычно это запрет self-attach: нужен -XX:+EnableDynamicAgentLoading (JEP 451).\n");
        }
        failures.forEach(failure -> out.append("  ✗ ").append(failure).append('\n'));
        if (!failures.isEmpty()) {
            out.append("  Трансформация сорвалась — это НЕ «матчер не совпал», а падение внутри перехвата.\n");
        }
        return out.toString();
    }
}
