package io.github.kolomyychenkoai.allure.spring.tools;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** Снимок прогона: исходы тестов из surefire-XML плюс наблюдаемое поведение. */
class SnapshotTest {

    @TempDir
    Path service;

    private Path reports() {
        return service.resolve("target").resolve("surefire-reports");
    }

    private static String suite(String body) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?><testsuite tests=\"0\">" + body + "</testsuite>";
    }

    @Test
    @DisplayName("исход каждого теста: passed, failed, error, skipped")
    void outcomes() throws IOException {
        ToolRun.write(reports(), "TEST-a.xml", suite("""
                <testcase classname="A" name="ok"/>
                <testcase classname="A" name="упал"><failure message="нет"/></testcase>
                <testcase classname="A" name="сломался"><error message="нет"/></testcase>
                <testcase classname="A" name="пропущен"><skipped/></testcase>
                """));

        ToolRun run = ToolRun.of("snapshot", service.toString());

        assertThat(run.code()).isZero();
        assertThat(run.lines()).contains(
                "TEST | A#ok → PASSED",
                "TEST | A#упал → FAILED",
                "TEST | A#сломался → ERROR",
                "TEST | A#пропущен → SKIPPED");
    }

    @Test
    @DisplayName("@Nested: в корне tests=0, но тесты есть — считаем по элементам, а не по атрибуту")
    void nestedClassesAreNotLost() throws IOException {
        // Ради этого случая снимок и считает <testcase>: у класса, где все тесты в @Nested,
        // surefire пишет в корень tests="0". По атрибуту тесты пропали бы молча, а A/B-дифф
        // двух пустых снимков сошёлся бы и отрапортовал «библиотека ничего не изменила».
        ToolRun.write(reports(), "TEST-nested.xml", suite("""
                <testcase classname="Outer$Inner" name="первый"/>
                <testcase classname="Outer$Inner" name="второй"/>
                """));

        ToolRun run = ToolRun.of("snapshot", service.toString());

        assertThat(run.lines())
                .as("тесты вложенного класса потерялись — снимок снова считает по атрибуту tests=")
                .contains("TEST | Outer$Inner#первый → PASSED", "TEST | Outer$Inner#второй → PASSED");
    }

    @Test
    @DisplayName("дампы поведения со всех форков сливаются, а не затирают друг друга")
    void behaviourDumpsFromEveryFork() throws IOException {
        ToolRun.write(reports(), "TEST-a.xml", suite("<testcase classname=\"A\" name=\"ok\"/>"));
        Path behavior = service.resolve("target").resolve("behavior");
        ToolRun.write(behavior, "fork-1.log", "BEHAVIOR | первый форк\n");
        ToolRun.write(behavior, "fork-2.log", "BEHAVIOR | второй форк\n");

        ToolRun run = ToolRun.of("snapshot", service.toString());

        assertThat(run.lines())
                .as("под forkCount>1 дампов несколько, и общий файл затирался бы")
                .contains("BEHAVIOR | первый форк", "BEHAVIOR | второй форк");
    }

    @Test
    @DisplayName("рекордер не отработал — это видно в снимке, а не выглядит как «поведения нет»")
    void missingBehaviourIsVisible() throws IOException {
        ToolRun.write(reports(), "TEST-a.xml", suite("<testcase classname=\"A\" name=\"ok\"/>"));

        ToolRun run = ToolRun.of("snapshot", service.toString());

        assertThat(run.lines()).contains("BEHAVIOR | <дампов нет: рекордер не отработал>");
    }

    @Test
    @DisplayName("битый отчёт виден строкой, а соседние файлы разбираются дальше")
    void brokenReportIsReportedAndDoesNotStopTheRest() throws IOException {
        ToolRun.write(reports(), "TEST-a-broken.xml", "<testsuite><testcase");
        ToolRun.write(reports(), "TEST-b.xml", suite("<testcase classname=\"B\" name=\"ok\"/>"));

        ToolRun run = ToolRun.of("snapshot", service.toString());

        assertThat(run.out())
                .as("битый файл обязан быть виден: молча пропав, он выглядел бы как «тестов не было»")
                .contains("неразобранный отчёт TEST-a-broken.xml");
        assertThat(run.lines()).contains("TEST | B#ok → PASSED");
    }

    @Test
    @DisplayName("внешняя сущность в XML не подтягивается, а файл помечен неразобранным")
    void externalEntityIsRejected() throws IOException {
        // XML приходит из каталога ЧУЖОГО сервиса, то есть снаружи. С включёнными сущностями
        // парсер сходил бы по ссылке — прочитал бы локальный файл или дёрнул сеть.
        //
        // Сущность стоит в ТЕКСТЕ элемента, а не в атрибуте: в атрибуте её запрещает сама
        // спецификация XML, и такой файл отвергается при любых настройках — тест не отличал бы
        // защищённый парсер от беззащитного (проверено мутацией).
        ToolRun.write(reports(), "TEST-xxe.xml", """
                <?xml version="1.0"?>
                <!DOCTYPE r [<!ENTITY x SYSTEM "file:///etc/passwd">]>
                <testsuite><testcase classname="C" name="ok"><system-out>&x;</system-out></testcase></testsuite>
                """);

        ToolRun run = ToolRun.of("snapshot", service.toString());

        assertThat(run.out())
                .as("DOCTYPE обязан быть отвергнут: без защиты файл разобрался бы молча, "
                        + "а парсер сходил бы за внешним файлом")
                .contains("неразобранный отчёт TEST-xxe.xml");
        assertThat(run.out())
                .as("содержимое внешней сущности просочилось в снимок")
                .doesNotContain("root:");
    }

    @Test
    @DisplayName("каталога сервиса нет — код 2, а не пустой снимок с кодом 0")
    void missingServiceDirectory() {
        ToolRun run = ToolRun.of("snapshot", service.resolve("нет-такого").toString());

        assertThat(run.code())
                .as("опечатка в пути давала снимок «тестов нет», а два таких снимка совпадают — "
                        + "A/B отрапортовал бы «библиотека ничего не изменила»")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("без аргументов — код 2 и подсказка")
    void usageWithoutArguments() {
        ToolRun run = ToolRun.of("snapshot");

        assertThat(run.code()).isEqualTo(2);
        assertThat(run.err()).contains("Использование");
    }
}
