package io.github.kolomyychenkoai.allure.spring.tools;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** Дерево витринных тестов: то, по чему отчёт читают глазами (проход 2.6). */
class ReportTreeTest {

    @TempDir
    Path results;

    private static final String INTERNAL = "Внутренние проверки библиотеки";

    private void testCase(String file, String epic, String cls, String name, String steps) throws IOException {
        ToolRun.write(results, file + "-result.json", """
                {"name":"%s","labels":[{"name":"epic","value":"%s"},{"name":"testClass","value":"%s"}],
                 "steps":[%s]}""".formatted(name, epic, cls, steps));
    }

    private static String step(String name) {
        return "{\"name\":\"%s\",\"status\":\"passed\"}".formatted(name);
    }

    @Test
    @DisplayName("шаги печатаются с вложенностью, вложениями и статусом")
    void treeShape() throws IOException {
        testCase("a", "витрина", "com.example.MyIT", "мой тест", """
                {"name":"внешний","status":"passed","attachments":[{"name":"Тело"}],
                 "steps":[{"name":"внутренний","status":"failed"}]}""");

        ToolRun run = ToolRun.of("report-tree", results.toString());

        assertThat(run.code()).isZero();
        assertThat(run.out())
                .contains("MyIT")
                .contains("  ТЕСТ: мой тест")
                .contains("        • внешний   {Тело}")
                .contains("            • внутренний  [FAILED]");
        assertThat(run.out()).contains("ИТОГО: шагов 2, вложений 1");
    }

    @Test
    @DisplayName("витрина отделена от внутренних проверок, внутренние по умолчанию скрыты")
    void showcaseIsSeparatedFromInternal() throws IOException {
        testCase("a", "витрина", "com.example.ShowIT", "витринный", step("шаг"));
        testCase("b", INTERNAL, "com.example.InnerTest", "внутренний", step("шаг"));

        ToolRun shown = ToolRun.of("report-tree", results.toString());
        assertThat(shown.out()).contains("← витрина, её и читает тестировщик");
        assertThat(shown.out()).contains("ShowIT").doesNotContain("InnerTest");

        ToolRun all = ToolRun.of("report-tree", results.toString(), "--all");
        assertThat(all.out()).contains("ShowIT").contains("InnerTest");
    }

    @Test
    @DisplayName("классы и тесты идут по алфавиту, а не в порядке файлов")
    void stableOrder() throws IOException {
        testCase("z", "витрина", "com.example.BIT", "тест", step("шаг"));
        testCase("a", "витрина", "com.example.AIT", "тест", step("шаг"));

        String out = ToolRun.of("report-tree", results.toString()).out();

        assertThat(out.indexOf("AIT"))
                .as("порядок файлов на диске случаен, а дерево должно читаться одинаково")
                .isLessThan(out.indexOf("BIT"));
    }

    @Test
    @DisplayName("серия из пяти одинаковых шагов подсвечивается, из четырёх — нет")
    void noiseThresholdBoundary() throws IOException {
        testCase("four", "витрина", "com.example.FourIT", "четыре",
                String.join(",", step("одно"), step("одно"), step("одно"), step("одно")));
        assertThat(ToolRun.of("report-tree", results.toString()).out())
                .as("порог сдвинулся: четыре одинаковых шага ещё не шум")
                .contains("Серий одинаковых шагов от 5 подряд нет.");

        testCase("five", "витрина", "com.example.FiveIT", "пять",
                String.join(",", step("одно"), step("одно"), step("одно"), step("одно"), step("одно")));
        assertThat(ToolRun.of("report-tree", results.toString()).out())
                .as("пять одинаковых шагов подряд — уже шум, смысловой шаг в нём тонет")
                .contains("СЮДА СМОТРЕТЬ").contains("«одно»");
    }

    @Test
    @DisplayName("длинное имя обрезается, не разрывая суррогатную пару")
    void longNameIsCutByCodePoints() throws IOException {
        // Имя длиннее предела обрезки (50 кодовых точек). Первая буква обычная, дальше
        // суррогатные пары: тогда substring по UTF-16 обрывается ВНУТРИ пары и в вывод попадает
        // её половина. Ровно то, на чём падал прежний питон.
        String emoji = "\uD83D\uDE00";
        testCase("a", "витрина", "com.example.LongIT", "x" + emoji.repeat(60),
                String.join(",", step("одно"), step("одно"), step("одно"), step("одно"), step("одно")));

        String out = ToolRun.of("report-tree", results.toString()).out();

        // Проверять надо СТРОКУ ПОДСВЕТКИ, а не весь вывод: полное имя есть ещё и в строке
        // «ТЕСТ:», и проверка по всему тексту нашла бы его там, ничего не доказав.
        String noisy = out.lines().filter(l -> l.contains(" :: ")).findFirst()
                .orElseThrow(() -> new AssertionError("имя не попало в подсветку серий:\n" + out));
        assertThat(noisy)
                .as("обрезка режет по UTF-16: вместо 50 кодовых точек остаётся половина от них "
                        + "и обломок суррогатной пары (в UTF-8 он печатается как «?»)")
                .contains("x" + emoji.repeat(49));
    }

    @Test
    @DisplayName("каталог без файлов результатов — код 2, а не «0 тестов» с кодом 0")
    void emptyResultsDirectoryIsRed() {
        ToolRun run = ToolRun.of("report-tree", results.toString());

        assertThat(run.code())
                .as("«ОТЧЁТ: 0 тестов» с нулевым кодом читалось бы как успех и прятало забытый прогон")
                .isEqualTo(2);
        assertThat(run.err()).contains("нет файлов *-result.json");
    }

    @Test
    @DisplayName("несуществующий каталог — код 2 с понятной строкой")
    void missingDirectory() {
        ToolRun run = ToolRun.of("report-tree", results.resolve("нет-такого").toString());

        assertThat(run.code()).isEqualTo(2);
        assertThat(run.err()).contains("нет каталога результатов");
    }

    @Test
    @DisplayName("битый файл результата назван по имени, а не отдан трассой")
    void brokenResultFileIsNamed() throws IOException {
        ToolRun.write(results, "a-result.json", "{это не json");

        ToolRun run = ToolRun.of("report-tree", results.toString());

        assertThat(run.code()).isEqualTo(2);
        assertThat(run.err()).contains("не разобрать a-result.json");
    }

    @Test
    @DisplayName("неизвестный флаг и второй каталог не проглатываются молча")
    void argumentsAreStrict() throws IOException {
        testCase("a", "витрина", "com.example.AIT", "тест", step("шаг"));

        assertThat(ToolRun.of("report-tree", results.toString(), "--вздор").code()).isEqualTo(2);
        assertThat(ToolRun.of("report-tree", results.toString(), results.toString()).code()).isEqualTo(2);
    }

    @Test
    @DisplayName("повторный запуск даёт тот же вывод: счётчики не копятся между прогонами")
    void repeatedRunIsIdentical() throws IOException {
        testCase("a", "витрина", "com.example.AIT", "тест", step("шаг"));

        assertThat(ToolRun.of("report-tree", results.toString()).out())
                .as("счётчики шагов лежали в статике и складывались бы от запуска к запуску")
                .isEqualTo(ToolRun.of("report-tree", results.toString()).out());
    }
}
