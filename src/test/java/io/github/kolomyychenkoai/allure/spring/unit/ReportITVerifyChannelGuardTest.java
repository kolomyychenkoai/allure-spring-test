package io.github.kolomyychenkoai.allure.spring.unit;

import io.qameta.allure.Epic;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Тест немого verify-канала. После перехвата JUnit Jupiter Assertions (см.
 * {@code AllureJUnitJupiterAssertionsInstrumentation}) любой verify-ассерт вида
 * {@code assertTrue(steps.contains(...))} в живом {@code *ReportIT} САМ станет шагом «Проверка: …»
 * и засорит отчёт, который тест проверяет (плюс саморефлексивная рекурсия по {@code stepNames()}).
 * Поэтому в {@code *ReportIT} verify — ТОЛЬКО через {@code CurrentReport.check}/{@code assertStep}.
 * Этот тест превращает дисциплину в enforced-инвариант: краснеет с именем файла и строкой.
 * <p>
 * {@code assertThrows}/{@code assertThrowsExactly}/{@code assertDoesNotThrow} разрешены — это
 * ДРАЙВЕРЫ поведения (заставить бросить), их шаг «Проверка: брошено …» — законная витрина.
 * Исключён {@code JUnitJupiterAssertionsReportIT} — там JUnit-ассерты это САМ предмет показа.
 */
@Epic("Внутренние проверки библиотеки")
class ReportITVerifyChannelGuardTest {

    private static final Path DEMO_DIR =
            Path.of("src/test/java/io/github/kolomyychenkoai/allure/spring/demo");

    // verify-семейство (без Throws/DoesNotThrow — они драйверы)
    private static final Pattern FORBIDDEN = Pattern.compile(
            "\\bassert(True|False|Equals|NotEquals|Null|NotNull|Same|NotSame|ArrayEquals|IterableEquals|LinesMatch|InstanceOf|Timeout|TimeoutPreemptively)\\s*\\(");

    @Test
    @DisplayName("в *ReportIT нет JUnit verify-ассертов (иначе засорят отчёт после перехвата Jupiter)")
    void noJUnitVerifyAssertsInReportITs() throws IOException {
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.list(DEMO_DIR)) {
            List<Path> its = files
                    // ВСЕ живые level-B (не только *ReportIT — был *SmokeIT с дырой в фильтре)
                    .filter(p -> p.getFileName().toString().endsWith("IT.java"))
                    // новый Jupiter-IT: JUnit-ассерты там — предмет показа, не verify
                    .filter(p -> !p.getFileName().toString().equals("JUnitJupiterAssertionsReportIT.java"))
                    // Spring-ассерты (AssertionErrors.assert*) там — предмет показа (драйверы шагов),
                    // а не verify отчёта; verify этого IT уже переведён на немой CurrentReport-канал
                    .filter(p -> !p.getFileName().toString().equals("SpringAssertionsReportIT.java"))
                    .sorted()
                    .toList();
            for (Path f : its) {
                String[] lines = Files.readString(f).split("\n");
                for (int i = 0; i < lines.length; i++) {
                    if (FORBIDDEN.matcher(lines[i]).find()) {
                        offenders.add(f.getFileName() + ":" + (i + 1) + "  " + lines[i].trim());
                    }
                }
            }
        }
        if (!offenders.isEmpty()) {
            throw new AssertionError("JUnit verify-ассерты в *ReportIT засорят отчёт после перехвата "
                    + "Jupiter. Переведи на CurrentReport.check(...)/assertStep(...):\n  "
                    + String.join("\n  ", offenders));
        }
    }

    /** Эпик, под которым живёт всё внутреннее. Витрину от него отделяет ЗНАЧЕНИЕ, не наличие. */
    private static final String INTERNAL_EPIC = "Внутренние проверки библиотеки";

    /** Любая форма теста JUnit 5, а не только @Test: класс с одним @ParameterizedTest — тоже класс. */
    private static final Pattern ANY_TEST = Pattern.compile(
            "^\\s*@(Test|ParameterizedTest|RepeatedTest|TestFactory|TestTemplate)\\b", Pattern.MULTILINE);

    /** Аннотация в начале строки, а НЕ слово «@Epic» где-нибудь в javadoc. */
    private static final Pattern EPIC_ANNOTATION = Pattern.compile(
            "^\\s*@Epic\\s*\\(\\s*\"([^\"]*)\"", Pattern.MULTILINE);

    @Test
    @DisplayName("вне demo каждый тест-класс помечен внутренним эпиком (иначе уедет в витрину)")
    void everyTestClassOutsideDemoDeclaresInternalEpic() throws IOException {
        // Витрину читают ~20 ручных QA, и отделяется она ЗНАЧЕНИЕМ эпика. Класс без метки —
        // или с чужой меткой — попадает к ним. Так уехал внутренний тест уровня B и высыпал
        // туда девять шагов подготовки схемы Hibernate.
        //
        // ⚠️ Первая редакция этого гейта была слепа ЧЕТЫРЬМЯ способами сразу, и каждый замерен:
        //   • спрашивала `body.contains("@Epic")` — засчитывала слово из javadoc, поэтому не
        //     видела ровно тот класс, который правило и объясняет;
        //   • спрашивала `body.contains("@Test")` — класс с одним @ParameterizedTest пропускала;
        //   • фильтровала по суффиксу файла — мимо шли `*Check.java` (их два, и docs/testing.md
        //     про это прямо пишет);
        //   • не имела положительного якоря — молчала бы, не посмотрев ни одного файла.
        // Отсюда форма: аннотация в начале строки, любая форма теста, вход по содержимому,
        // и счётчик просмотренного в ассерте.
        // Мутация: снять @Epic с любого тест-класса вне demo → RED с именем файла.
        List<String> offenders = new ArrayList<>();
        int scanned = 0;
        try (Stream<Path> files = Files.walk(Path.of("src/test/java"))) {
            for (Path f : files.filter(Files::isRegularFile).sorted().toList()) {
                if (!f.getFileName().toString().endsWith(".java")) {
                    continue;
                }
                String body = Files.readString(f);
                if (!ANY_TEST.matcher(body).find()) {
                    continue; // фикстура или хелпер — витрину не создаёт
                }
                scanned++;
                if (f.startsWith(DEMO_DIR)) {
                    continue; // demo и ЕСТЬ витрина: там эпик прикладной, а не внутренний
                }
                Matcher epic = EPIC_ANNOTATION.matcher(body);
                if (!epic.find()) {
                    offenders.add(f + "  →  эпика нет");
                } else if (!INTERNAL_EPIC.equals(epic.group(1))) {
                    offenders.add(f + "  →  эпик «" + epic.group(1) + "», а не внутренний");
                }
            }
        }

        // ЯКОРЬ. Без него «нарушителей нет» неотличимо от «не посмотрел ни одного файла»:
        // сломай Files.walk или фильтр — и гейт зелен, ничего не измерив.
        assertThat(scanned).as("гейт не нашёл тест-классов вовсе — он проверяет пустоту")
                .isGreaterThan(60);
        assertThat(offenders).as("тест-класс вне demo без внутреннего эпика уедет в витрину "
                + "ручной приёмки; поставь @Epic(\"" + INTERNAL_EPIC + "\")")
                .isEmpty();
    }
}
