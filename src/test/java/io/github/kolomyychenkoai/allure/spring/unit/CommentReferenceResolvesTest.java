package io.github.kolomyychenkoai.allure.spring.unit;

import io.qameta.allure.Epic;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Имя, названное в комментарии рядом с заявкой о факте, обязано существовать в репозитории.
 * <p>
 * §6.4 код-стандарта обещает: «пересказ устаревает молча, ссылка краснеет». Без этого гейта
 * вторая половина обещания — неправда: ссылка на удалённый тест, метод или фикстуру не краснеет
 * никогда и молча врёт следующему редактору. Пять находок двух последних кругов — ровно этот
 * класс, и все пять нашли люди поштучно.
 * <p>
 * Кандидатов берём ТОЛЬКО со строк с маркерами заявки ({@code Мутация:}, {@code Держит},
 * {@code краснит}, {@code Замерено}, {@code см.}). Маркер — место, где автор утверждает факт
 * О РЕПОЗИТОРИИ; вне маркера комментарий объясняет чужой мир, и там не-наших имён большинство.
 * Замер: без маркеров 564 кандидата и 86 ненайденных, с маркерами — 100 и 6.
 * <p>
 * Резолвим по УПОМИНАНИЮ в некомментарном коде, а не по объявлению: чужие типы у нас никогда
 * не объявлены, а упомянуты — сплошь. Замер: «по объявлениям» даёт 77 ненайденных против 6.
 */
@Epic("Внутренние проверки библиотеки")
class CommentReferenceResolvesTest {

    private static final List<Path> ROOTS = List.of(
            Path.of("src/main/java"), Path.of("src/test/java"));

    /**
     * Места, где автор утверждает факт о репозитории. Границы слова обязательны: «Держать этот
     * код» — не маркер, а «Держит» — маркер.
     * <p>
     * ⚠️ НЕ убирай {@link Pattern#UNICODE_CHARACTER_CLASS}: без него {@code \b} в Java считает
     * словом только ASCII, границы вокруг кириллицы не срабатывают, и маркеров находится РОВНО
     * НОЛЬ. Гейт при этом зеленеет, ничего не просмотрев. Держит якорь на числе маркер-строк.
     */
    private static final Pattern MARKER = Pattern.compile(
            "\\b(?:Мутация|мутация|Держит|Держат|Держи|краснит|краснеет|краснел|Замерено|замерено"
                    + "|Замерен|Замерена|проверено)\\b|\\bсм\\.",
            Pattern.UNICODE_CHARACTER_CLASS);

    /** Идентификатор с горбом: длиннее пяти символов, чтобы не ловить прозаические слова. */
    private static final Pattern IDENTIFIER = Pattern.compile(
            "(?<![A-Za-z0-9_$.#])([A-Za-z][A-Za-z0-9]*[a-z][A-Z][A-Za-z0-9]*)(?![A-Za-z0-9_$])");

    /** Тот же горб, но без запрета на точку слева: для СЛОВАРЯ, где `x.foo()` тоже имя. */
    private static final Pattern ANY_IDENTIFIER = Pattern.compile(
            "([A-Za-z][A-Za-z0-9]*[a-z][A-Z][A-Za-z0-9]*)");

    /** {@code Owner.member} и {@code Owner#member}: владелец резолвится — про член не спрашиваем. */
    private static final Pattern QUALIFIED = Pattern.compile("([A-Za-z][A-Za-z0-9]*)[.#](\\w+)");

    /** {@code флаг=значение} — это настройка, а не имя. */
    private static final Pattern ASSIGNMENT = Pattern.compile("([A-Za-z][A-Za-z0-9]*)\\s*=");

