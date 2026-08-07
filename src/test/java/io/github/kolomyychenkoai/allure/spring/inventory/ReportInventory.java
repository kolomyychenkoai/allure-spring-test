package io.github.kolomyychenkoai.allure.spring.inventory;

import io.qameta.allure.Epic;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Инвентарь ВИДОВ шагов и вложений: скан {@code allure-results}, чтение/запись эталона, вердикт.
 * Вся логика ПРИНЯТИЯ РЕШЕНИЯ — чистые функции ({@link #verdict}, {@link #updateVerdict}),
 * тест {@link ReportInventoryCheck} остаётся тонким шеллом «посчитать → показать → упасть».
 * Так гейты детектора сами покрыты тестами: сломанный гейт = вечно-зелёный детектор,
 * худший из возможных исходов.
 * <p>
 * <b>Вид = пара «тест-класс | шаблон».</b> Одного имени шага мало: {@code HTTP GET … → 200}
 * пишут пять разных модулей, и по имени не понять, КТО отвалился. Каждый демо-{@code *IT} —
 * витрина ровно одного модуля.
 * <p>
 * <b>Вложенность — часть вида.</b> Шаг записывается как {@code родитель ▸ шаг} (один уровень
 * вверх). Без этого регрессия «SQL перестал вкладываться в шаг репозитория и вылез наверх»
 * даёт ТОТ ЖЕ набор видов — а для ручной приёмки это заметная деградация отчёта.
 * <p>
 * <b>Сканируем только пакет {@code demo}.</b> Юнит-тесты дают шаги НЕДЕТЕРМИНИРОВАННО: перехват
 * мог встать от ранее прошедшего Spring-теста, а порядок классов случайный.
 */
@Epic("Внутренние проверки библиотеки")
public final class ReportInventory {

    /** Эталон лежит вне resources: одна копия, читается по пути от корня проекта. */
    public static final Path BASELINE = Path.of("src/test/inventory/report-inventory.txt");

    /** Владелец «*» — вид, чья привязка к классу плавает (кэш контекстов, порядок тестов). */
    public static final String ANY_OWNER = "*";

    /** Разделитель «родитель ▸ вложенный шаг». */
    public static final String NESTED = " ▸ ";

    private static final String DEMO_PACKAGE = ".demo.";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** Комментарий-подпись отделяется ДВУМЯ пробелами перед «#» — «#» внутри имени шага не мешает. */
    private static final Pattern COMMENT = Pattern.compile("\\s{2,}#");
    /**
     * Маркер ожидаемой кратности в конце строки эталона: «  ×1», «  ×≥2».
     * Два пробела обязательны: «×» встречается ВНУТРИ имён видов («(ожидали ×<N>)»).
     */
    private static final Pattern COUNT_MARKER = Pattern.compile("\\s{2,}×(≥)?(\\d+)\\s*$");
    /** Маркер статусного вида: « [BROKEN]», « [FAILED]», « [SKIPPED]». */
    private static final Pattern STATUS_MARKER = Pattern.compile(" \\[[A-Z]+]$");
    /**
     * Маркер формы тела вложения в конце строки эталона: «  ¶1», «  ¶N».
     * Пишется ПОСЛЕ маркера кратности, поэтому при чтении снимается ПЕРВЫМ.
     */
    private static final Pattern SHAPE_MARKER = Pattern.compile("\\s{2,}¶(1|N)\\s*$");

    private ReportInventory() {
    }

    /** Один вид: класс-владелец + шаблон. */
    public record Kind(String owner, String text) implements Comparable<Kind> {
        @Override
        public int compareTo(Kind other) {
            int byOwner = owner.compareTo(other.owner);
            return byOwner != 0 ? byOwner : text.compareTo(other.text);
        }

        @Override
        public String toString() {
            return owner + " | " + text;
        }
    }

    /** Что реально произвёл прогон. */
    public record Scan(Set<Kind> steps, Set<Kind> attachments, Set<String> owners, int resultFiles,
                       List<String> unreadable, List<String> missingFiles, List<String> dirtyNames,
                       Map<Kind, Range> perCase, List<String> dirtyBodies,
                       Map<Kind, ShapeStat> shapes) {

        /** Без тел вложений — для тестов вердикта, которым содержимое не нужно. */
        public Scan(Set<Kind> steps, Set<Kind> attachments, Set<String> owners, int resultFiles,
                    List<String> unreadable, List<String> missingFiles, List<String> dirtyNames,
                    Map<Kind, Range> perCase) {
            this(steps, attachments, owners, resultFiles, unreadable, missingFiles, dirtyNames,
                    perCase, List.of(), Map.of());
        }
    }

    /** Состояние содержимого вложения. */
    public enum Content {
        /** Файл на месте и непустой (либо источник не указан — судить не о чем). */
        PRESENT,
        /** Файл на месте, но пустой — деградация «пишем, а нечего показать». */
        EMPTY,
        /** Файла НЕТ: вложение объявлено, байты не записаны. Отчёт сломан, а не «стал другим». */
        MISSING
    }

    /** Ожидаемая кратность вида: «ровно N в каждом кейсе» либо «не меньше N». */
    public record Count(int value, boolean atLeast) {
        @Override
        public String toString() {
            return "×" + (atLeast ? "≥" : "") + value;
        }
    }

    /** Сколько раз вид встречался в одном тест-кейсе: минимум и максимум по кейсам. */
    public record Range(int min, int max, int cases) {
        public Range(int min, int max) {
            this(min, max, 1);
        }

        Range with(Range other) {
            return new Range(Math.min(min, other.min()), Math.max(max, other.max()), cases + other.cases());
        }

        @Override
        public String toString() {
            return min == max ? String.valueOf(min) : min + ".." + max;
        }
    }

    /** Кратность разъехалась: ждали одно, увидели другое. */
    public record CountMismatch(Kind kind, Count expected, Range seen) {
    }

    /**
     * Форма ТЕЛА вложения: одной строкой или многострочное.
     * <p>
     * Зачем отдельное измерение: вид вложения знает только «пусто/непусто», поэтому «тело
     * схлопнулось в строку» или «от него осталась одна строка после обрезки» — деградация,
     * которую сетка иначе не видит. Именно так прошёл near-miss WireMock (колоночный диф стал
     * одной строкой): имя, mime и непустота не изменились.
     * <p>
     * В сам ВИД форму класть нельзя — замер показал, что у 7 видов из 87 она законно плавает
     * (например «DB Call» с одним аргументом против нескольких), и такие виды раздвоились бы.
     * Поэтому форма — МАРКЕР в эталоне по замеру, ровно как кратность {@link Count}.
     * <p>
     * Критерий пометки — ТРИ условия сразу (см. {@link #seedShapes}): форма одинакова во всех
     * наблюдениях, наблюдений не меньше {@link #MIN_OBSERVATIONS}, и форма — многострочная.
     * Каждое добавлено по замеру, после ложного падения; «стабильной формы» одной НЕ достаточно.
     * Виды, не прошедшие все три, остаются без маркера и формой не стерегутся.
     */
    public enum Shape {
        ONE_LINE("¶1"), MULTILINE("¶N");

        private final String marker;

        Shape(String marker) {
            this.marker = marker;
        }

        @Override
        public String toString() {
            return marker;
        }

        static Shape of(String marker) {
            return ONE_LINE.marker.equals(marker) ? ONE_LINE : MULTILINE;
        }
    }

    /** Форма тела разъехалась: ждали одну, увидели другую (или сразу обе). */
    public record ShapeMismatch(Kind kind, Shape expected, Set<Shape> seen) {
    }

    /**
     * Наблюдения формы по одному виду: какие формы встретились и СКОЛЬКО РАЗ всего.
     * <p>
     * Число наблюдений нужно посеву: у вида, встреченного за прогон ОДИН раз, «форма стабильна» —
     * тавтология. Замерено на живых данных: такой посев пометил {@code Application Logs} как
     * однострочный, а на следующем прогоне лог оказался длиннее — гейт покраснел на ровном месте.
     * Поэтому маркер получают только виды с {@link #MIN_OBSERVATIONS}+ наблюдениями.
     */
    public record ShapeStat(Set<Shape> seen, int observations) {

        ShapeStat with(Shape shape) {
            Set<Shape> merged = EnumSet.copyOf(seen);
            merged.add(shape);
            return new ShapeStat(merged, observations + 1);
        }

        static ShapeStat first(Shape shape) {
            return new ShapeStat(EnumSet.of(shape), 1);
        }
    }

    /**
     * Сколько раз надо увидеть вид, чтобы поверить в стабильность его формы.
     * <p>
     * Для КРАТНОСТИ такое требование осознанно отвергнуто (там оно превратило бы почти всё
     * в «×≥1», а это не ловит задвоение — ради чего кратность и заведена). У формы этого размена
     * нет: ослабленного варианта не существует, вид просто остаётся без маркера.
     * <p>
     * Вместе со вторым условием ({@link #seedShapes} помечает только МНОГОСТРОЧНЫЕ) цена такая:
     * 35 помеченных видов вложений из 87. Меньше половины — зато без ложных срабатываний,
     * проверено двенадцатью прогонами подряд.
     */
    private static final int MIN_OBSERVATIONS = 2;

    /** Что записано в эталоне. */
    public record Baseline(Set<Kind> steps, Set<Kind> attachments, Set<Kind> optional,
                           Map<Kind, String> comments, Map<Kind, Count> counts,
                           Map<Kind, Shape> shapes) {

        public Baseline(Set<Kind> steps, Set<Kind> attachments, Set<Kind> optional, Map<Kind, String> comments) {
            this(steps, attachments, optional, comments, Map.of(), Map.of());
        }

        public Baseline(Set<Kind> steps, Set<Kind> attachments, Set<Kind> optional,
                        Map<Kind, String> comments, Map<Kind, Count> counts) {
            this(steps, attachments, optional, comments, counts, Map.of());
        }

        public Set<String> owners() {
            Set<String> owners = new TreeSet<>();
            Stream.concat(steps.stream(), attachments.stream())
                    .map(Kind::owner)
                    .filter(owner -> !ANY_OWNER.equals(owner))
                    .forEach(owners::add);
            return owners;
        }
    }

    /**
     * Вердикт сверки. {@code noData} — прогон не дал данных (проверка НЕДЕЙСТВИТЕЛЬНА, а не
     * успешна); {@code silentOwners} — класс-витрина потерял ВСЕ свои виды («пропал тест», не
     * «отвалилась инструментация»); {@code unknownOwners} — появился класс-витрина, которого нет
     * в эталоне (модуль вне сетки — его никто не стережёт).
     */
    public record Verdict(boolean noData, List<Kind> missingSteps, List<Kind> missingAttachments,
                          List<Kind> extraSteps, List<Kind> extraAttachments,
                          List<String> silentOwners, List<String> unknownOwners,
                          List<CountMismatch> countMismatches, List<ShapeMismatch> shapeMismatches) {

        public Verdict(boolean noData, List<Kind> missingSteps, List<Kind> missingAttachments,
                       List<Kind> extraSteps, List<Kind> extraAttachments,
                       List<String> silentOwners, List<String> unknownOwners) {
            this(noData, missingSteps, missingAttachments, extraSteps, extraAttachments,
                    silentOwners, unknownOwners, List.of(), List.of());
        }

        public Verdict(boolean noData, List<Kind> missingSteps, List<Kind> missingAttachments,
                       List<Kind> extraSteps, List<Kind> extraAttachments,
                       List<String> silentOwners, List<String> unknownOwners,
                       List<CountMismatch> countMismatches) {
            this(noData, missingSteps, missingAttachments, extraSteps, extraAttachments,
                    silentOwners, unknownOwners, countMismatches, List.of());
        }


        /** Появился НОВЫЙ вид со статусом — библиотека начала фабриковать сбои там, где их не было. */
        public boolean newFailures() {
            return Stream.concat(extraSteps.stream(), extraAttachments.stream())
                    .anyMatch(kind -> STATUS_MARKER.matcher(kind.text()).find());
        }

        public boolean failed(boolean strict) {
            // silentOwners — самостоятельная причина падения, а не «следствие пропавших видов»:
            // если ВСЕ виды класса помечены «?» (а «?» ставят как раз против флаки), класс мог бы
            // исчезнуть целиком при зелёной сборке.
            return noData || !missingSteps.isEmpty() || !missingAttachments.isEmpty()
                    || !unknownOwners.isEmpty() || !silentOwners.isEmpty()
                    // новый ШАГ — это обогащение отчёта (норма), новый НЕ-PASSED статус — поломка:
                    // правило библиотеки «упавшую проверку шагом не логируем» перестало соблюдаться
                    || newFailures() || !countMismatches.isEmpty() || !shapeMismatches.isEmpty()
                    || (strict && (!extraSteps.isEmpty() || !extraAttachments.isEmpty()));
        }

        public int missingCount() {
            return missingSteps.size() + missingAttachments.size();
        }

        public List<Kind> extras() {
            List<Kind> all = new ArrayList<>(extraSteps);
            all.addAll(extraAttachments);
            return all;
        }
    }

    /** Можно ли обновлять эталон этим прогоном и удаляет ли обновление виды. */
    public record UpdateVerdict(List<String> lostOwners, List<Kind> removed) {
        public boolean partialRun() {
            return !lostOwners.isEmpty();
        }
    }

    // ─────────────────────────── скан результатов ───────────────────────────

    public static Scan scan(Path resultsDir) {
        Set<Kind> steps = new TreeSet<>();
        Set<Kind> attachments = new TreeSet<>();
        Set<String> owners = new TreeSet<>();
        int files = 0;
        if (!Files.isDirectory(resultsDir)) {
            return new Scan(steps, attachments, owners, 0, List.of(), List.of(), List.of(), Map.of());
        }
        List<String> unreadable = new ArrayList<>();
        List<String> missingFiles = new ArrayList<>();
        List<String> dirtyNames = new ArrayList<>();
        List<String> dirtyBodies = new ArrayList<>();
        Map<Kind, Range> perCase = new TreeMap<>();
        Map<Kind, ShapeStat> shapes = new TreeMap<>();
        try (Stream<Path> list = Files.list(resultsDir)) {
            List<Path> results = list.filter(p -> p.getFileName().toString().endsWith("-result.json")).toList();
            for (Path file : results) {
                JsonNode root = readTree(file, unreadable);
                if (root == null) {
                    continue;
                }
                String testClass = label(root, "testClass");
                if (testClass == null || !testClass.contains(DEMO_PACKAGE)) {
                    continue;
                }
                files++;
                String owner = testClass.substring(testClass.lastIndexOf('.') + 1);
                owners.add(owner);
                // Кратность считаем ЗА ТЕСТ-КЕЙС, а не за класс: иначе добавление @Test двигало бы
                // эталон по причине, не связанной с отчётом. Инвариант библиотеки — «один вызов =
                // один шаг», и он про кейс.
                Map<Kind, Integer> local = new HashMap<>();
                collectAttachments(root, owner, attachments, resultsDir, missingFiles, dirtyNames,
                        dirtyBodies, shapes, local);
                collectSteps(root, owner, null, steps, attachments, resultsDir, missingFiles, dirtyNames,
                        dirtyBodies, shapes, local);
                local.forEach((kind, n) -> perCase.merge(kind, new Range(n, n), Range::with));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return new Scan(steps, attachments, owners, files, unreadable, missingFiles, dirtyNames, perCase,
                dirtyBodies, shapes);
    }

    /** {@code parent} — шаблон НЕПОСРЕДСТВЕННОГО родителя (null для верхнего уровня). */
    private static void collectSteps(JsonNode node, String owner, String parent, Set<Kind> steps,
                                     Set<Kind> attachments, Path resultsDir,
                                     List<String> missingFiles, List<String> dirtyNames,
                                     List<String> dirtyBodies, Map<Kind, ShapeStat> shapes,
                                     Map<Kind, Integer> local) {
        for (JsonNode step : node.path("steps")) {
            String raw = step.path("name").asText("");
            hygiene(owner, raw, dirtyNames);
            String template = StepTemplates.step(raw);
            // Родительский префикс берём БЕЗ статус-суффикса: иначе один сломавшийся родитель
            // переименовал бы все дочерние виды и дал каскад «пропало+появилось» вместо диагноза.
            Kind kind = new Kind(owner,
                    (parent == null ? template : parent + NESTED + template) + statusSuffix(step));
            steps.add(kind);
            local.merge(kind, 1, Integer::sum);
            collectAttachments(step, owner, attachments, resultsDir, missingFiles, dirtyNames,
                    dirtyBodies, shapes, local);
            collectSteps(step, owner, template, steps, attachments, resultsDir, missingFiles, dirtyNames,
                    dirtyBodies, shapes, local);
        }
    }

    /**
     * Суффикс статуса — ТОЛЬКО для не-passed. Сегодня это ровно один вид на весь отчёт
     * (BROKEN у ошибки репозитория), то есть эталон не раздувается, а регрессия
     * «BROKEN стал PASSED» становится видимой: вид просто исчезает.
     */
    static String statusSuffix(JsonNode step) {
        String status = step.path("status").asText("passed");
        return status.isBlank() || "passed".equals(status) ? "" : " [" + status.toUpperCase(Locale.ROOT) + "]";
    }

    /** Гигиена по СЫРОМУ имени: нормализация маскирует хэши, то есть прячет ровно этот мусор. */
    private static void hygiene(String owner, String rawName, List<String> dirtyNames) {
        StepNameHygiene.defect(rawName)
                .ifPresent(diagnosis -> dirtyNames.add(owner + " | " + rawName + "  ← " + diagnosis));
    }

    /** Сколько байт тела читаем ради гигиены: мусор рождается в рендере значения и виден сразу. */
    private static final int BODY_PROBE = 64 * 1024;

    /**
     * Те же правила гигиены, но по ТЕЛУ вложения.
     * <p>
     * Тело нужно стеречь отдельно от имени, хотя мусор рождается в одной точке
     * ({@code AllureAdviceSupport}) и течёт в оба места: вид вложения знает только
     * «пусто/непусто», поэтому {@code [B@4a3f} вместо аргумента, {@code HibernateProxy} вместо
     * сущности или {@code $$Lambda} в «Mock Call» проходят мимо сетки — имя, mime и непустота
     * на месте. Типовой исход апгрейда: матчер или имя поля уехали, и рендер выдал внутренности.
     * <p>
     * Нарушителей ноль по всем телам прогона — гейт держится «в зелёном», как и гейт по именам.
     * Появился мусор — чинить рендер в {@code src/main}.
     * <p>
     * <b>Доказан сквозной мутацией.</b> Снимаешь чистку в {@code AllureAdviceSupport.safeValue} —
     * гейт краснеет на «{@code AllureMockitoReportIT | Mock Call → toString массива вместо
     * поэлементного вывода}».
     * <p>
     * ⚠️ Держится это на ОДНОМ элементе витрины: {@code Pricing.bulk(byte[])} в
     * {@code demo/AllureMockitoReportIT} заведён специально, чтобы во вложение попадало значение,
     * которое без чистки станет мусором. Уберёшь его — гейт снова станет форвардным (логика
     * останется под юнит-тестами, но сквозняком доказать будет нечем). Мутацию гонять с
     * {@code -Dmaven.test.failure.ignore=true}: иначе её первым ловит сам витринный тест.
     * <p>
     * Только текстовые вложения: гонять регулярки по декодированным байтам картинки или архива
     * значит выдумывать нарушения на ровном месте.
     */
    private static void bodyHygiene(String owner, String name, Body body, List<String> dirtyBodies) {
        if (body == null) {
            return;
        }
        StepNameHygiene.bodyDefect(body.text()).ifPresent(diagnosis ->
                dirtyBodies.add(owner + " | " + name + " → " + diagnosis + "\n      …"
                        + excerpt(body.text()) + "…"));
    }

    /** Прочитанное начало тела вложения. {@code truncated} — файл длиннее пробы. */
    private record Body(String text, boolean truncated) {
    }

    /**
     * Начало тела текстового вложения — один раз на вложение, для гигиены И для формы.
     * {@code null}, если анализировать нечего (нет источника, не текст, файла нет, не читается).
     */
    private static Body body(String type, Path resultsDir, String source) {
        if (source == null || source.isBlank() || !isText(type)) {
            return null;
        }
        Path file = resultsDir.resolve(source);
        if (!Files.isRegularFile(file)) {
            return null; // «файла нет» — отдельный гейт missingFiles, второй раз не диагностируем
        }
        try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            char[] probe = new char[BODY_PROBE];
            int read = reader.read(probe);
            return new Body(read <= 0 ? "" : new String(probe, 0, read), read == BODY_PROBE);
        } catch (IOException | RuntimeException unreadable) {
            return null; // нечитаемое тело — не наша тема, о ней скажет content()/unreadable
        }
    }

    /**
     * Форма тела: одной строкой или многострочное. Пусто — формы нет (это уже отдельное
     * измерение вида). Проба обрезана и переноса в ней не нашлось — тоже нет: судить о форме
     * по началу файла значит выдумывать, а выдуманный маркер краснел бы на ровном месте.
     */
    static Optional<Shape> shapeOf(Body body) {
        if (body == null) {
            return Optional.empty();
        }
        String text = body.text().strip();
        if (text.isEmpty()) {
            return Optional.empty();
        }
        if (text.contains("\n")) {
            return Optional.of(Shape.MULTILINE);
        }
        return body.truncated() ? Optional.empty() : Optional.of(Shape.ONE_LINE);
    }

    /** Текстовое ли вложение (регулярки по картинке дали бы выдуманные нарушения). */
    private static boolean isText(String type) {
        return type != null && (type.startsWith("text/") || type.contains("json") || type.contains("xml"));
    }

    /** Кусок тела вокруг первого нарушения — чтобы не листать вложение руками. */
    private static String excerpt(String body) {
        String flat = body.replace('\n', '⏎');
        int at = StepNameHygiene.bodyDefectAt(flat);
        int from = Math.max(0, at - 50);
        return flat.substring(from, Math.min(flat.length(), from + 120));
    }

    private static void collectAttachments(JsonNode node, String owner, Set<Kind> attachments, Path resultsDir,
                                           List<String> missingFiles, List<String> dirtyNames,
                                           List<String> dirtyBodies, Map<Kind, ShapeStat> shapes,
                                     Map<Kind, Integer> local) {
        for (JsonNode att : node.path("attachments")) {
            String name = att.path("name").asText("");
            hygiene(owner, name, dirtyNames);
            String source = att.path("source").asText(null);
            Content content = content(resultsDir, source);
            Body body = body(att.path("type").asText(""), resultsDir, source);
            bodyHygiene(owner, name, body, dirtyBodies);
            if (content == Content.MISSING) {
                // НЕ вид: «вложение объявлено, а байтов нет» — это не «отчёт стал другим», а
                // «отчёт сломан», и лечится оно не обновлением эталона. Отдельный безусловный гейт.
                missingFiles.add(owner + " | " + name + " → нет файла " + source);
            }
            Kind kind = new Kind(owner, StepTemplates.attachment(
                    name, att.path("type").asText(""), content != Content.EMPTY));
            attachments.add(kind);
            local.merge(kind, 1, Integer::sum);
            // Копим МНОЖЕСТВО наблюдённых форм: маркер получит только вид, у которого оно
            // из одного элемента. Так белый список берётся из факта, а не из предположения.
            shapeOf(body).ifPresent(shape -> shapes.merge(kind, ShapeStat.first(shape),
                    (was, fresh) -> was.with(shape)));
        }
    }

    /**
     * Состояние содержимого вложения. Признак «пусто» входит в ВИД: типичная тихая деградация —
     * «вложение пишется, но пустое» (напр. отвалился разбор полей сущности) — иначе прошла бы мимо,
     * ведь имя и mime на месте.
     * <p>
     * ОТСУТСТВИЕ файла-источника — отдельное состояние, а не «содержимое есть»: иначе самая
     * тяжёлая деградация выглядела бы здоровой. Инвентарь гоняется ВТОРЫМ прогоном, когда всё
     * уже сброшено на диск, так что «файла нет» — однозначный дефект, а не гонка.
     */
    static Content content(Path resultsDir, String source) {
        if (source == null || source.isBlank()) {
            return Content.PRESENT; // источник не указан — судить не о чем, не выдумываем деградацию
        }
        Path file = resultsDir.resolve(source);
        if (!Files.isRegularFile(file)) {
            return Content.MISSING;
        }
        try {
            return Files.size(file) > 0 ? Content.PRESENT : Content.EMPTY;
        } catch (IOException unreadable) {
            return Content.MISSING;
        }
    }

    private static JsonNode readTree(Path file, List<String> unreadable) {
        try {
            return MAPPER.readTree(file.toFile());
        } catch (IOException broken) {
            // Диагноз копим и отдаём наружу (печатает вызывающий): молча пропущенный файл выглядел
            // бы как «отвалился модуль», и человек пошёл бы искать поломку инструментации вместо
            // недописанного файла. В stderr отсюда не пишем — юнит-тесты скана не должны сорить
            // в лог основного прогона жалобами на свои временные каталоги.
            unreadable.add(file + " — " + broken);
            return null;
        }
    }

    private static String label(JsonNode root, String name) {
        for (JsonNode l : root.path("labels")) {
            if (name.equals(l.path("name").asText())) {
                return l.path("value").asText(null);
            }
        }
        return null;
    }

    // ─────────────────────────── вердикт (чистые функции) ───────────────────────────

    public static Verdict verdict(Scan scan, Baseline baseline) {
        if (scan.owners().isEmpty()) {
            return new Verdict(true, List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        }
        List<String> silent = baseline.owners().stream()
                .filter(owner -> !scan.owners().contains(owner))
                .toList();
        // Новый класс-витрина = модуль ВНЕ сетки: его виды никто не стережёт, а «новые виды»
        // сами по себе не роняют сборку. Событие разовое (раз в жизни модуля) — шума не даёт.
        List<String> unknown = scan.owners().stream()
                .filter(owner -> !baseline.owners().contains(owner))
                .toList();
        return new Verdict(false,
                missing(baseline.steps(), baseline.optional(), scan.steps()),
                missing(baseline.attachments(), baseline.optional(), scan.attachments()),
                extra(baseline.steps(), scan.steps()),
                extra(baseline.attachments(), scan.attachments()),
                silent, unknown, counts(baseline.counts(), scan.perCase()),
                shapes(baseline.shapes(), scan.shapes()));
    }

    /**
     * Виды, у которых форма тела разъехалась с помеченной в эталоне. Как и кратность, считается
     * ТОЛЬКО для помеченных видов — белый список живёт в данных, а не в коде. Вид, которого прогон
     * не дал, пропускаем: это уже {@code missing}, второй раз тот же факт не диагностируем.
     */
    public static List<ShapeMismatch> shapes(Map<Kind, Shape> expected, Map<Kind, ShapeStat> seen) {
        List<ShapeMismatch> mismatches = new ArrayList<>();
        expected.forEach((kind, shape) -> {
            ShapeStat stat = seen.get(kind);
            if (stat == null || stat.seen().isEmpty()) {
                return;
            }
            Set<Shape> observed = stat.seen();
            // Форма обязана совпасть в КАЖДОМ наблюдении: «иногда одной строкой» — это уже
            // не стабильный вид, и маркер на нём стоять не должен.
            if (observed.size() != 1 || !observed.contains(shape)) {
                mismatches.add(new ShapeMismatch(kind, shape, observed));
            }
        });
        return mismatches;
    }

    /**
     * Что произойдёт при обновлении эталона этим прогоном.
     * <p>
     * «Что БУДЕТ удалено» считается ровно тем же {@link #merge}, которым потом пишется файл:
     * два независимых вычисления одного факта — это дублирование не кода, а ИСТИНЫ, и их
     * рассинхрон тих. Иначе {@code ?}-вид, не пришедший в этом прогоне, попадал бы в «удаляемые»,
     * хотя {@code write} его сохраняет — флаг требовался бы ради удаления, которого не будет.
     */
    public static UpdateVerdict updateVerdict(Scan scan, Baseline baseline) {
        List<String> lost = baseline.owners().stream()
                .filter(owner -> !scan.owners().contains(owner))
                .toList();
        Set<Kind> keptSteps = merge(scan.steps(), baseline.steps(), baseline);
        Set<Kind> keptAttachments = merge(scan.attachments(), baseline.attachments(), baseline);
        List<Kind> removed = new ArrayList<>();
        baseline.steps().stream().filter(kind -> !keptSteps.contains(kind)).forEach(removed::add);
        baseline.attachments().stream().filter(kind -> !keptAttachments.contains(kind)).forEach(removed::add);
        return new UpdateVerdict(lost, removed);
    }

    /**
     * Виды, чья кратность разъехалась с ожидаемой. Считается только для видов, ПОМЕЧЕННЫХ в эталоне
     * (маркер «×N»): белый список живёт в данных, а не в коде. Виды, которых прогон вовсе не дал,
     * тут пропускаем — это уже {@code missing}, второй раз тот же факт не диагностируем.
     * <p>
     * Семантика по умолчанию — «ровно N в каждом кейсе, ГДЕ ВИД ВСТРЕТИЛСЯ»: главный класс
     * регрессии при апгрейде — ЗАДВОЕНИЕ (сломался граф само-делегации, отвалился CAS-гард),
     * а «не меньше N» его не видит.
     * <p>
     * ⚠️ Именно «где встретился», а не «в каждом»: {@link Scan#perCase} копит наблюдения только
     * по тем кейсам, где вид был, — нуля там не бывает. Значит регрессия «шаг пропал в ЧАСТИ
     * кейсов» не ловится ни здесь, ни в {@link #missing} (вид-то остался). Это осознанная
     * граница: считать «в каждом» пришлось бы знать, в каких кейсах вид ОБЯЗАН быть, а витрины
     * ветвятся. Записана в docs/acceptance-report-standard.md.
     */
    public static List<CountMismatch> counts(Map<Kind, Count> expected, Map<Kind, Range> seen) {
        List<CountMismatch> mismatches = new ArrayList<>();
        expected.forEach((kind, count) -> {
            Range range = seen.get(kind);
            if (range == null) {
                return;
            }
            boolean ok = count.atLeast()
                    ? range.min() >= count.value()
                    : range.min() == count.value() && range.max() == count.value();
            if (!ok) {
                mismatches.add(new CountMismatch(kind, count, range));
            }
        });
        return mismatches;
    }

    /** Виды из эталона, которых прогон не дал. С учётом «*» (где угодно) и «?» (необязательные). */
    public static List<Kind> missing(Set<Kind> baseline, Set<Kind> optional, Set<Kind> seen) {
        Set<String> seenAnywhere = new TreeSet<>();
        seen.forEach(k -> seenAnywhere.add(k.text()));
        List<Kind> gone = new ArrayList<>();
        for (Kind kind : baseline) {
            if (optional.contains(kind)) {
                continue;
            }
            boolean present = ANY_OWNER.equals(kind.owner())
                    ? seenAnywhere.contains(kind.text())
                    : seen.contains(kind);
            if (!present) {
                gone.add(kind);
            }
        }
        return gone;
    }

    /** Виды, которых в эталоне нет (новые). Сами по себе не падение — печатаются отдельно. */
    public static List<Kind> extra(Set<Kind> baseline, Set<Kind> seen) {
        Set<String> anywhere = new TreeSet<>();
        baseline.stream().filter(k -> ANY_OWNER.equals(k.owner())).forEach(k -> anywhere.add(k.text()));
        List<Kind> fresh = new ArrayList<>();
        for (Kind kind : seen) {
            if (!baseline.contains(kind) && !anywhere.contains(kind.text())) {
                fresh.add(kind);
            }
        }
        return fresh;
    }

    /**
     * Спец-диагноз: вложение с таким именем ЕСТЬ, но с другим mime/содержимым. Иначе человек
     * будет искать «исчезнувшее» вложение, которого не исчезало (типичная деградация притти-JSON).
     */
    public static Map<Kind, String> mimeDrift(List<Kind> missingAttachments, Scan scan) {
        Map<Kind, String> drift = new LinkedHashMap<>();
        for (Kind gone : missingAttachments) {
            int cut = gone.text().lastIndexOf(" | ");
            if (cut < 0) {
                continue; // не вид вложения — сравнивать не с чем
            }
            String namePart = gone.text().substring(0, cut + 3);
            scan.attachments().stream()
                    .filter(seen -> seen.owner().equals(gone.owner()) || ANY_OWNER.equals(gone.owner()))
                    .filter(seen -> seen.text().startsWith(namePart))
                    .findFirst()
                    .ifPresent(seen -> drift.put(gone, seen.text().substring(namePart.length())));
        }
        return drift;
    }

    // ─────────────────────────── эталон: чтение ───────────────────────────

    public static Baseline readBaseline(Path path) {
        Set<Kind> steps = new TreeSet<>();
        Set<Kind> attachments = new TreeSet<>();
        Set<Kind> optional = new TreeSet<>();
        Map<Kind, String> comments = new TreeMap<>();
        Map<Kind, Count> counts = new TreeMap<>();
        Map<Kind, Shape> shapes = new TreeMap<>();
        if (!Files.isRegularFile(path)) {
            return new Baseline(steps, attachments, optional, comments, counts, shapes);
        }
        String section = "steps";
        for (String raw : readLines(path)) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            if (line.startsWith("[") && line.endsWith("]")) {
                section = line.substring(1, line.length() - 1);
                continue;
            }
            boolean isOptional = line.startsWith("? ");
            if (isOptional) {
                line = line.substring(2).strip();
            }
            String comment = null;
            Matcher commentAt = COMMENT.matcher(line);
            if (commentAt.find()) {
                comment = line.substring(commentAt.start()).replaceFirst("^\\s*#\\s*", "");
                line = line.substring(0, commentAt.start()).strip();
            }
            // Порядок снятия ОБРАТЕН порядку записи: сначала форма (она пишется последней
            // перед комментарием), потом кратность.
            Shape shape = null;
            Matcher shapeAt = SHAPE_MARKER.matcher(line);
            if (shapeAt.find()) {
                shape = Shape.of("¶" + shapeAt.group(1));
                line = line.substring(0, shapeAt.start()).strip();
            }
            Count count = null;
            Matcher countAt = COUNT_MARKER.matcher(line);
            if (countAt.find()) {
                count = new Count(Integer.parseInt(countAt.group(2)), countAt.group(1) != null);
                line = line.substring(0, countAt.start()).strip();
            }
            int sep = line.indexOf(" | ");
            if (sep < 0) {
                continue;
            }
            Kind kind = new Kind(line.substring(0, sep).strip(), line.substring(sep + 3).strip());
            if (count != null) {
                counts.put(kind, count);
            }
            if (shape != null) {
                shapes.put(kind, shape);
            }
            ("attachments".equals(section) ? attachments : steps).add(kind);
            if (isOptional) {
                optional.add(kind);
            }
            if (comment != null && !comment.isBlank()) {
                comments.put(kind, comment);
            }
        }
        return new Baseline(steps, attachments, optional, comments, counts, shapes);
    }

    private static List<String> readLines(Path path) {
        try {
            return Files.readAllLines(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ─────────────────────────── эталон: запись ───────────────────────────

    /**
     * Перезаписать эталон по результатам прогона.
     * <p>
     * Ручная работа НЕ теряется: подписи-комментарии переносятся, а виды с владельцем {@code *}
     * и необязательные {@code ?} СОХРАНЯЮТСЯ, даже если прогон их не дал или дал с конкретным
     * владельцем — иначе первое же обновление тихо сняло бы флако-толерантность, ради которой
     * их и заводили.
     */
    public static void write(Path path, Scan scan, Baseline previous) {
        StringBuilder out = new StringBuilder();
        out.append("""
                # Инвентарь ВИДОВ шагов и вложений Allure-отчёта — эталон.
                #
                # Зачем: библиотека ломается ТИХО (перехват отвалился → шаг исчез → тесты зелёные).
                # Этот файл фиксирует, что отчёт УМЕЛ показывать. Пропажа вида = красный
                # ReportInventoryCheck с именем модуля.
                #
                # Обновление — ОСОЗНАННОЕ действие:  mvn clean test -Dinventory.update=true
                # (файл перезапишется, прогон всё равно упадёт — проверь git diff и закоммить).
                # УДАЛЕНИЕ видов требует отдельного -Dinventory.remove=true: чтобы красную сборку
                # нельзя было «починить» обновлением эталона, не заметив, что сетка распустилась.
                #
                # Формат:  <ТестКласс> | <шаблон>  # <кто отвечает>
                #   «▸» — вложенность шага в родительский (регрессия «шаг вылез наверх» видна).
                #   «*» вместо класса — вид, чей класс-владелец плавает (кэш контекстов).
                #   «?» в начале строки — вид необязательный (может не появиться в прогоне).
                #   «| пусто» у вложения — содержимое пустое (тоже вид: деградация видна).
                """);
        appendSection(out, "steps", merge(scan.steps(), previous.steps(), previous), previous);
        appendSection(out, "attachments", merge(scan.attachments(), previous.attachments(), previous), previous);
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, out.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Разовый посев маркеров кратности ПО ЗАМЕРУ: помечаем «×N» только те виды, чья кратность
     * ОДИНАКОВА во всех кейсах прогона ({@code min == max}). Виды с плавающим числом (их решает
     * Hibernate/Liquibase/сеть) остаются без маркера — и, значит, кратностью не стерегутся.
     * <p>
     * Так белый список получается из ФАКТА, а не из предположения «наверное, один раз».
     */
    public static Map<Kind, Count> seedCounts(Scan scan, Baseline previous) {
        Map<Kind, Count> counts = new TreeMap<>(previous.counts());
        scan.perCase().forEach((kind, range) -> {
            if (ANY_OWNER.equals(kind.owner())) {
                // Маркеры на «*»-видах НЕ ставим. Технически counts/shapes ищут наблюдения по
                // ТОЧНОМУ Kind, включая владельца, а скан ключует конкретным классом — для «*»
                // поиск всегда промахнулся бы, и маркер молча не проверялся. Смыслово: «*» ставят
                // там, где привязка вида к классу ПЛАВАЕТ, и требовать жёсткую кратность или форму
                // от такого вида нечего. Мёртвых маркеров не заводим вовсе.
                return;
            }
            // Разброс МЕЖДУ кейсами → «×≥min»: число решает чужая библиотека (сколько SELECT сделает
            // Hibernate), жёстко пиннить нечего. Одинаковое число → «×N»: именно оно ловит ЗАДВОЕНИЕ,
            // ради которого кратность и заведена.
            //
            // Осознанный размен: у вида, встреченного в ОДНОМ кейсе, «min==max» — тавтология,
            // и напрашивается требовать два наблюдения. Но таких видов большинство, и это
            // превратило бы почти всё в «×≥1», а «×≥1» задвоение НЕ ловит (2 ≥ 1) — проверено
            // мутацией: со «×≥» снятый дедуп AssertJ проходит зелёным. Цена размена — служебные
            // виды (Liquibase/Hibernate) при апгрейде дадут законное расхождение; лечится
            // пересевом, и об этом сказано в docs/upgrade-checklist.md.
            counts.put(kind, range.min() == range.max()
                    ? new Count(range.min(), false)
                    : new Count(range.min(), true));
        });
        return counts;
    }

    /**
     * Разовый посев маркеров ФОРМЫ по замеру. Помечаются только виды, которые во ВСЕХ
     * наблюдениях прогона (и не меньше {@link #MIN_OBSERVATIONS} раз) были МНОГОСТРОЧНЫМИ.
     * <p>
     * <b>Почему только {@code ¶N}.</b> Стеречь имеет смысл ровно одно направление: «многострочное
     * тело схлопнулось / обрезалось до строки» — это деградация. Обратное («была строка, стало
     * несколько») деградацией не является, зато случается постоянно: у {@code Application Logs}
     * объём вывода зависит от того, поднимался ли в этом классе контекст. Это не рассуждение,
     * а замер: посев в обе стороны дал ложные падения на другом прогоне — дважды, у разных классов.
     * <p>
     * Так белый список получается из ФАКТА и из направления риска, а не из симметрии механизма.
     */
    public static Map<Kind, Shape> seedShapes(Scan scan, Baseline previous) {
        Map<Kind, Shape> shapes = new TreeMap<>(previous.shapes());
        scan.shapes().forEach((kind, stat) -> {
            if (ANY_OWNER.equals(kind.owner())) {
                // Маркеры на «*»-видах НЕ ставим. Технически counts/shapes ищут наблюдения по
                // ТОЧНОМУ Kind, включая владельца, а скан ключует конкретным классом — для «*»
                // поиск всегда промахнулся бы, и маркер молча не проверялся. Смыслово: «*» ставят
                // там, где привязка вида к классу ПЛАВАЕТ, и требовать жёсткую кратность или форму
                // от такого вида нечего. Мёртвых маркеров не заводим вовсе.
                return;
            }
            boolean stable = stat.seen().size() == 1 && stat.observations() >= MIN_OBSERVATIONS;
            if (stable && stat.seen().contains(Shape.MULTILINE)) {
                shapes.put(kind, Shape.MULTILINE);
            } else {
                // форма поплыла, наблюдение одно ЛИБО тело однострочное — стеречь нечего
                shapes.remove(kind);
            }
        });
        return shapes;
    }

    /** К увиденному добавляем «аварийные клапаны» эталона: {@code *}-виды и {@code ?}-виды. */
    private static Set<Kind> merge(Set<Kind> seen, Set<Kind> previous, Baseline baseline) {
        Set<Kind> result = new TreeSet<>(seen);
        Set<String> anyOwnerTexts = new TreeSet<>();
        previous.stream().filter(k -> ANY_OWNER.equals(k.owner())).forEach(k -> {
            result.add(k);
            anyOwnerTexts.add(k.text());
        });
        previous.stream().filter(baseline.optional()::contains).forEach(result::add);
        // конкретный владелец не должен вытеснять «*»-запись того же вида
        result.removeIf(k -> !ANY_OWNER.equals(k.owner()) && anyOwnerTexts.contains(k.text()));
        return result;
    }

    private static void appendSection(StringBuilder out, String name, Set<Kind> kinds, Baseline previous) {
        out.append('\n').append('[').append(name).append(']').append('\n');
        for (Kind kind : kinds) {
            boolean optional = previous.optional().contains(kind);
            String line = (optional ? "? " : "") + kind;
            Count count = previous.counts().get(kind);
            if (count != null) {
                line = line + "  " + count; // маркер переносится: белый список живёт в эталоне
            }
            Shape shape = previous.shapes().get(kind);
            if (shape != null) {
                line = line + "  " + shape; // ПОСЛЕ кратности — чтение снимает в обратном порядке
            }
            String comment = previous.comments().get(kind);
            if (comment != null) {
                line = line + "  # " + comment;
            }
            out.append(line).append('\n');
        }
    }
}
