package io.github.kolomyychenkoai.allure.spring.inventory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Второй сигнал об апгрейде как ГЕЙТ: читает дампы {@link InstrumentationDiagnosticsDump}
 * и говорит, срывались ли трансформации в основном прогоне.
 * <p>
 * Без этого счётчик сбоев был бы не сигналом, а строчкой в логе, который никто не читает:
 * тридцать сорванных трансформаций после апгрейда давали бы ЗЕЛЁНУЮ сборку.
 * <p>
 * Дампов может быть несколько (по одному на JVM: форки surefire + JVM самого инвентаря) —
 * сливаем: агент считается установленным, если это удалось ХОТЯ БЫ в одной, а сбои берём
 * объединением. Иначе последняя закрывшаяся JVM молча решала бы за всех.
 */
final class InstrumentationFailures {

    /**
     * Чьи сбои не считаются поломкой НАШЕЙ инструментации (сравнение — по ИМЕНИ ТИПА, не по
     * всей строке: иначе настоящий сбой с таким словом в тексте исключения был бы проглочен):
     * <ul>
     *   <li>{@code …$NegativeProbe} — мишень намеренного сбоя из {@code InstrumentationDiagnosticsTest};
     *       без фильтра гейт краснел бы всегда и его бы отключили;</li>
     *   <li>{@code MockMethodAdvice} — артефакт ЧУЖОГО кода: диспетчер Mockito живёт в отдельном
     *       загрузчике, и обход загруженных классов (стратегия Reiterating) не может разрешить его
     *       тип. Наши матчеры этот класс не трогают, перехват не страдает.</li>
     * </ul>
     * Список гасит сигнал, поэтому пополнять его можно только с обоснованием, почему сбой НЕ наш.
     */
    private static final List<String> IGNORED_TYPES = List.of(
            "io.github.kolomyychenkoai.allure.spring.unit.InstrumentationDiagnosticsTest$NegativeProbe",
            "org.mockito.internal.creation.bytebuddy.MockMethodAdvice");

    /**
     * ОЖИДАЕМЫЙ артефакт по ADR 0001: у листовых ассертов AssertJ ({@code StringAssert} и др.)
     * публичный конструктор, а наш advice ловит исключения — {@code onThrowable} на конструктор
     * не ложится. Их методы-проверки наследуются от абстрактных предков, которые вплетены
     * успешно, поэтому отчёт не страдает; исключать конструкторы из матчера НЕЛЬЗЯ (пробовали:
     * рассинхронивает дедуп по глубине и роняет comparable-ассерты).
     * <p>
     * Гасим УЗКО: только эта причина и только у типов AssertJ. Любой другой сбой там же — наш.
     */
    private static final String ASSERTJ_TYPES = "org.assertj.core.api.";
    private static final String CONSTRUCTOR_ADVICE = "Cannot catch exception during constructor call";

    /**
     * Потолок ожидаемого шума. Сейчас подавляется ~47 сбоев (по числу листовых ассертов, что
     * успевают загрузиться). Само подавление узкое, но КОЛИЧЕСТВО тоже сигнал: если после
     * апгрейда ту же причину начнут давать и абстрактные предки (которые сегодня вплетаются
     * успешно), гейт остался бы зелёным на принципиально другой картине. Потолок щедрый —
     * ловит порядковый скачок, а не дрожание порядка загрузки классов.
     */
    private static final int SUPPRESSED_CEILING = 120;

    private static final String ARROW = " → ";

    private InstrumentationFailures() {
    }

    /** Текст для сообщения о падении либо {@code null}, если перехват здоров. */
    static String report(Path dumpDir) {
        List<Path> dumps = dumps(dumpDir);
        if (dumps.isEmpty()) {
            // Дампы пишет SPI-листенер основного прогона. Нет ни одного — сигнал не работает,
            // и молчать об этом нельзя: «проверку не удалось выполнить» ≠ «проверка прошла».
            return "СБОИ ПЕРЕХВАТА: дампы в " + dumpDir + " не найдены — второй сигнал не работает.\n"
                    + "  Проверь SPI META-INF/services/org.junit.platform.launcher.LauncherSessionListener.\n";
        }
        boolean installed = false;
        int suppressed = 0;
        Set<String> failures = new LinkedHashSet<>();
        for (Path dump : dumps) {
            List<String> lines;
            try {
                lines = Files.readAllLines(dump, StandardCharsets.UTF_8);
            } catch (IOException unreadable) {
                return "СБОИ ПЕРЕХВАТА: дамп " + dump + " не читается — " + unreadable + "\n";
            }
            installed |= lines.contains("installed=true");
            List<String> all = lines.stream()
                    .filter(line -> line.startsWith("failure: "))
                    .map(line -> line.substring("failure: ".length()))
                    .toList();
            all.stream().filter(InstrumentationFailures::ours).forEach(failures::add);
            suppressed += (int) all.stream().filter(failure -> !ours(failure)).count();
        }
        if (installed && failures.isEmpty() && suppressed <= SUPPRESSED_CEILING) {
            return null;
        }
        StringBuilder out = new StringBuilder("СБОИ ПЕРЕХВАТА (байткод-агент):\n");
        if (!installed) {
            out.append("  ✗ агент НЕ установлен НИ В ОДНОЙ JVM — весь байткод-слой мёртв.\n")
                    .append("    Обычно это запрет self-attach: нужен -XX:+EnableDynamicAgentLoading (JEP 451).\n");
        }
        if (suppressed > SUPPRESSED_CEILING) {
            out.append("  ✗ ожидаемых-подавленных сбоев ").append(suppressed)
                    .append(", а потолок ").append(SUPPRESSED_CEILING).append(" — картина изменилась.\n")
                    .append("    Перечитай ADR 0001: компромисс с конструкторами мог перестать быть узким.\n");
        }
        failures.forEach(failure -> out.append("  ✗ ").append(failure).append('\n'));
        if (!failures.isEmpty()) {
            out.append("  Трансформация сорвалась — это НЕ «матчер не совпал», а падение внутри перехвата.\n");
        }
        return out.toString();
    }

    private static List<Path> dumps(Path dumpDir) {
        if (!Files.isDirectory(dumpDir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(dumpDir)) {
            return new ArrayList<>(files.filter(p -> p.getFileName().toString().endsWith(".txt")).sorted().toList());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Сбой наш, если тип-мишень (часть строки до стрелки) не в списке игнорируемых. */
    private static boolean ours(String failure) {
        int arrow = failure.indexOf(ARROW);
        String type = arrow < 0 ? failure : failure.substring(0, arrow);
        String reason = arrow < 0 ? "" : failure.substring(arrow + ARROW.length());
        if (type.startsWith(ASSERTJ_TYPES) && reason.contains(CONSTRUCTOR_ADVICE)) {
            return false; // ожидаемый компромисс ADR 0001, см. константы выше
        }
        return IGNORED_TYPES.stream().noneMatch(type::equals);
    }
}
