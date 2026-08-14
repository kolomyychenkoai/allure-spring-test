package io.github.kolomyychenkoai.allure.spring.unit;

import io.qameta.allure.Epic;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

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

    @Test
    @DisplayName("у каждого тест-класса есть @Epic (иначе он молча уезжает в витрину)")
    void everyTestClassDeclaresItsEpic() throws IOException {
        // Витрину читают ~20 ручных QA, и отсекается она РОВНО по эпику: класс без метки
        // попадает к ним. Так уехал внутренний тест уровня B и высыпал туда девять шагов
        // подготовки схемы Hibernate — гейта на метку не было вовсе, и первый же нарушитель
        // прошёл молча.
        // ⚠️ Проверка идёт по ФАЙЛУ, а не по имени класса: предыдущий гейт этого файла уже
        // однажды промахнулся мимо *SmokeIT, потому что смотрел на суффикс.
        // Мутация: снять @Epic с любого тест-класса → RED с именем файла.
        List<String> unmarked = new ArrayList<>();
        try (Stream<Path> files = Files.walk(Path.of("src/test/java"))) {
            for (Path f : files.filter(Files::isRegularFile).sorted().toList()) {
                String name = f.getFileName().toString();
                if (!name.endsWith("Test.java") && !name.endsWith("Tests.java")
                        && !name.endsWith("IT.java")) {
                    continue;
                }
                String body = Files.readString(f);
                // Классы без @Test — это фикстуры и хелперы с «тестовым» именем, витрину
                // они не создают.
                if (body.contains("@Test") && !body.contains("@Epic")) {
                    unmarked.add(f.toString());
                }
            }
        }
        if (!unmarked.isEmpty()) {
            throw new AssertionError("тест-класс без @Epic уедет в витрину ручной приёмки. "
                    + "Внутренним поставь @Epic(\"Внутренние проверки библиотеки\"):\n  "
                    + String.join("\n  ", unmarked));
        }
    }
}
