package io.github.kolomyychenkoai.allure.spring.inventory;

import io.qameta.allure.Epic;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ловит обвал сьюта: тесты перестали запускаться целым пакетом или пропали десятками.
 * <p>
 * Считает по настоящим отчётам surefire, а не по аннотациям: параметризованный метод даёт
 * N тестов из одного исходника, а дискаверинг JUnit шаблоны не разворачивает. Отчёты
 * первого исполнения уже лежат на диске, потому что класс идёт ВТОРЫМ исполнением,
 * из профиля {@code report-inventory}.
 * <p>
 * <b>С документами больше не сверяется.</b> Раньше здесь стояло точное число тестов
 * из {@code docs/architecture.md} и {@code docs/testing.md}. Замер по истории: настоящих
 * дефектов та сверка не поймала ни разу, а 36% коммитов в эти документы состояли
 * исключительно из перенумерации. Разбор — в задаче #123.
 */
@Epic("Внутренние проверки библиотеки")
class TestCountCheck {

    private static final Path REPORTS = Path.of("target/surefire-reports");
    private static final Path TESTS = Path.of("src/test/java/io/github/kolomyychenkoai/allure/spring");

    /**
     * Порог, а не точное число. Точное пришлось бы поднимать на каждый добавленный тест —
     * это и есть самосчёт, только переехавший из документа в гейт.
     * <p>
     * <b>Чего порог НЕ ловит:</b> потерю внутри полосы до него. Сегодня это 55 тестов:
     * рефакторинг, сносящий три класса по полтора десятка тестов, пройдёт молча. Размен
     * осознанный — цена точного числа выше. Обвал целым пакетом ловит проверка ниже,
     * исчезновение шага из отчёта — {@code ReportInventoryCheck}.
     * <p>
     * Порог держит и профили совместимости: {@code compat-boot-min} исключает из компиляции
     * шесть классов, это на полтора десятка тестов меньше. Точная сверка краснела бы там
     * всегда, поэтому её там и не было.
     */
    private static final int FLOOR = 550;

    @Test
    @DisplayName("сьют не схлопнулся: выполненных тестов не меньше порога")
    void suiteDidNotCollapse() throws Exception {
        long executed = executedTests();
        assertThat(executed)
                .as("выполнено %d тестов при пороге %d. Либо отчёты первого исполнения "
                        + "не прочитались вовсе, либо сьют потерял пакет или профиль — "
                        + "смотри, какие TEST-*.xml лежат в %s", executed, FLOOR, REPORTS)
                .isGreaterThan(FLOOR);
    }

    @Test
    @DisplayName("ни один тест не пропущен молча")
    void nothingIsSilentlySkipped() throws Exception {
        // Без этого порог выше слеп к главному способу схлопнуть сьют: surefire пишет
        // <testcase> и для пропущенных, поэтому @Disabled на классе из полусотни тестов
        // не меняет сумму ВООБЩЕ. Замерено: tests="57" skipped="57", элементов <testcase> 57.
        long skipped = skippedTests();
        assertThat(skipped)
                .as("пропущено %d тестов. @Disabled и assumption — это выключенная проверка, "
                        + "и она не должна включаться молча: либо чини, либо удаляй", skipped)
                .isZero();
    }

    @Test
    @DisplayName("каждый пакет с тестами дал хотя бы один отчёт")
    void everyKnownPackageStillRuns() throws Exception {
        // Истина берётся из ДЕРЕВА, а не из списка внутри теста: список в самом гейте чинится
        // правкой одной строки, и такая правка читается как рутина — гейт, умеющий себя
        // вылечить, перестаёт быть гейтом.
        Set<String> onDisk = packagesWithTests();
        assertThat(onDisk)
                .as("в %s не нашлось ни одного пакета с тестами — сломался обход дерева, "
                        + "и пустое множество ниже сошлось бы само с собой", TESTS)
                .hasSizeGreaterThan(3);

        Map<String, Long> counted = perPackage();
        assertThat(counted)
                .as("отчёты первого исполнения не прочитались — сверять нечего, и «совпало» "
                        + "тут было бы ложью")
                .isNotEmpty();

        Set<String> silent = new TreeSet<>(onDisk);
        silent.removeAll(counted.keySet());
        assertThat(silent)
                .as("в пакете есть тест-классы, а отчётов от них ноль: пакет перестал "
                        + "запускаться целиком. Чаще всего это маска surefire или testExcludes "
                        + "профиля, а не удалённые тесты")
                .isEmpty();
    }

    /** Пакеты, в которых на диске лежит хотя бы один класс с суффиксом {@code Test} или {@code IT}. */
    private static Set<String> packagesWithTests() throws IOException {
        Set<String> packages = new TreeSet<>();
        try (Stream<Path> files = Files.walk(TESTS)) {
            files.filter(p -> {
                String name = p.getFileName().toString();
                return name.endsWith("Test.java") || name.endsWith("IT.java");
            }).forEach(p -> {
                Path relative = TESTS.relativize(p);
                // Только первый сегмент: вложенные пакеты вроде rest/internal отчитываются
                // под именем своего корня, потому что имя отчёта строится из имени класса.
                if (relative.getNameCount() > 1) {
                    String pkg = relative.getName(0).toString();
                    // Симметрично reportFiles(): отчёты пакета inventory оттуда выброшены,
                    // потому что он идёт вторым исполнением. Без этой же строки класс
                    // *Test, заведённый здесь, дал бы «пакет перестал запускаться» —
                    // красный с диагнозом не про то.
                    if (!pkg.equals("inventory")) {
                        packages.add(pkg);
                    }
                }
            });
        }
        return packages;
    }

    /** Сколько тестов помечено пропущенными в отчётах первого исполнения. */
    private long skippedTests() throws Exception {
        long total = 0;
        for (Path xml : reportFiles()) {
            total += parse(xml).getElementsByTagName("skipped").getLength();
        }
        return total;
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
            Document document = parse(xml);
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
            Document document = parse(xml);
            // Минус пропущенные: surefire пишет <testcase> и для них, и без вычитания
            // @Disabled на целом классе не меняет сумму ни на единицу.
            total += document.getElementsByTagName("testcase").getLength()
                    - document.getElementsByTagName("skipped").getLength();
        }
        return total;
    }

    private Document parse(Path xml) throws Exception {
        try {
            return factory().newDocumentBuilder().parse(xml.toFile());
        } catch (IOException | org.xml.sax.SAXException broken) {
            // Непрочитанный отчёт занизил бы сумму, и гейт покраснел бы «не тем» способом.
            throw new AssertionError("не разобрать отчёт " + xml.getFileName() + ": " + broken.getMessage(), broken);
        }
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
