package io.github.kolomyychenkoai.allure.spring.tools;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Атрибуция шагов: шаг с меткой теста обязан лежать в кейсе именно этого теста.
 * <p>
 * Проверяет обещание README про forked-JVM и {@code @Execution(CONCURRENT)}. A/B-дифф это
 * не ловит — он видит только «тесты не упали».
 */
class AttributionTest {

    @TempDir
    Path service;

    private Path results() {
        return service.resolve("target").resolve("allure-results");
    }

    private void testCase(String file, String name, String... steps) throws IOException {
        StringBuilder json = new StringBuilder("{\"fullName\":\"").append(name).append("\",\"steps\":[");
        for (int i = 0; i < steps.length; i++) {
            json.append(i > 0 ? "," : "").append("{\"name\":\"").append(steps[i]).append("\"}");
        }
        ToolRun.write(results(), file + "-result.json", json.append("]}").toString());
    }

    @Test
    @DisplayName("каждый маркер в своём кейсе — зелено")
    void everyMarkerStaysInItsCase() throws IOException {
        testCase("a", "Первый", "шаг attr-1");
        testCase("b", "Второй", "шаг attr-2");

        ToolRun run = ToolRun.of("attribution", service.toString());

        assertThat(run.code()).isZero();
        assertThat(run.out()).contains("атрибуция цела: 2 маркеров");
    }

    @Test
    @DisplayName("маркер в двух кейсах — шаг уехал, красный")
    void markerInTwoCasesIsRed() throws IOException {
        testCase("a", "Первый", "шаг attr-1");
        testCase("b", "Второй", "шаг attr-1");

        ToolRun run = ToolRun.of("attribution", service.toString());

        assertThat(run.code()).isEqualTo(1);
        assertThat(run.out()).contains("АТРИБУЦИЯ НАРУШЕНА").contains("Первый").contains("Второй");
    }

    @Test
    @DisplayName("маркер находится и во вложенном шаге")
    void markerInsideNestedStep() throws IOException {
        ToolRun.write(results(), "a-result.json",
                "{\"fullName\":\"Первый\",\"steps\":[{\"name\":\"внешний\",\"steps\":[{\"name\":\"attr-1\"}]}]}");

        ToolRun run = ToolRun.of("attribution", service.toString());

        assertThat(run.code()).isZero();
    }

    @Test
    @DisplayName("составной маркер attr-rest-1 тоже ловится")
    void compoundMarker() throws IOException {
        // Узкая регулярка (\\d+ без [\\w-]*) пропустила бы целый канал, а чекер отрапортовал бы «✅».
        testCase("a", "Первый", "шаг attr-rest-1");

        ToolRun run = ToolRun.of("attribution", service.toString());

        assertThat(run.code()).isZero();
        assertThat(run.out()).contains("attr-rest-1");
    }

    @Test
    @DisplayName("маркеров нет вовсе — красный «сбор сломан», а не «нарушений нет»")
    void noMarkersAtAllIsRed() throws IOException {
        testCase("a", "Первый", "шаг без метки");

        ToolRun run = ToolRun.of("attribution", service.toString());

        assertThat(run.code())
                .as("пустой результат читался бы как «нарушений нет» — а это либо сломавшийся "
                        + "сбор, либо шаги вообще не пишутся; оба случая обязаны быть красными")
                .isEqualTo(1);
        assertThat(run.out()).contains("сбор сломан либо шагов нет");
    }

    @Test
    @DisplayName("маркеров меньше ожидаемого — канал потерян, красный")
    void fewerMarkersThanExpected() throws IOException {
        testCase("a", "Первый", "шаг attr-1");

        ToolRun run = ToolRun.of("attribution", service.toString(), "7");

        assertThat(run.code()).isEqualTo(1);
        assertThat(run.out()).contains("маркеров 1, а ждали 7");
    }

    @Test
    @DisplayName("свой префикс маркера")
    void customPrefix() throws IOException {
        testCase("a", "Первый", "шаг mark-9");

        ToolRun run = ToolRun.of("attribution", service.toString(), "1", "mark-");

        assertThat(run.code()).isZero();
    }

    @Test
    @DisplayName("нечисловое ожидаемое число — код 2, а не трасса и не «провал проверки»")
    void expectedCountMustBeANumber() throws IOException {
        testCase("a", "Первый", "шаг attr-1");

        ToolRun run = ToolRun.of("attribution", service.toString(), "абв");

        assertThat(run.code())
                .as("по коду 1 скрипт не отличил бы опечатку в вызове от настоящего провала")
                .isEqualTo(2);
        assertThat(run.err()).contains("должно быть числом");
    }

    @Test
    @DisplayName("каталога результатов нет — провал проверки, код 1")
    void missingResultsIsFailedCheck() {
        ToolRun run = ToolRun.of("attribution", service.toString());

        assertThat(run.code())
                .as("для проверки отсутствие данных — красный результат, а не ошибка вызова")
                .isEqualTo(1);
        assertThat(run.out()).contains("нет каталога результатов");
    }
}