    /**
     * Имена, которых в репозитории нет и не будет: продукты и технологии, названные прозой.
     * <p>
     * ⚠️ НЕ превращай это в плоский список-глушитель. У записи обязаны быть причина и ЕДИНСТВЕННОЕ
     * место; гейт краснеет и на саму запись — мёртвую или расползшуюся, — иначе реестр растёт молча.
     */
    private static final List<Allowed> ALLOWED = List.of(
            new Allowed("ShedLock", "имя чужого продукта, названное прозой; классов у нас нет",
                    "AllureDataAutoConfigurationTest.java"),
            new Allowed("EclipseLink", "имя JPA-провайдера прозой; его типы резолвим по строке",
                    "JpaLaziness.java"),
            new Allowed("AspectJ", "имя технологии прозой, не тип; тип называется отдельно",
                    "RepositoryNoticeOnRealSpringDataTest.java"));

    private record Allowed(String token, String reason, String file) {
    }

    private record Candidate(String token, String where) {
    }

    @Test
    @DisplayName("каждое имя рядом с заявкой о факте существует в коде")
    void everyNameNextToAClaimResolves() throws IOException {
        Scan scan = scan();

        // ЯКОРИ. Стадий три, и каждая ломается молча своим способом: обход дерева, регексп
        // маркеров (кириллица!), токенайзер. Один счётчик их не различает.
        assertThat(scan.files).as("обход дерева сломался — прочитано слишком мало файлов")
                .isGreaterThan(150);
        assertThat(scan.markerLines).as("регексп маркеров перестал совпадать — заявок не найдено. "
                        + "Первое, что проверить: кодировку чтения файлов")
                .isGreaterThan(180);
        assertThat(scan.candidates).as("токенайзер сломался: заявки есть, имён из них не извлеклось")
                .hasSizeGreaterThan(70);

        Set<String> known = knownTokens();
        assertThat(known).as("словарь пуст — резолвиться будет нечему, и гейт покраснеет на всём")
                .hasSizeGreaterThan(1000);

        List<String> unresolved = new ArrayList<>();
        for (Candidate c : scan.candidates) {
            if (known.contains(c.token()) || allowedAt(c)) {
                continue;
            }
            unresolved.add(c.token() + "   (" + c.where() + ")");
        }
        assertThat(unresolved)
                .as("в комментарии рядом с заявкой названо имя, которого в коде нет: ссылка "
                        + "не покраснеет никогда и будет врать следующему редактору (§6.4). "
                        + "Либо поправь имя, либо перепиши фразу так, чтобы она не выглядела ссылкой")
                .isEmpty();
    }

    @Test
    @DisplayName("реестр исключений не мёртвый и не расползся")
    void allowListStaysHonest() throws IOException {
        Scan scan = scan();
        assertThat(ALLOWED).as("реестр исключений разросся — это уже глушитель, а не исключения")
                .hasSizeLessThan(9);

        for (Allowed a : ALLOWED) {
            assertThat(a.reason()).as("у исключения «%s» нет внятной причины", a.token())
                    .hasSizeGreaterThan(20);
            List<String> seen = scan.candidates.stream()
                    .filter(c -> c.token().equals(a.token())).map(Candidate::where).toList();
            assertThat(seen).as("исключение «%s» больше не срабатывает — мёртвая запись, удали её",
                    a.token()).isNotEmpty();
            assertThat(seen).as("исключение «%s» расползлось за пределы %s", a.token(), a.file())
                    .allMatch(w -> w.startsWith(a.file() + ":"));
        }
    }

    /** Гейт обязан ловить нарушение — иначе он зелен, потому что слеп. */
    @Nested
    @DisplayName("ловит")
    class Catches {

        @Test
        @DisplayName("имя, которого в репозитории нет")
        void unknownName() {
            // Мутация, которую видит ИМЕННО этот гейт: переименовать метод в коде, оставив
            // старое имя в комментарии рядом с «Мутация:». javac зелёный (все ссылки переехали),
            // число тестов не изменилось, доки не тронуты — краснеет только он.
            assertThat(unresolvedIn("// Мутация: убрать проверку hasNoSuchIdentifierHere → RED."))
                    .containsExactly("hasNoSuchIdentifierHere");
        }
    }

