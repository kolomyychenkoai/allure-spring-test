package io.github.kolomyychenkoai.allure.spring.inventory;

import io.qameta.allure.Epic;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Сверяет число тестов, обещанное доками, с числом реально выполненных.
 * <p>
 * Долго считалось, что это число не загейтить: аннотации не годятся (параметризованный метод
 * даёт N тестов из одного исходника), дискаверинг JUnit не разворачивает шаблоны, а отчёты
 * surefire пишутся тем же прогоном, внутри которого проверка и живёт.
 * <p>
 * Выход даёт устройство сборки: класс запускается ВТОРЫМ исполнением surefire, из профиля
 * {@code report-inventory}, а к его началу отчёты первого исполнения уже лежат на диске.
 * Оттуда число и берётся.
 * <p>
 * Пока гейта не было, число в {@code docs/architecture.md} разошлось с реальностью за один
 * день — ровно на тот тест, которым закрывали предыдущую находку.
 */
@Epic("Внутренние проверки библиотеки")
class TestCountCheck {

    private static final Path REPORTS = Path.of("target/surefire-reports");

    /** Доки, которые называют число тестов, и фраза, в которой оно стоит. */
    private static final List<Path> DOCS = List.of(
            Path.of("docs/architecture.md"), Path.of("docs/testing.md"));

    @Test
    @DisplayName("число тестов в доках совпадает с числом реально выполненных")
    void documentedCountMatchesTheRun() throws Exception {
        long executed = executedTests();
        assertThat(executed)
                .as("отчёты первого исполнения surefire не прочитались — проверять нечего, "
                        + "и «совпало» тут было бы ложью")
                .isGreaterThan(100);

        List<String> stale = new ArrayList<>();
        for (Path doc : DOCS) {
            String text = Files.readString(doc, StandardCharsets.UTF_8);
            if (!text.contains(String.valueOf(executed))) {
                stale.add(doc + ": выполнено " + executed + " тестов, а такого числа в тексте нет");
            }
        }
        assertThat(stale)
                .as("документ обещает не то число тестов, которое получается на прогоне")
                .isEmpty();
    }

    /**
     * Считаем по элементам {@code <testcase>}, а НЕ по атрибуту {@code tests=} в корне:
     * у класса, где все тесты лежат в {@code @Nested}, там стоит 0. На этом уже терялись тесты.
     * <p>
     * Свой отчёт (второго исполнения) в момент подсчёта ещё не дописан, поэтому в сумму
     * не попадает — сверяется именно первое исполнение.
     */
    private long executedTests() throws Exception {
        if (!Files.isDirectory(REPORTS)) {
            return 0;
        }
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);

        List<Path> files;
        try (Stream<Path> s = Files.list(REPORTS)) {
            files = s.filter(p -> {
                String name = p.getFileName().toString();
                // Отчёты пакета inventory принадлежат ВТОРОМУ исполнению: оба его класса лежат
                // здесь и исключены из первого. Соседний ReportInventoryCheck успевает дописать
                // свой отчёт раньше, и без этого фильтра сумма скакала бы на единицу от порядка.
                return name.startsWith("TEST-") && name.endsWith(".xml")
                        && !name.contains(".inventory.");
            }).sorted().toList();
        }
        long total = 0;
        for (Path xml : files) {
            try {
                Document document = factory.newDocumentBuilder().parse(xml.toFile());
                total += document.getElementsByTagName("testcase").getLength();
            } catch (IOException | org.xml.sax.SAXException broken) {
                // Непрочитанный отчёт занизил бы сумму, и гейт покраснел бы «не тем» способом.
                // Лучше сказать прямо, что именно не разобралось.
                throw new AssertionError("не разобрать отчёт " + xml.getFileName() + ": " + broken.getMessage(), broken);
            }
        }
        return total;
    }
}
