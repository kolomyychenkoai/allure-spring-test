package io.github.kolomyychenkoai.allure.spring.unit;

import io.qameta.allure.Epic;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.io.UncheckedIOException;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Числа, которые документы выдают за замер, обязаны совпадать со своим источником.
 * <p>
 * Негейтованное число в документе живёт своей жизнью, и первым его замечает читатель, а не
 * сборка — разбор этого класса дефектов в javadoc {@code inventory/TestCountCheck}. Здесь
 * закрыты два числа — по одному на каждый вид источника.
 * <p>
 * <b>Сверяем ФРАЗУ, а не цифру.</b> Простое «встречается ли число в источнике» бесполезно:
 * 72 двузначных числа из 90 проходят такую проверку по {@code docs/consumer-affects.md},
 * включая соседей замера — то есть ровно те, на которые число и может съехать.
 * Поэтому {@code N} вынимается регуляркой из фразы и сравнивается с {@code N} из такой же
 * фразы у потребителя.
 * <p>
 * <b>Границы, за которые этот гейт не отвечает.</b> Для замера по потребителям зелёный
 * означает «тексты согласны», а не «число правда»: приложения живут вне репозитория,
 * пересчитать их сборкой нельзя, и устаревание ОБОИХ текстов сразу гейт не ловит. Для числа
 * тестов инструментов источник в репозитории, но считаются аннотации, а не выполненные
 * тесты, — поэтому рядом стоит отдельная проверка, что параметризованных тестов там нет.
 */
@Epic("Внутренние проверки библиотеки")
class MeasuredNumbersDocTest {

    private static final Path README = Path.of("README.md");
    private static final Path CONSUMER_AFFECTS = Path.of("docs/consumer-affects.md");
    private static final Path PLAYBOOK = Path.of("docs/review-playbook.md");
    private static final Path TESTING = Path.of("docs/testing.md");

    /**
     * Разрыв между словами фразы: пробел, перенос строки и маркер цитаты. Фразы живут
     * во врезках {@code > …}, и перенос строки внутри фразы вставляет туда «&gt;» — правка,
     * факта не меняющая. Шаблон на голом {@code \s+} краснел бы на ней «фразы больше нет».
     */
    private static final String GAP = "[\\s>]+";

    /**
     * Фраза-источник: «запрет … валит N тестов из M» в разборе замеров по потребителям.
     * <p>
     * ⚠️ Кириллица в классах символов — ЯВНЫМ диапазоном {@code [а-я]}: у Java {@code \w} без
     * {@code UNICODE_CHARACTER_CLASS} это только латиница, и шаблон молча не совпадал бы.
     * <p>
     * {@code \s+} вместо пробела и {@code тест(?:а|ов)?} — намеренно: перенос строки внутри
     * фразы и склонение числительного правку факта не означают, а шаблон на литеральном
     * пробеле краснел бы на них с сообщением «фраза пропала» и отправлял бы читателя
     * искать удалённый абзац вместо правки шаблона.
     */
    private static final Pattern SOURCE =
            Pattern.compile("валит" + GAP + "(\\d+)" + GAP + "тест(?:а|ов)?" + GAP + "из" + GAP + "(\\d+)");

    /** Тот же замер у потребителя. */
    private static final Pattern QUOTE =
            Pattern.compile("упал[а-я]*" + GAP + "(\\d+)" + GAP + "тест(?:а|ов)?" + GAP + "из" + GAP + "(\\d+)");

    /**
     * Число тестов инструментов ревью. Шаблон привязан к ПРЕДМЕТУ, а не к слову «тест»:
     * без якоря на «инструмент» он цеплялся бы за любую чужую строку с числом и тестами,
     * и документ мог бы вообще перестать называть это число при зелёной сборке.
     */
    private static final Pattern README_TOOLS =
            Pattern.compile("инструментов" + GAP + "(\\d+)" + GAP + "тест");
    private static final Pattern TESTING_TOOLS =
            Pattern.compile("Инструменты ревью[\\s\\S]{0,80}?(\\d+)" + GAP + "свой" + GAP + "тест");

