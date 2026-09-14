package io.github.kolomyychenkoai.allure.spring.unit;

import io.qameta.allure.Epic;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Числа, которые документы выдают за замер, обязаны совпадать со своим источником.
 * <p>
 * Негейтованное число в документе живёт своей жизнью, и первым его замечает читатель, а не
 * сборка: обзор архитектуры однажды обещал 491 тест при 492 и разошёлся за день. Здесь
 * закрыты два таких числа — по одному на каждый вид источника.
 * <p>
 * <b>Сверяем ФРАЗУ, а не цифру.</b> Простое «встречается ли число в источнике» бесполезно:
 * в {@code docs/consumer-affects.md} 85 уникальных числовых токенов, и 72 двузначных числа
 * из 90 проходят такую проверку — включая те, на которые число реально может съехать.
 * Поэтому {@code N} вынимается регуляркой из фразы и сравнивается с {@code N} из такой же
 * фразы у потребителя.
 * <p>
 * ⚠️ <b>Зелёный тут значит «тексты согласны», а не «число правда».</b> Приложения-потребители
 * живут вне репозитория, пересчитать их сборкой нельзя, и сам источник это оговаривает: числа
 * относятся к ревизии, на которой гоняли A/B. Устаревание ОБОИХ текстов сразу этот гейт
 * не ловит и поймать не может. Число инструментов — другое дело: его источник в репозитории,
 * и там гейт действительно меряет.
 */
@Epic("Внутренние проверки библиотеки")
class MeasuredNumbersDocTest {

    private static final Path README = Path.of("README.md");
    private static final Path CONSUMER_AFFECTS = Path.of("docs/consumer-affects.md");
    private static final Path PLAYBOOK = Path.of("docs/review-playbook.md");
    private static final Path TESTING = Path.of("docs/testing.md");

    /** Фраза-источник: «запрет … валит N тестов из N» в реестре замеров по потребителям. */
    private static final Pattern SOURCE = Pattern.compile("валит (\\d+) тестов из (\\d+)");

    /** Те же числа у потребителей замера. */
    private static final List<Quote> QUOTES = List.of(
            new Quote(README, Pattern.compile("упали (\\d+) тестов из (\\d+)")),
            new Quote(PLAYBOOK, Pattern.compile("меряет (\\d+) упавших из (\\d+)")));

    /** Кто повторяет замеренное число и какой фразой. */
    private record Quote(Path file, Pattern phrase) {
    }

    @Test
    @DisplayName("замер про запрет привязки агента: README и playbook называют число источника")
    void замеренноеЧислоСовпадаетСИсточником() throws IOException {
        int measured = pairedNumber(CONSUMER_AFFECTS, SOURCE);

        for (Quote quote : QUOTES) {
            assertThat(pairedNumber(quote.file(), quote.phrase()))
                    .as("%s называет не то число, которое замерено в %s — документ врёт молча",
                            quote.file(), CONSUMER_AFFECTS)
                    .isEqualTo(measured);
        }
    }

    @Test
    @DisplayName("число тестов инструментов ревью: документы называют то, что считается командой")
    void числоТестовИнструментовСовпадаетСКодом() throws IOException {
        long actual = toolTests();
        assertThat(actual)
                .as("сбор тестов инструментов сломался — проверять нечего, и «совпало» тут было бы ложью")
                .isPositive();

        for (Path doc : List.of(README, TESTING)) {
            assertThat(onlyNumber(doc, Pattern.compile("(\\d+) (?:свой тест|тест,)")))
                    .as("%s обещает не то число тестов инструментов, которое есть в tools/", doc)
                    .isEqualTo((int) actual);
        }
    }

    /**
     * Число из фразы вида «N из N» — и ЯКОРЬ: фраза обязана найтись ровно один раз, обе её
     * половины обязаны совпасть. Пропала фраза, переписали абзац, разъехались половины —
     * это находка, а не повод молча пропустить проверку.
     */
    private static int pairedNumber(Path file, Pattern phrase) throws IOException {
        Matcher matcher = matchOnce(file, phrase);
        int first = Integer.parseInt(matcher.group(1));
        assertThat(Integer.parseInt(matcher.group(2)))
                .as("в %s половины «N из N» разъехались — замер описан неверно", file)
                .isEqualTo(first);
        return first;
    }

    /** Одиночное число из фразы, с тем же якорем на единственность. */
    private static int onlyNumber(Path file, Pattern phrase) throws IOException {
        return Integer.parseInt(matchOnce(file, phrase).group(1));
    }

    private static Matcher matchOnce(Path file, Pattern phrase) throws IOException {
        String text = Files.readString(file, StandardCharsets.UTF_8);
        Matcher matcher = phrase.matcher(text);
        assertThat(matcher.find())
                .as("в %s больше нет фразы «%s» — гейт проверял бы пустоту", file, phrase.pattern())
                .isTrue();
        Matcher second = phrase.matcher(text);
        second.find();
        assertThat(second.find())
                .as("в %s фраза «%s» встречается дважды — неясно, какая из них замер",
                        file, phrase.pattern())
                .isFalse();
        return matcher;
    }

    /** Сколько тестов у инструментов ревью: источник лежит в репозитории и считается. */
    private static long toolTests() throws IOException {
        try (Stream<Path> files = Files.walk(Path.of("tools"))) {
            return files.filter(path -> path.toString().endsWith(".java"))
                    .mapToLong(MeasuredNumbersDocTest::testAnnotations)
                    .sum();
        }
    }

    private static long testAnnotations(Path file) {
        try {
            return Files.readAllLines(file, StandardCharsets.UTF_8).stream()
                    .filter(line -> line.strip().equals("@Test"))
                    .count();
        } catch (IOException unreadable) {
            throw new java.io.UncheckedIOException(unreadable);
        }
    }
}
