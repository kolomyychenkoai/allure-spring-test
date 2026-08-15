package io.github.kolomyychenkoai.allure.spring.unit;

import io.qameta.allure.Epic;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Тест карты модулей в {@code docs/architecture.md}: документ обещает ПОЛНЫЙ список точек
 * входа, и обещание проверяется в обе стороны.
 * <p>
 * Архитектурный обзор устаревает молча и тем быстрее, чем он полезнее: добавили модуль —
 * карта соврала, а узнает об этом следующий ревьюер. Точки входа берём из тех же файлов,
 * из которых их берёт Spring, поэтому разъехаться карта и код не могут.
 */
@Epic("Внутренние проверки библиотеки")
class ArchitectureDocTest {

    private static final Path DOC = Path.of("docs/architecture.md");
    private static final Path FACTORIES = Path.of("src/main/resources/META-INF/spring.factories");
    private static final Path IMPORTS = Path.of(
            "src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports");

    /** Файлы `src/main`, из которых считаются размеры документа. */
    private static List<Path> mainSources() throws IOException {
        try (var files = Files.walk(Path.of("src/main/java"))) {
            return files.filter(p -> p.toString().endsWith(".java")).toList();
        }
    }

    private static long lines(Path file) throws IOException {
        return Files.readAllLines(file, StandardCharsets.UTF_8).size();
    }

    /** Сколько файлов `src/main` содержат подстроку. */
    private static long filesContaining(String needle) throws IOException {
        long n = 0;
        for (Path p : mainSources()) {
            if (Files.readString(p, StandardCharsets.UTF_8).contains(needle)) {
                n++;
            }
        }
        return n;
    }

    /** Простые имена классов, объявленных точками входа в ресурсах Spring. */
    private static Set<String> entryPoints(Path resource) throws IOException {
        return Arrays.stream(Files.readString(resource, StandardCharsets.UTF_8).split("[,\\\\\\s]+"))
                .filter(s -> s.startsWith("io.github.kolomyychenkoai"))
                .map(s -> s.substring(s.lastIndexOf('.') + 1))
                .collect(Collectors.toCollection(TreeSet::new));
    }

    @Test
    @DisplayName("каждый TestExecutionListener и автоконфиг назван в архитектурном обзоре")
    void everyEntryPointIsOnTheMap() throws IOException {
        String doc = Files.readString(DOC, StandardCharsets.UTF_8);
        Set<String> declared = new TreeSet<>(entryPoints(FACTORIES));
        declared.addAll(entryPoints(IMPORTS));

        assertThat(declared).as("сбор точек входа сломался — проверять нечего").hasSizeGreaterThan(10);
        Set<String> missing = declared.stream().filter(c -> !doc.contains(c))
                .collect(Collectors.toCollection(TreeSet::new));
        assertThat(missing)
                .as("точка входа есть в ресурсах Spring, но не названа в docs/architecture.md — "
                        + "карта модулей врёт ровно тому, для кого написана")
                .isEmpty();
    }

    @Test
    @DisplayName("маршрут чтения ведёт в существующие классы")
    void readingRoutePointsAtRealClasses() throws IOException {
        String doc = Files.readString(DOC, StandardCharsets.UTF_8);
        // Мёртвая ссылка в маршруте дороже опечатки в тексте: ревьюер идёт по нему первым делом.
        List<String> route = List.of("AllureInstrumentation", "AllureAdviceSupport",
                "AllureAssertionsListener", "AllureMockMvcAutoConfiguration",
                "MovedCustomizerRegistrar", "AllureRepositoryAspect", "InstrumentationDiagnostics");

        for (String type : route) {
            assertThat(doc).as("класс «%s» пропал из маршрута чтения", type).contains(type);
            try (var files = Files.walk(Path.of("src/main/java"))) {
                assertThat(files.anyMatch(p -> p.getFileName().toString().equals(type + ".java")))
                        .as("маршрут ведёт в несуществующий класс «%s»", type)
                        .isTrue();
            }
        }
    }

    @Test
    @DisplayName("числа обзора пересчитываются из исходников и совпадают с текстом")
    void numbersMatchRepository() throws IOException {
        // Дрейф чисел — самый частый способ документа соврать: сборку не ломает, при чтении
        // не виден. За время работы над обзором число протухало дважды (487 → 489 тестов через
        // час после написания; объём маршрута был взят из головы). Считаем заново из исходников
        // и требуем, чтобы текст содержал именно это значение — в той фразе, где его увидит читатель.
        String doc = Files.readString(DOC, StandardCharsets.UTF_8);
        long mainLines = 0;
        for (Path p : mainSources()) {
            mainLines += lines(p);
        }
        long testClasses;
        try (var files = Files.walk(Path.of("src/test/java"))) {
            testClasses = files.filter(p -> p.toString().endsWith(".java")).count();
        }
        long internalClasses;
        try (var files = Files.list(Path.of("src/main/java/io/github/kolomyychenkoai/allure/spring/internal"))) {
            internalClasses = files.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !p.getFileName().toString().equals("package-info.java")).count();
        }

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("классы и строки src/main",
                "**%d классов / %d строк**".formatted(mainSources().size(), mainLines));
        // Форма слова подобрана под текущее число. Изменится так, что поедет падеж, —
        // тест скажет поправить и текст, и этот шаблон: врать документу дороже.
        expected.put("тест-классы", "**%d классов**".formatted(testClasses));
        expected.put("листенеры", "%d листенеров".formatted(entryPoints(FACTORIES).size()));
        expected.put("автоконфиги", "%d автоконфига".formatted(entryPoints(IMPORTS).size()));
        expected.put("классы internal", "(%d классов + `package-info`)".formatted(internalClasses));
        expected.put("объём маршрута", "Итого %d строка".formatted(routeLines()));
        expected.put("файлы со строковым матчером", "%d файлов".formatted(filesContaining("named(\"")));