    @Test
    @DisplayName("замер про запрет привязки агента: README называет ту же пару, что и источник")
    void замеренноеЧислоСовпадаетСИсточником() {
        // Сверяется ПАРА «упало из всего», а не одно число и не равенство половин: «27 из 29» —
        // нормальный будущий замер, а не ошибка описания. Гейт, требующий равенства, запрещал бы
        // замеру измениться, то есть сторожил бы не факт, а сегодняшнее значение.
        List<Integer> measured = numbers(CONSUMER_AFFECTS, SOURCE);

        assertThat(numbers(README, QUOTE))
                .as("README называет не тот замер, что записан в %s — документ врёт молча",
                        CONSUMER_AFFECTS)
                .isEqualTo(measured);
    }

    @Test
    @DisplayName("число тестов инструментов ревью: документы называют то, что есть в tools/")
    void числоТестовИнструментовСовпадаетСКодом() throws IOException {
        // ⚠️ НЕ добавляй в tools/ параметризованные тесты, не переписав этот счётчик:
        // одна аннотация даёт тогда N тестов, и документ начнёт врать при зелёном гейте.
        // Тот же разбор — в javadoc inventory/TestCountCheck.
        assertThat(parameterizedInTools())
                .as("в tools/ появился параметризованный тест — аннотаций больше не равно "
                        + "числу тестов, считай по tools/target/surefire-reports")
                .isZero();

        long actual = toolTests();
        assertThat(actual)
                .as("сбор тестов инструментов сломался — проверять нечего, и «совпало» тут было бы ложью")
                .isPositive();

        assertThat(numbers(README, README_TOOLS))
                .as("README обещает не то число тестов инструментов, которое есть в tools/")
                .containsExactly((int) actual);
        assertThat(numbers(TESTING, TESTING_TOOLS))
                .as("docs/testing.md обещает не то число тестов инструментов, которое есть в tools/")
                .containsExactly((int) actual);
    }

    /**
     * Все числа единственного совпадения шаблона — и ЯКОРЬ: совпадение обязано быть ровно одно.
     * Пропала фраза, переписали абзац, шаблон стал совпадать дважды — это находка, а не повод
     * молча пропустить проверку.
     */
    private static List<Integer> numbers(Path file, Pattern phrase) {
        List<MatchResult> found = phrase.matcher(read(file)).results().toList();
        assertThat(found)
                .as("в %s шаблон «%s» совпал %d раз — гейт проверяет не то, что задумано",
                        file, phrase.pattern(), found.size())
                .hasSize(1);
        MatchResult only = found.get(0);
        return java.util.stream.IntStream.rangeClosed(1, only.groupCount())
                .mapToObj(group -> Integer.parseInt(only.group(group)))
                .toList();
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            throw new UncheckedIOException(unreadable);
        }
    }

    /** Сколько тестов у инструментов ревью: источник лежит в репозитории и считается. */
    private static long toolTests() throws IOException {
        return countInTools(line -> line.equals("@Test"));
    }

    /** Параметризованные: одна аннотация — несколько тестов, и счёт по аннотациям соврёт. */
    private static long parameterizedInTools() throws IOException {
        return countInTools(line -> line.startsWith("@ParameterizedTest")
                || line.startsWith("@RepeatedTest")
                || line.startsWith("@TestFactory"));
    }

    /**
     * Обходим {@code tools/src}, а не {@code tools}: в {@code tools/target} лежит каталог
     * сборки со сгенерированными исходниками, и счёт зависел бы от того, собирали ли
     * инструменты.
     */
    private static long countInTools(java.util.function.Predicate<String> marker) throws IOException {
        try (Stream<Path> files = Files.walk(Path.of("tools/src"))) {
            return files.filter(path -> path.toString().endsWith(".java"))
                    .mapToLong(file -> read(file).lines().map(String::strip).filter(marker).count())
                    .sum();
        }
    }
}
