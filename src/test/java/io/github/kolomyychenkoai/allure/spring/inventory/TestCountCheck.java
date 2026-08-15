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
import java.util.Map;
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

    @Test
    @DisplayName("разбивка по пакетам в docs/testing.md совпадает с прогоном")
    void packageBreakdownMatchesTheRun() throws Exception {
        // Живёт здесь, а не рядом с остальными тестами документа, по той же причине, что и
        // счётчик выше: отчёты первого исполнения дописаны только к началу второго. Первая
        // версия этой проверки стояла в unit-пакете и видела 164 теста вместо 384.
        Map<String, Long> counted = perPackage();
        if (counted.isEmpty()) {
            return;
        }
        String doc = Files.readString(Path.of("docs/testing.md"), StandardCharsets.UTF_8);
        List<String> stale = counted.entrySet().stream()
                .filter(e -> !doc.contains("`%s` | %d ".formatted(e.getKey(), e.getValue())))
                .map(e -> "пакет %s: выполнено %d, в таблице этого числа нет".formatted(e.getKey(), e.getValue()))
                .sorted()
                .toList();
        assertThat(stale)
                .as("разбивка по пакетам разошлась с прогоном — раньше её не проверял никто, "
                        + "и в таблице жило заниженное число")
                .isEmpty();
    }

    /** Сколько тестов выполнилось в каждом пакете первого исполнения. */
    private Map<String, Long> perPackage() throws Exception {
        Map<String, Long> counted = new java.util.LinkedHashMap<>();
        for (Path xml : reportFiles()) {
            java.util.regex.Matcher pkg = java.util.regex.Pattern
                    .compile("allure\\.spring\\.([a-z]+)\\.").matcher(xml.getFileName().toString());
            if (!pkg.find()) {
                continue;
            }
            Document document = factory().newDocumentBuilder().parse(xml.toFile());
            counted.merge(pkg.group(1), (long) document.getElementsByTagName("testcase").getLength(), Long::sum);
        }
        return counted;
    }

    /**
     * Считаем по элементам {@code <testcase>}, а НЕ по атрибуту {@code tests=} в корне:
     * у класса, где все тесты лежат в {@code @Nested}, там стоит 0. На этом уже терялись тесты.
     * <p>
     * Свой отчёт (второго исполнения) в момент подсчёта ещё не дописан, поэтому в сумму
     * не попадает — сверяется именно первое исполнение.
     */
    private long executedTests() throws Exception {
        long total = 0;
        for (Path xml : reportFiles()) {
            try {
                Document document = factory().newDocumentBuilder().parse(xml.toFile());
                total += document.getElementsByTagName("testcase").getLength();
            } catch (IOException | org.xml.sax.SAXException broken) {
                // Непрочитанный отчёт занизил бы сумму, и гейт покраснел бы «не тем» способом.
                throw new AssertionError("не разобрать отчёт " + xml.getFileName() + ": " + broken.getMessage(), broken);
            }
        }
        return total;
    }

    /**
     * Отчёты ПЕРВОГО исполнения. Пакет {@code inventory} отбрасываем: оба его класса идут
     * вторым исполнением, и соседний успевает дописать свой отчёт раньше — без фильтра сумма
     * скакала бы от порядка на единицу.
     */
    private static List<Path> reportFiles() throws IOException {
        if (!Files.isDirectory(REPORTS)) {
            return List.of();
        }
        try (Stream<Path> s = Files.list(REPORTS)) {
            return s.filter(p -> {
                String name = p.getFileName().toString();
                return name.startsWith("TEST-") && name.endsWith(".xml") && !name.contains(".inventory.");
            }).sorted().toList();
        }
    }

    /** Разбор XML без внешних сущностей: отчёты читаются из каталога сборки. */
    private static DocumentBuilderFactory factory() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        return factory;
    }
}