        List<String> stale = expected.entrySet().stream()
                .filter(e -> !doc.contains(e.getValue()))
                .map(e -> e.getKey() + ": в тексте нет «" + e.getValue() + "»")
                .toList();
        assertThat(stale)
                .as("число в docs/architecture.md разошлось с исходниками — документ врёт молча, "
                        + "и первым это заметит читатель, а не сборка")
                .isEmpty();
    }

    @Test
    @DisplayName("всё, на что документ ссылается, существует: классы, типы, файлы, разделы")
    void everyReferenceResolves() throws IOException {
        // Самая дешёвая ложь документа — сослаться на то, чего нет: она не ломает сборку
        // и не видна при чтении, а читатель идёт по ссылке и упирается в пустоту. Так уже
        // было: обзор обещал в InstrumentationDiagnostics дамп и гейт, которые живут в тестах.
        String doc = Files.readString(DOC, StandardCharsets.UTF_8);
        List<String> broken = new java.util.ArrayList<>();

        // 1. НАШИ классы — по ПРЕФИКСУ имени. Список полных имён ловил бы только то, что в нём
        // уже есть, и переименование в несуществующий класс проходило бы мимо (проверено
        // мутацией). Суффиксы («…Listener») цепляют чужие типы вроде TestExecutionListener,
        // поэтому берём начала имён, которые бывают только у нас.
        var ours = java.util.regex.Pattern.compile("`[a-z/]*((?:Allure|Moved|ClassPresence|ByteBuddyP"
                + "|ByteBuddyC|JpaLaziness|Instrumentation|Activation|ListenerDegradation"
                + "|ArchitectureDoc)[A-Za-z]*)`");
        var m = ours.matcher(doc);
        while (m.find()) {
            String type = m.group(1);
            try (var files = Files.walk(Path.of("src"))) {
                if (files.noneMatch(p -> p.getFileName().toString().equals(type + ".java"))) {
                    broken.add("наш класс «" + type + "» упомянут, но файла нет");
                }
            }
        }

        // 2. ЧУЖИЕ типы, на которых стоят §5 и §9. Проверка в ОБЕ стороны: тип обязан
        // резолвиться на classpath И быть назван в документе. Односторонняя («если документ
        // упоминает — проверить») не ловит переименование в тексте: имени просто не находится,
        // и проверка молча пропускается (поймано мутацией).
        for (String fqn : List.of("org.springframework.test.web.servlet.MockMvc",
                "org.assertj.core.api.AbstractAssert",
                "org.springframework.web.client.DefaultRestClientBuilder",
                "io.restassured.internal.ValidatableResponseOptionsImpl",
                "org.mockito.internal.creation.bytebuddy.InlineByteBuddyMockMaker",
                "liquibase.changelog.ChangeSet",
                "com.github.tomakehurst.wiremock.WireMockServer",
                "org.springframework.http.client.support.InterceptingHttpAccessor")) {
            String simple = fqn.substring(fqn.lastIndexOf('.') + 1);
            try {
                Class.forName(fqn, false, getClass().getClassLoader());
            } catch (ClassNotFoundException gone) {
                broken.add("чужой тип «" + simple + "» не резолвится: " + fqn);
                continue;
            }
            if (!doc.contains(simple)) {
                broken.add("чужой тип «" + simple + "» пропал из документа — либо он переименован "
                        + "в тексте, либо из таблицы рисков ушла строка");
            }
        }

        // 3. Методы и константы: имена берём ИЗ ТЕКСТА, а не сверяем список с текстом —
        // иначе `describeResponseXX` проходит проверку как подстрока (поймано мутацией).
        var members = java.util.regex.Pattern.compile("`([a-z]+[A-Z][A-Za-z]+|[A-Z]{2,}_[A-Z_]+)`").matcher(doc);
        while (members.find()) {
            String member = members.group(1);
            if (filesContaining(member) == 0) {
                broken.add("метод/константа «" + member + "» упомянут, но в src/main его нет");
            }
        }

        // 4. Пути к файлам и перекрёстные ссылки на разделы.
        var paths = java.util.regex.Pattern.compile("`((?:docs|scripts|src)/[A-Za-z0-9_./-]+)`").matcher(doc);
        while (paths.find()) {
            if (!Files.exists(Path.of(paths.group(1)))) {
                broken.add("путь «" + paths.group(1) + "» упомянут, но файла нет");
            }
        }
        var sections = java.util.regex.Pattern.compile("§(\\d+)").matcher(doc);
        while (sections.find()) {
            if (!doc.contains("## " + sections.group(1) + ". ")) {
                broken.add("ссылка на §" + sections.group(1) + ", а такого раздела нет");
            }
        }

        assertThat(broken)
                .as("документ ссылается в пустоту — читатель пойдёт по ссылке и не найдёт ничего")
                .isEmpty();
    }

    /** Суммарный объём файлов «маршрута чтения»: документ обещает его одним числом. */
    private static long routeLines() throws IOException {
        long total = 0;
        for (String rel : List.of("internal/AllureInstrumentation", "internal/AllureAdviceSupport",
                "assertion/AllureAssertionsListener", "assertion/internal/AllureAssertJInstrumentation",
                "rest/AllureMockMvcAutoConfiguration", "internal/MovedCustomizerRegistrar",
                "data/internal/AllureRepositoryAspect", "internal/InstrumentationDiagnostics")) {
            total += lines(Path.of("src/main/java/io/github/kolomyychenkoai/allure/spring/" + rel + ".java"));
        }
        return total;
    }
}
