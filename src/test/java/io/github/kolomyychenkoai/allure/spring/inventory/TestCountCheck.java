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
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Ловит обвал сьюта: тесты перестали запускаться пакетом, классом или ушли в пропуск.
 * <p>
 * Считает по настоящим отчётам surefire, а не по аннотациям: параметризованный метод даёт
 * N тестов из одного исходника, а дискаверинг JUnit шаблоны не разворачивает. Отчёты
 * первого исполнения уже лежат на диске, потому что класс идёт ВТОРЫМ исполнением,
 * из профиля {@code report-inventory}.
 * <p>
 * <b>С прозой документов не сверяется.</b> Раньше здесь стояло точное число тестов
 * из {@code docs/architecture.md} и {@code docs/testing.md}. Почему так делать перестали
 * и каким замером это решено — {@code docs/review-playbook.md}, проход 2.10, ось 4.
 * <p>
 * ⚠️ После этого класс — ЕДИНСТВЕННЫЙ сторож «тесты ещё запускаются», и исполняется он
 * по явному {@code <include>} в профиле. Что строка из профиля не пропадёт, стережёт
 * {@code unit/PomCompatibilityTest.inventoryProfileIsWiredUp}.
 */
@Epic("Внутренние проверки библиотеки")
class TestCountCheck {

    private static final Path REPORTS = Path.of("target/surefire-reports");
    private static final Path TESTS = Path.of("src/test/java/io/github/kolomyychenkoai/allure/spring");

    /**
     * Сколько тестов выполнялось в прошлый раз. Лежит файлом рядом с эталоном отчёта и правится
     * тем же способом — осознанно и коммитом, см. {@code docs/testing.md} §5.
     * <p>
     * Это не самосчёт в прозе: число живёт в служебном файле, человеку не показывается и ничего
     * ему не обещает. Добавил тест — база молча устаревает вниз и сборка зелёная; потерял
     * тесты — красная. Тем и отличается от порога-константы: константу поднимают редко,
     * и разрыв между ней и правдой растёт, пока не станет шире любой потери.
     */
    private static final Path BASELINE = Path.of("src/test/inventory/test-count-baseline.txt");

    /** Имя атрибута-флага: {@code -Dtestcount.update=true} пересевает базу вместо сверки. */
    private static final boolean UPDATE = Boolean.getBoolean("testcount.update");

    /** Пакеты из имени отчёта: {@code …allure.spring.<пакет>.<Класс>.xml}. */
    private static final Pattern PACKAGE = Pattern.compile("allure\\.spring\\.([a-z]+)\\.");

    /** Любая аннотация, по которой JUnit заводит тест-кейс. */
    private static final Pattern TEST_METHOD = Pattern.compile("@(Test|ParameterizedTest|RepeatedTest|TestFactory|TestTemplate)\\b");

    @Test
    @DisplayName("сьют не схлопнулся: выполнено не меньше, чем в прошлый раз")
    void suiteDidNotCollapse() throws Exception {
        requireReports();
        long executed = executedTests();

        if (UPDATE) {
            Files.writeString(BASELINE, executed + "\n", StandardCharsets.UTF_8);
        }
        long baseline = Long.parseLong(Files.readString(BASELINE, StandardCharsets.UTF_8).trim());

        assertThat(executed)
                .as("выполнено %d тестов, в прошлый раз было %d. Тесты пропали — смотри, какой "
                        + "класс или профиль перестал их давать. Если сокращение осознанное, "
                        + "пересей базу: mvn clean test -Dtestcount.update=true, и объясни "
                        + "в тексте PR, что убыло", executed, baseline)
                .isGreaterThanOrEqualTo(baseline);
    }

    @Test
    @DisplayName("ни один тест не пропущен молча")
    void nothingIsSilentlySkipped() throws Exception {
        // Без гарда «пропущено ноль» на пустом каталоге читается как факт, хотя это отсутствие
        // данных: reportFiles() отдаёт пустой список, сумма даёт ноль, ассерт проходит.
        // Такой зелёный хуже красного — он утверждает то, чего не проверял.
        requireReports();

        // Считать обязательно: surefire пишет <testcase> и для пропущенных, поэтому @Disabled
        // на классе из полусотни тестов не меняет сумму выполненных ни на единицу.
        long skipped = skippedTests();
        assertThat(skipped)
                .as("пропущено %d тестов. @Disabled и assumption — это выключенная проверка, "
                        + "и она не должна включаться молча: либо чини, либо удаляй", skipped)
                .isZero();
    }