    /** И обязан молчать на законном — гейт, который шумит, обучает себя пролистывать. */
    @Nested
    @DisplayName("не ловит законное")
    class DoesNotCatchLegitimate {

        @Test
        @DisplayName("чужие типы, которые в нашем коде упомянуты")
        void foreignTypesMentionedInCode() {
            assertThat(unresolvedIn(" * Держит {@code TransactionalProxy} и {@code ProceedingJoinPoint}."))
                    .isEmpty();
        }

        @Test
        @DisplayName("чужой член через владельца")
        void memberViaOwner() {
            assertThat(unresolvedIn(" * Замерено: {@code ClassUtils.getInterfaceMethodIfPossible}."))
                    .isEmpty();
        }

        @Test
        @DisplayName("настройка вида флаг=значение")
        void settingIsNotAName() {
            assertThat(unresolvedIn("// Мутация: runOrder=random → порядок поедет.")).isEmpty();
        }

        @Test
        @DisplayName("вложенный класс через доллар")
        void nestedTypeSplitsOnDollar() {
            assertThat(unresolvedIn(" * см. {@code EmbeddedDatabaseFactory$EmbeddedDataSourceProxy}"))
                    .isEmpty();
        }

        @Test
        @DisplayName("строка без маркера заявки не рассматривается вовсе")
        void lineWithoutMarkerIsIgnored() {
            assertThat(unresolvedIn("// Просто пояснение про someCompletelyUnknownThing.")).isEmpty();
        }
    }

    // ─────────────────────────── механика ───────────────────────────

    private record Scan(int files, int markerLines, List<Candidate> candidates) {
    }

    private static Scan scan() throws IOException {
        int files = 0;
        int markerLines = 0;
        List<Candidate> candidates = new ArrayList<>();
        for (Path root : ROOTS) {
            try (Stream<Path> tree = Files.walk(root)) {
                for (Path f : tree.filter(p -> p.toString().endsWith(".java")).sorted().toList()) {
                    files++;
                    String[] lines = Files.readString(f, StandardCharsets.UTF_8).split("\n", -1);
                    for (int i = 0; i < lines.length; i++) {
                        String line = lines[i].strip();
                        if (!line.startsWith("*") && !line.startsWith("//") && !line.startsWith("/*")) {
                            continue;
                        }
                        if (!MARKER.matcher(line).find()) {
                            continue;
                        }
                        markerLines++;
                        String where = f.getFileName() + ":" + (i + 1);
                        for (String token : tokens(line)) {
                            candidates.add(new Candidate(token, where));
                        }
                    }
                }
            }
        }
        return new Scan(files, markerLines, candidates);
    }

    /** Имена со строки, после трёх вычетов: владелец.член, флаг=значение, A$B. */
    private static Set<String> tokens(String line) {
        String work = line.replace('$', ' ');                 // вложенный класс — две части
        Set<String> skip = new LinkedHashSet<>();
        Matcher q = QUALIFIED.matcher(work);
        while (q.find()) {
            skip.add(q.group(2));                             // член спрашиваем с владельца
        }
        Matcher a = ASSIGNMENT.matcher(work);
        while (a.find()) {
            skip.add(a.group(1));                             // это настройка, а не имя
        }
        Set<String> found = new LinkedHashSet<>();
        Matcher m = IDENTIFIER.matcher(work);
        while (m.find()) {
            String token = m.group(1);
            if (!skip.contains(token)) {
                found.add(token);
            }
        }
        return found;
    }

