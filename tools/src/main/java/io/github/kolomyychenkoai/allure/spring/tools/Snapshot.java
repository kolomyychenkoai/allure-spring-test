package io.github.kolomyychenkoai.allure.spring.tools;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Снимок прогона сервиса-потребителя: исходы тестов плюс наблюдаемое поведение.
 * <p>
 * Из него строится A/B-дифф «с библиотекой» против «без»: снимки обязаны совпасть.
 */
final class Snapshot {

    private Snapshot() {
    }

    static int run(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Использование: snapshot <каталог-сервиса>");
            return 2;
        }
        Path root = Path.of(args[0]);
        if (!Files.isDirectory(root)) {
            // Без этой проверки опечатка в пути давала снимок «тестов нет, дампов нет» с кодом 0,
            // а два таких снимка совпадают между собой — A/B отрапортовал бы «библиотека ничего
            // не изменила», не прочитав ни одного теста.
            System.err.println("нет каталога сервиса: " + root);
            return 2;
        }
        List<String> lines = new ArrayList<>();

        collectTests(root.resolve("target").resolve("surefire-reports"), lines);
        collectBehavior(root.resolve("target").resolve("behavior"), lines);

        System.out.println(lines.stream()
                .map(String::stripTrailing)
                .filter(line -> !line.isBlank())
                .sorted()
                .reduce((a, b) -> a + "\n" + b)
                .orElse(""));
        return 0;
    }

    /**
     * Исходы берём по элементам {@code <testcase>}, а НЕ по атрибуту {@code tests=} в корне:
     * у класса, где все тесты в {@code @Nested}, там стоит 0 — на этом уже терялись тесты
     * при апгрейде.
     */
    private static void collectTests(Path reports, List<String> lines)
            throws IOException, ParserConfigurationException {
        if (!Files.isDirectory(reports)) {
            return;
        }
        List<Path> files;
        try (Stream<Path> s = Files.list(reports)) {
            files = s.filter(p -> {
                String name = p.getFileName().toString();
                return name.startsWith("TEST-") && name.endsWith(".xml");
            }).sorted().toList();
        }
        DocumentBuilderFactory factory = safeFactory();
        for (Path xml : files) {
            Document document;
            try {
                DocumentBuilder builder = factory.newDocumentBuilder();
                document = builder.parse(xml.toFile());
            } catch (Exception broken) {
                lines.add("TEST | <неразобранный отчёт %s: %s>".formatted(xml.getFileName(), broken.getMessage()));
                continue;
            }
            NodeList cases = document.getElementsByTagName("testcase");
            for (int i = 0; i < cases.getLength(); i++) {
                Element testCase = (Element) cases.item(i);
                lines.add("TEST | %s#%s → %s".formatted(
                        attribute(testCase, "classname"), attribute(testCase, "name"), outcome(testCase)));
            }
        }
    }

    /**
     * Разбор XML без внешних сущностей: DOCTYPE запрещён, XInclude и подстановка сущностей
     * выключены.
     * <p>
     * По умолчанию парсер сходил бы по ссылке из документа — прочитал бы локальный файл
     * или дёрнул сеть. Отчёты surefire мы читаем из каталога ЧУЖОГО сервиса, то есть входные
     * данные приходят снаружи, и доверять им нельзя.
     */
    private static DocumentBuilderFactory safeFactory() throws ParserConfigurationException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        return factory;
    }

    private static String attribute(Element element, String name) {
        String value = element.getAttribute(name);
        return value.isEmpty() ? "?" : value;
    }

    private static String outcome(Element testCase) {
        if (testCase.getElementsByTagName("failure").getLength() > 0) {
            return "FAILED";
        }
        if (testCase.getElementsByTagName("error").getLength() > 0) {
            return "ERROR";
        }
        if (testCase.getElementsByTagName("skipped").getLength() > 0) {
            return "SKIPPED";
        }
        return "PASSED";
    }

    /**
     * Файл НА КАЖДУЮ JVM: под {@code forkCount>1} форков несколько, и общий файл последний
     * закрывшийся затирал бы. Сливаем все.
     */
    private static void collectBehavior(Path behavior, List<String> lines) throws IOException {
        List<Path> dumps = List.of();
        if (Files.isDirectory(behavior)) {
            try (Stream<Path> s = Files.list(behavior)) {
                dumps = s.filter(p -> p.getFileName().toString().endsWith(".log")).sorted().toList();
            }
        }
        if (dumps.isEmpty()) {
            lines.add("BEHAVIOR | <дампов нет: рекордер не отработал>");
            return;
        }
        for (Path dump : dumps) {
            lines.addAll(Files.readAllLines(dump, StandardCharsets.UTF_8));
        }
    }
}