    @Test
    @DisplayName("каждый пакет с тестами дал хотя бы один отчёт")
    void everyKnownPackageStillRuns() throws Exception {
        requireReports();

        // Истина берётся из ДЕРЕВА, а не из списка внутри теста: список в самом гейте чинится
        // правкой одной строки, и такая правка читается как рутина — гейт, умеющий себя
        // вылечить, перестаёт быть гейтом.
        Set<String> onDisk = packagesWithTests();
        // Порог нарочно грубый: он ловит сломавшийся обход дерева, а не убыль пакетов.
        // Точное число пакетов сверять нечем — их убыль и есть предмет проверки ниже.
        assertThat(onDisk)
                .as("в %s не нашлось ни одного пакета с тестами — сломался обход дерева, "
                        + "и пустое множество ниже сошлось бы само с собой", TESTS)
                .hasSizeGreaterThan(3);

        Set<String> silent = new TreeSet<>(onDisk);
        silent.removeAll(packagesWithReports());
        assertThat(silent)
                .as("в пакете есть тест-классы, а отчётов от них ноль: пакет перестал "
                        + "запускаться целиком. Чаще всего это маска surefire или testExcludes "
                        + "профиля, а не удалённые тесты")
                .isEmpty();
    }

    /**
     * Пакеты, от которых прогон ОБЯЗАН дать отчёт: там лежит класс под маской surefire,
     * и в нём есть хотя бы одна тестовая аннотация.
     * <p>
     * Одного имени файла мало: хелпер, названный {@code …Test} без единого {@code @Test},
     * отчёта не даёт, и сверка объявила бы его пакет «переставшим запускаться» — красный
     * с причиной, которой нет.
     * <p>
     * Класс, лежащий прямо в корне {@code spring/}, не считается: {@link #packagesWithReports()}
     * его тоже не видит (шаблон требует сегмента пакета), так что обе стороны слепы
     * согласованно и ложного красного не будет.
     */
    private static Set<String> packagesWithTests() throws IOException {
        Set<String> packages = new TreeSet<>();
        try (Stream<Path> files = Files.walk(TESTS)) {
            files.filter(TestCountCheck::looksLikeTestClass).forEach(p -> {
                Path relative = TESTS.relativize(p);
                if (relative.getNameCount() > 1) {
                    String pkg = relative.getName(0).toString();
                    // Симметрично reportFiles(): отчёты пакета inventory оттуда выброшены,
                    // потому что он идёт вторым исполнением. Без этой же строки класс *Test,
                    // заведённый здесь, дал бы «пакет перестал запускаться» — красный
                    // с диагнозом не про то.
                    if (!pkg.equals("inventory")) {
                        packages.add(pkg);
                    }
                }
            });
        }
        return packages;
    }

    private static boolean looksLikeTestClass(Path file) {
        String name = file.getFileName().toString();
        if (!name.endsWith("Test.java") && !name.endsWith("IT.java")) {
            return false;
        }
        try {
            return TEST_METHOD.matcher(Files.readString(file, StandardCharsets.UTF_8)).find();
        } catch (IOException unreadable) {
            throw new AssertionError("не прочитать " + file, unreadable);
        }
    }

    /**
     * Пакеты, от которых отчёты ЕСТЬ. Вложенные пакеты вроде {@code rest/internal} схлопываются
     * до корня: шаблон {@link #PACKAGE} берёт из имени отчёта только первый сегмент.
     */
    private Set<String> packagesWithReports() throws IOException {
        Set<String> reported = new TreeSet<>();
        for (Path xml : reportFiles()) {
            Matcher pkg = PACKAGE.matcher(xml.getFileName().toString());
            if (pkg.find()) {
                reported.add(pkg.group(1));
            }
        }
        return reported;
    }

    /**
     * Гард на пустой вход, общий для всех трёх проверок: без отчётов каждая из них зеленеет
     * по-своему, и зелёное читается как факт, хотя это отсутствие данных.
     */
    private void requireReports() throws IOException {
        assertThat(reportFiles())
                .as("в %s нет отчётов первого исполнения — проверять нечего, и любой зелёный "
                        + "здесь был бы ложью. Гоняй полный `mvn clean test`", REPORTS)
                .isNotEmpty();
    }

    /** Сколько тестов помечено пропущенными в отчётах первого исполнения. */
    private long skippedTests() throws Exception {
        long total = 0;
        for (Path xml : reportFiles()) {
            total += parse(xml).getElementsByTagName("skipped").getLength();
        }
        return total;
    }

    /**
     * Считаем по элементам {@code <testcase>}, а НЕ по атрибуту {@code tests=} в корне:
     * у класса, где все тесты лежат в {@code @Nested}, там стоит 0. На этом уже терялись тесты.
     * Пропущенные вычитаются — разбор в {@link #nothingIsSilentlySkipped()}.
     */
    private long executedTests() throws Exception {
        long total = 0;
        for (Path xml : reportFiles()) {
            Document document = parse(xml);
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

    private static DocumentBuilderFactory factory() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        // Отчёты свои, но парсер общий: внешние сущности выключаем безусловно.
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        return factory;
    }
}
