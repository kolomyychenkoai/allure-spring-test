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
     * Потолок ожидаемого шума. Сейчас подавляется РОВНО ДВА сбоя — по одному на каждую строку
     * {@link #IGNORED_TYPES}: {@code MockMethodAdvice} (диспетчер Mockito в чужом загрузчике) и
     * {@code NegativeProbe} (мишень мутации из {@code InstrumentationDiagnosticsTest}). Записи в
     * выборке уникальны, поэтому повторы числа не двигают.
     * <p>
     * КОЛИЧЕСТВО — тоже сигнал: если после апгрейда ту же причину начнут давать другие типы,
     * гейт остался бы зелёным на принципиально другой картине. Потолок 5 — это запас ровно на
     * пару новых вариантов, а не «чтобы не мешал».
     * <p>
     * ⚠️ Число живое. Было ~47 (листовые ассерты AssertJ, срывавшиеся на конструкторах) — упало
     * до 2 после {@code not(isConstructor())} в матчере AssertJ, а потолок за ним не поехал и
     * остался в 10 раз выше реальности. Меняешь матчер — перечитай это число.
     */
    private static final int SUPPRESSED_CEILING = 5;

    /**
     * Пол реально применённых трансформаций. Сегодня их 145–151 (замер на трёх прогонах).
     * <p>
     * Зачем: сценарий «агент встал, ошибок нет, а матчеры не совпали ни разу» гейт сбоев
     * пропускал — {@code transformed} писался в дамп и не проверялся ничем. Это самый тихий
     * исход апгрейда: перехват формально жив, а в отчёт не попадает НИЧЕГО.
     * <p>
     * Порог с большим запасом: число зависит от того, какие классы успели загрузиться, и
     * дрожит от прогона к прогону. Ловим обвал, а не дрожание.
     * <p>
     * Доказан сквозной мутацией: поднимаешь пол до недостижимого — гейт краснеет с ЧИСЛОМ ИЗ
     * РЕАЛЬНОГО дампа (145). ⚠️ Мутацию надо гонять с {@code -Dmaven.test.failure.ignore=true}:
     * иначе её первыми ловят фикстуры {@code InstrumentationFailuresTest} (там 117), основной
     * прогон падает, и до этого гейта дело не доходит — «доказательство» окажется чужим.
     */
    private static final int TRANSFORMED_FLOOR = 50;

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
        int transformed = 0;
        boolean truncated = false;
        Set<String> failures = new LinkedHashSet<>();
        for (Path dump : dumps) {
            List<String> lines;
            try {
                lines = Files.readAllLines(dump, StandardCharsets.UTF_8);
            } catch (IOException unreadable) {
                return "СБОИ ПЕРЕХВАТА: дамп " + dump + " не читается — " + unreadable + "\n";
            }
            installed |= lines.contains("installed=true");
            truncated |= lines.contains("sample_truncated=true");
            transformed += number(lines, "transformed=");
            List<String> all = lines.stream()
                    .filter(line -> line.startsWith("failure: "))
                    .map(line -> line.substring("failure: ".length()))
                    .toList();
            all.stream().filter(InstrumentationFailures::ours).forEach(failures::add);
            suppressed += (int) all.stream().filter(failure -> !ours(failure)).count();
        }
        if (installed && failures.isEmpty() && suppressed <= SUPPRESSED_CEILING && !truncated
                && transformed >= TRANSFORMED_FLOOR) {
            return null;
        }
        StringBuilder out = new StringBuilder("СБОИ ПЕРЕХВАТА (байткод-агент):\n");
        if (!installed) {
            out.append("  ✗ агент НЕ установлен НИ В ОДНОЙ JVM — весь байткод-слой мёртв.\n")
                    .append("    Обычно это запрет self-attach: нужен -XX:+EnableDynamicAgentLoading (JEP 451).\n");
        }
        if (installed && transformed < TRANSFORMED_FLOOR) {
            // Позитивный сигнал: агент может встать и не перехватить НИЧЕГО — матчеры заданы
            // строками, и после апгрейда просто перестают совпадать. Ошибок при этом нет.
            out.append("  ✗ применено трансформаций ").append(transformed).append(", а пол ")
                    .append(TRANSFORMED_FLOOR).append(" — агент встал, но матчеры почти ничего не нашли.\n")
                    .append("    Смотри канарейки: имена классов/методов чужих библиотек уехали.\n");
        }
        if (truncated) {
            // Ограниченный буфер, прочитанный как полная картина, — тихий отказ с отложенным
            // сроком: «настоящий» сбой за границей выборки гейт бы не увидел. Об усечении
            // обязан сообщать сам механизм, а не человек — по расхождению чисел.
            out.append("  ✗ выборка сбоев УСЕЧЕНА — гейт видит не все типы поломок.\n")
                    .append("    Подними MAX_SAMPLE в InstrumentationDiagnostics и перепрогони.\n");
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

    /** Число из строки вида {@code ключ=42}; ноль, если строки нет или она не число. */
    private static int number(List<String> lines, String key) {
        return lines.stream()
                .filter(line -> line.startsWith(key))
                .map(line -> line.substring(key.length()).trim())
                .findFirst()
                .map(value -> {
                    try {
                        return Integer.parseInt(value);
                    } catch (NumberFormatException notANumber) {
                        return 0;
                    }
                })
                .orElse(0);
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
        return IGNORED_TYPES.stream().noneMatch(type::equals);
    }
}