    /** Всё, что упомянуто в НЕкомментарном коде, плюс простые имена классов с classpath. */
    private static Set<String> knownTokens() throws IOException {
        Set<String> known = new LinkedHashSet<>();
        for (Path root : List.of(Path.of("src"), Path.of("tools/src"))) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (Stream<Path> tree = Files.walk(root)) {
                for (Path f : tree.filter(p -> p.toString().endsWith(".java")).toList()) {
                    // ⚠️ НЕ используй здесь IDENTIFIER: у него запрещена точка слева, и метод
                    // из `x.foo()` в словарь не попадёт — гейт покраснеет на законной ссылке.
                    Matcher m = ANY_IDENTIFIER.matcher(
                            stripComments(Files.readString(f, StandardCharsets.UTF_8)));
                    while (m.find()) {
                        known.add(m.group(1));
                    }
                }
            }
        }
        // Типы JDK лежат не в jar-ах, а в jrt-образе — перебором пакетов дешевле, чем ходить
        // в ModuleFinder ради десятка имён.
        for (String pkg : List.of("java.lang.", "java.util.", "java.io.", "java.sql.",
                "java.time.", "java.util.function.", "java.util.concurrent.")) {
            for (String probe : List.of("RandomAccess", "AutoCloseable", "CharSequence")) {
                tryClass(pkg + probe, known);
            }
        }
        for (String entry : System.getProperty("java.class.path", "").split(java.io.File.pathSeparator)) {
            if (!entry.endsWith(".jar")) {
                continue;
            }
            try (java.util.jar.JarFile jar = new java.util.jar.JarFile(entry)) {
                jar.stream().map(java.util.zip.ZipEntry::getName)
                        .filter(n -> n.endsWith(".class"))
                        .forEach(n -> {
                            String simple = n.substring(n.lastIndexOf('/') + 1, n.length() - 6);
                            for (String part : simple.split("\\$")) {
                                known.add(part);
                            }
                        });
            } catch (IOException unreadableJar) {
                // битый или отсутствующий jar словарь не рушит: якорь на размер это поймает
            }
        }
        return known;
    }

    private static void tryClass(String fqn, Set<String> known) {
        try {
            known.add(Class.forName(fqn, false, CommentReferenceResolvesTest.class.getClassLoader())
                    .getSimpleName());
        } catch (Throwable notThere) {
            // такого типа нет — словарь просто не пополняется
        }
    }

    private static boolean allowedAt(Candidate c) {
        return ALLOWED.stream().anyMatch(a -> a.token().equals(c.token())
                && c.where().startsWith(a.file() + ":"));
    }

    /** Нарушители одной строки — для вложенных проверок «ловит» / «не ловит». */
    private static List<String> unresolvedIn(String line) {
        try {
            if (!MARKER.matcher(line).find()) {
                return List.of();
            }
            Set<String> known = knownTokens();
            return tokens(line).stream().filter(t -> !known.contains(t)).toList();
        } catch (IOException unreadable) {
            throw new AssertionError("не прочитать исходники для словаря", unreadable);
        }
    }

    /**
     * Код без комментариев И без строковых литералов — только имена.
     * <p>
     * ⚠️ Литералы снимать обязательно: синтетическое имя из фикстуры этого же теста лежит
     * в строке, попадает в словарь и делает гейт слепым к самому себе. Держит {@code Catches}.
     */
    private static String stripComments(String src) {
        StringBuilder out = new StringBuilder();
        int state = 0;
        for (int i = 0; i < src.length(); i++) {
            char c = src.charAt(i);
            char next = i + 1 < src.length() ? src.charAt(i + 1) : '\0';
            switch (state) {
                case 0 -> {
                    if (c == '/' && next == '/') {
                        state = 1;
                        i++;
                    } else if (c == '/' && next == '*') {
                        state = 2;
                        i++;
                    } else if (c == '"') {
                        state = 3;                 // литерал — данные, а не имя
                    } else {
                        out.append(c);
                    }
                }
                case 3 -> {
                    if (c == '\\') {
                        i++;                       // экранированная кавычка литерал не закрывает
                    } else if (c == '"') {
                        state = 0;
                    }
                }
                case 1 -> {
                    if (c == '\n') {
                        state = 0;
                        out.append(c);
                    }
                }
                default -> {
                    if (c == '*' && next == '/') {
                        state = 0;
                        i++;
                    }
                }
            }
        }
        return out.toString();
    }
}
