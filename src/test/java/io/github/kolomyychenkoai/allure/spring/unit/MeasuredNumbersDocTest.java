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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Числа, которые документы выдают за ЗАМЕР, обязаны совпадать со своим источником.
 * <p>
 * Замер — это результат прогона, который нельзя пересчитать сборкой: сколько тестов упало
 * у потребителя, сколько строк WARN было в логах харнесса. Такое число живёт в документе
 * законно, потому что взять его больше неоткуда. Самосчёт — сколько у НАС классов, тестов
 * и строк — наоборот, в прозе не пишется вовсе (разбор в задаче #123).
 * <p>
 * <b>Сверяем ФРАЗУ, а не цифру.</b> Простое «встречается ли число в источнике» бесполезно:
 * двузначные числа в {@code docs/consumer-affects.md} идут сотнями, и подавляющее
 * большинство любых значений найдётся там просто так — включая соседей замера, то есть
 * ровно те, на которые число и может съехать.
 * Поэтому {@code N} вынимается регуляркой из фразы и сравнивается с {@code N} из такой же
 * фразы у потребителя.
 * <p>
 * <b>Граница, за которую этот гейт не отвечает.</b> Зелёный означает «тексты согласны»,
 * а не «число правда»: приложения живут вне репозитория, пересчитать их сборкой нельзя,
 * и устаревание ОБОИХ текстов сразу гейт не ловит.
 */
@Epic("Внутренние проверки библиотеки")
class MeasuredNumbersDocTest {

    private static final Path README = Path.of("README.md");
    private static final Path CONSUMER_AFFECTS = Path.of("docs/consumer-affects.md");

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
}
