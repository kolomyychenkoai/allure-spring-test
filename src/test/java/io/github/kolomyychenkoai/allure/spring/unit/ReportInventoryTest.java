package io.github.kolomyychenkoai.allure.spring.unit;

import io.qameta.allure.Epic;
import io.github.kolomyychenkoai.allure.spring.inventory.ReportInventory;
import io.github.kolomyychenkoai.allure.spring.inventory.ReportInventory.Baseline;
import io.github.kolomyychenkoai.allure.spring.inventory.ReportInventory.Kind;
import io.github.kolomyychenkoai.allure.spring.inventory.ReportInventory.Range;
import io.github.kolomyychenkoai.allure.spring.inventory.ReportInventory.Scan;
import io.github.kolomyychenkoai.allure.spring.inventory.ReportInventory.UpdateVerdict;
import io.github.kolomyychenkoai.allure.spring.inventory.ReportInventory.Verdict;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Тесты логики инвентаря отчёта: скан → вердикт → эталон. Сам {@code ReportInventoryCheck}
 * гоняется отдельным прогоном surefire на живых результатах и остаётся тонким шеллом;
 * ЗДЕСЬ покрыты его гейты — потому что сломанный гейт даёт вечно-зелёный детектор,
 * а это хуже, чем отсутствие детектора.
 */
@Epic("Внутренние проверки библиотеки")
class ReportInventoryTest {

    private static Kind kind(String owner, String text) {
        return new Kind(owner, text);
    }

    private static Scan scanOf(Set<Kind> steps, Set<Kind> attachments) {
        Set<String> owners = new TreeSet<>();
        steps.forEach(k -> owners.add(k.owner()));
        attachments.forEach(k -> owners.add(k.owner()));
        return new Scan(new TreeSet<>(steps), new TreeSet<>(attachments), owners,
                steps.size() + attachments.size(), List.of(), List.of(), List.of(), Map.of());
    }

    private static Baseline baselineOf(Set<Kind> steps, Set<Kind> attachments) {
        return new Baseline(steps, attachments, Set.of(), Map.of());
    }

    @Nested
    @Epic("Внутренние проверки библиотеки")
    @DisplayName("гейты вердикта (сломанный гейт = вечно-зелёный детектор)")
    class Gates {

        @Test
        @DisplayName("пустой прогон — «нет данных», а НЕ «всё в порядке»")
        void emptyScanIsNoData() {
            Verdict verdict = ReportInventory.verdict(scanOf(Set.of(), Set.of()),
                    baselineOf(Set.of(kind("KafkaReportIT", "Kafka: отправлено")), Set.of()));

            assertThat(verdict.noData()).isTrue();
            assertThat(verdict.failed(false)).as("проверка на пустых данных обязана падать").isTrue();
        }

        @Test
        @DisplayName("класс-витрина из эталона не дал результатов → «пропал тест», а не «пропал вид»")
        void ownerWithoutResultsIsSilent() {
            Verdict verdict = ReportInventory.verdict(
                    scanOf(Set.of(kind("KafkaReportIT", "Kafka: отправлено")), Set.of()),
                    baselineOf(Set.of(kind("KafkaReportIT", "Kafka: отправлено"),
                            kind("WireMockReportIT", "Создана заглушка")), Set.of()));

            assertThat(verdict.silentOwners()).containsExactly("WireMockReportIT");
            assertThat(verdict.failed(false)).isTrue();
        }

        @Test
        @DisplayName("класс исчез, а все его виды помечены «?» — всё равно падаем")
        void silentOwnerFailsEvenWhenAllKindsOptional() {
            // «?» ставят против флаки; без отдельного гейта на silentOwners класс-витрина мог бы
            // пропасть целиком при ЗЕЛЁНОЙ сборке — пропавших видов ведь нет
            Kind optional = kind("WireMockReportIT", "Создана заглушка");
            Baseline baseline = new Baseline(Set.of(kind("KafkaReportIT", "Kafka: отправлено"), optional),
                    Set.of(), Set.of(optional), Map.of());

            Verdict verdict = ReportInventory.verdict(
                    scanOf(Set.of(kind("KafkaReportIT", "Kafka: отправлено")), Set.of()), baseline);

            assertThat(verdict.missingSteps()).as("необязательный вид пропажей не считается").isEmpty();
            assertThat(verdict.silentOwners()).containsExactly("WireMockReportIT");
            assertThat(verdict.failed(false)).isTrue();
        }

        @Test
        @DisplayName("новый класс-витрина вне эталона роняет сборку (модуль вне сетки никто не стережёт)")
        void unknownOwnerFails() {
            Verdict verdict = ReportInventory.verdict(
                    scanOf(Set.of(kind("KafkaReportIT", "Kafka: отправлено"),
                            kind("НовыйReportIT", "Новый шаг")), Set.of()),
                    baselineOf(Set.of(kind("KafkaReportIT", "Kafka: отправлено")), Set.of()));

            assertThat(verdict.unknownOwners()).containsExactly("НовыйReportIT");
            // без этого гейта новый модуль просто печатался бы как «новый вид» и сборка была зелёной
            assertThat(verdict.failed(false)).isTrue();
        }

        @Test
        @DisplayName("кратность: ждали ровно один шаг, увидели два — красный (типичное задвоение)")
        void doubledStepIsCaught() {
            Kind kind = kind("KafkaReportIT", "Kafka: отправлено");
            Baseline baseline = new Baseline(Set.of(kind), Set.of(), Set.of(), Map.of(),
                    Map.of(kind, new ReportInventory.Count(1, false)));
            Scan scan = new Scan(new TreeSet<>(Set.of(kind)), new TreeSet<>(), Set.of("KafkaReportIT"), 1,
                    List.of(), List.of(), List.of(), Map.of(kind, new ReportInventory.Range(2, 2)));

            Verdict verdict = ReportInventory.verdict(scan, baseline);

            assertThat(verdict.countMismatches()).singleElement()
                    .extracting(m -> m.expected().toString() + " vs " + m.seen())
                    .isEqualTo("×1 vs 2");
            assertThat(verdict.failed(false)).isTrue();
        }

        @Test
        @DisplayName("кратность «×≥N» терпит рост, но ловит падение ниже порога")
        void atLeastCount() {
            Kind kind = kind("A", "Шаг");
            Baseline baseline = new Baseline(Set.of(kind), Set.of(), Set.of(), Map.of(),
                    Map.of(kind, new ReportInventory.Count(2, true)));

            assertThat(ReportInventory.counts(baseline.counts(), Map.of(kind, new ReportInventory.Range(3, 5)))).isEmpty();
            assertThat(ReportInventory.counts(baseline.counts(), Map.of(kind, new ReportInventory.Range(1, 5)))).hasSize(1);
        }

        @Test
        @DisplayName("непомеченные виды кратностью не стерегутся (белый список живёт в эталоне)")
        void unmarkedKindsAreNotCounted() {
            assertThat(ReportInventory.counts(Map.of(), Map.of(kind("A", "Шаг"), new ReportInventory.Range(1, 9))))
                    .isEmpty();
        }

        @Test
        @DisplayName("новый СТАТУСНЫЙ вид роняет прогон всегда: библиотека начала фабриковать сбои")
        void newStatusKindAlwaysFails() {
            // обычный новый шаг — обогащение отчёта (норма); новый не-passed статус — поломка
            // правила «упавшую проверку шагом не логируем»
            Verdict verdict = ReportInventory.verdict(
                    scanOf(Set.of(kind("A", "Шаг"), kind("A", "Шаг [FAILED]")), Set.of()),
                    baselineOf(Set.of(kind("A", "Шаг")), Set.of()));

            assertThat(verdict.newFailures()).isTrue();
            assertThat(verdict.failed(false)).isTrue();
        }

        @Test
        @DisplayName("новый вид у ЗНАКОМОГО класса сборку не роняет (только в строгом режиме)")
        void extraKindIsNotFailureUnlessStrict() {
            Verdict verdict = ReportInventory.verdict(
                    scanOf(Set.of(kind("KafkaReportIT", "Kafka: отправлено"), kind("KafkaReportIT", "Kafka: новый")), Set.of()),
                    baselineOf(Set.of(kind("KafkaReportIT", "Kafka: отправлено")), Set.of()));

            assertThat(verdict.extraSteps()).containsExactly(kind("KafkaReportIT", "Kafka: новый"));
            assertThat(verdict.failed(false)).isFalse();
            assertThat(verdict.failed(true)).isTrue();
        }

        @Test
        @DisplayName("частичный прогон запрещает обновление эталона")
        void partialRunForbidsUpdate() {
            UpdateVerdict update = ReportInventory.updateVerdict(
                    scanOf(Set.of(kind("KafkaReportIT", "Kafka: отправлено")), Set.of()),
                    baselineOf(Set.of(kind("KafkaReportIT", "Kafka: отправлено"),
                            kind("WireMockReportIT", "Создана заглушка")), Set.of()));

            assertThat(update.partialRun()).isTrue();
            assertThat(update.lostOwners()).containsExactly("WireMockReportIT");
        }

        @Test
        @DisplayName("обновление видит, какие виды УДАЛИТ (иначе красную сборку «чинят» эталоном)")
        void updateReportsRemovedKinds() {
            UpdateVerdict update = ReportInventory.updateVerdict(
                    scanOf(Set.of(kind("KafkaReportIT", "Kafka: отправлено")), Set.of()),
                    baselineOf(Set.of(kind("KafkaReportIT", "Kafka: отправлено"),
                            kind("KafkaReportIT", "Kafka: получено")), Set.of()));

            assertThat(update.partialRun()).as("класс на месте — прогон полный").isFalse();
            assertThat(update.removed()).containsExactly(kind("KafkaReportIT", "Kafka: получено"));
        }
    }

    @Nested
    @Epic("Внутренние проверки библиотеки")
    @DisplayName("сверка видов")
    class Diff {

        @Test
        @DisplayName("тот же шаг у ДРУГОГО класса не прикрывает пропажу (иначе слепы к модулю)")
        void sameStepDifferentOwnerIsStillMissing() {
            // HTTP-шаг пишут пять модулей: если MockMvc отвалился, шаг RestAssured не должен его «прикрыть»
            Verdict verdict = ReportInventory.verdict(
                    scanOf(Set.of(kind("RestAssuredReportIT", "HTTP GET /x → 200")), Set.of()),
                    baselineOf(Set.of(kind("MockMvcReportIT", "HTTP GET /x → 200"),
                            kind("RestAssuredReportIT", "HTTP GET /x → 200")), Set.of()));

            assertThat(verdict.missingSteps()).containsExactly(kind("MockMvcReportIT", "HTTP GET /x → 200"));
        }

        @Test
        @DisplayName("владелец «*»: вид засчитывается, если встретился у кого угодно")
        void anyOwnerMatchesAnywhere() {
            Set<Kind> baseline = Set.of(kind(ReportInventory.ANY_OWNER, "Liquibase: changeset <ID>"));

            assertThat(ReportInventory.missing(baseline, Set.of(),
                    Set.of(kind("DataJpaReportIT", "Liquibase: changeset <ID>")))).isEmpty();
            assertThat(ReportInventory.missing(baseline, Set.of(), Set.of()))
                    .containsExactly(kind(ReportInventory.ANY_OWNER, "Liquibase: changeset <ID>"));
        }

        @Test
        @DisplayName("необязательный вид («?») не считается пропажей")
        void optionalKindNeverMissing() {
            Kind optional = kind("LiquibaseReportIT", "SQL INSERT PUBLIC.DATABASECHANGELOG");
            assertThat(ReportInventory.missing(Set.of(optional), Set.of(optional), Set.of())).isEmpty();
        }

        @Test
        @DisplayName("«*» из эталона не плодит новых видов")
        void anyOwnerDoesNotProduceExtras() {
            Set<Kind> baseline = Set.of(kind(ReportInventory.ANY_OWNER, "Общий шаг"), kind("A", "Старый"));
            Set<Kind> seen = Set.of(kind("A", "Старый"), kind("B", "Общий шаг"), kind("A", "Новый"));

            assertThat(ReportInventory.extra(baseline, seen)).containsExactly(kind("A", "Новый"));
        }

        @Test
        @DisplayName("смена mime диагностируется отдельно: «имя есть, тип другой»")
        void mimeDriftIsDiagnosed() {
            Kind gone = kind("MockMvcReportIT", "HTTP Response Body | application/json");
            Scan scan = scanOf(Set.of(), Set.of(kind("MockMvcReportIT", "HTTP Response Body | text/plain")));

            assertThat(ReportInventory.mimeDrift(List.of(gone), scan)).containsEntry(gone, "text/plain");
        }

        @Test
        @DisplayName("вложение пропало целиком — дрейфом не считается")
        void noDriftWhenAttachmentGoneCompletely() {
            Kind gone = kind("MockMvcReportIT", "HTTP Response Body | application/json");
            assertThat(ReportInventory.mimeDrift(List.of(gone), scanOf(Set.of(), Set.of()))).isEmpty();
        }

        @Test
        @DisplayName("вид без разделителя не ломает диагностику дрейфа")
        void driftIgnoresKindsWithoutSeparator() {
            assertThat(ReportInventory.mimeDrift(List.of(kind("A", "коротко")), scanOf(Set.of(), Set.of()))).isEmpty();
        }
    }

    @Nested
    @Epic("Внутренние проверки библиотеки")
    @DisplayName("скан результатов")
    class Scanning {

        @Test
        @DisplayName("вложенность шага входит в вид (регрессия «шаг вылез наверх» видна)")
        void nestingIsPartOfKind(@TempDir Path dir) throws IOException {
            Files.writeString(dir.resolve("a-result.json"), """
                    {"labels":[{"name":"testClass","value":"io.github.kolomyychenkoai.allure.spring.demo.DataJpaReportIT"}],
                     "steps":[{"name":"DB WidgetRepository.save","steps":[{"name":"SQL INSERT widget"}]}]}
                    """, StandardCharsets.UTF_8);

            Scan nested = ReportInventory.scan(dir);

            assertThat(nested.steps()).containsExactlyInAnyOrder(
                    kind("DataJpaReportIT", "DB WidgetRepository.save"),
                    kind("DataJpaReportIT", "DB WidgetRepository.save ▸ SQL INSERT widget"));

            // тот же набор ИМЁН, но плоско — обязан дать ДРУГОЙ набор видов, иначе детектор слеп
            Files.writeString(dir.resolve("a-result.json"), """
                    {"labels":[{"name":"testClass","value":"io.github.kolomyychenkoai.allure.spring.demo.DataJpaReportIT"}],
                     "steps":[{"name":"DB WidgetRepository.save"},{"name":"SQL INSERT widget"}]}
                    """, StandardCharsets.UTF_8);

            assertThat(ReportInventory.scan(dir).steps()).isNotEqualTo(nested.steps());
        }

        @Test
        @DisplayName("пустое вложение — отдельный вид (имя и mime на месте, а толку нет)")
        void emptyAttachmentIsDistinctKind(@TempDir Path dir) throws IOException {
            Files.writeString(dir.resolve("empty-attachment.txt"), "", StandardCharsets.UTF_8);
            Files.writeString(dir.resolve("full-attachment.txt"), "id=1", StandardCharsets.UTF_8);
            Files.writeString(dir.resolve("a-result.json"), """
                    {"labels":[{"name":"testClass","value":"io.github.kolomyychenkoai.allure.spring.demo.DataJpaReportIT"}],
                     "attachments":[{"name":"DB Result","type":"text/plain","source":"empty-attachment.txt"},
                                    {"name":"SQL Query","type":"text/plain","source":"full-attachment.txt"}]}
                    """, StandardCharsets.UTF_8);

            assertThat(ReportInventory.scan(dir).attachments()).containsExactlyInAnyOrder(
                    kind("DataJpaReportIT", "DB Result | text/plain | пусто"),
                    kind("DataJpaReportIT", "SQL Query | text/plain"));
        }

        @Test
        @DisplayName("берёт только пакет demo (шаги юнит-тестов недетерминированы)")
        void scanReadsDemoResultsOnly(@TempDir Path dir) throws IOException {
            Files.writeString(dir.resolve("a-result.json"), """
                    {"labels":[{"name":"testClass","value":"io.github.kolomyychenkoai.allure.spring.demo.KafkaReportIT"}],
                     "attachments":[{"name":"Application Logs","type":"text/plain"}],
                     "steps":[{"name":"Kafka: получено 1 сообщ."}]}
                    """, StandardCharsets.UTF_8);
            Files.writeString(dir.resolve("b-result.json"), """
                    {"labels":[{"name":"testClass","value":"io.github.kolomyychenkoai.allure.spring.unit.SomeTest"}],
                     "steps":[{"name":"Проверка: чужое — верно"}]}
                    """, StandardCharsets.UTF_8);

            Scan scan = ReportInventory.scan(dir);

            assertThat(scan.owners()).containsExactly("KafkaReportIT");
            assertThat(scan.steps()).containsExactly(kind("KafkaReportIT", "Kafka: получено <N> сообщ."));
            assertThat(scan.attachments()).containsExactly(kind("KafkaReportIT", "Application Logs | text/plain"));
        }

        @Test
        @DisplayName("скан СЧИТАЕТ кратность по кейсам: два файла с 1 и 2 вхождениями → 1..2")
        void scanCountsPerCase(@TempDir Path dir) throws IOException {
            // Звено «scan → perCase» раньше не проверял никто: убери local.merge — все маркеры
            // становятся инертными, и сборка остаётся зелёной.
            Files.writeString(dir.resolve("a-result.json"), """
                    {"labels":[{"name":"testClass","value":"io.github.kolomyychenkoai.allure.spring.demo.KafkaReportIT"}],
                     "steps":[{"name":"Kafka: отправлено → t [k]"}]}
                    """, StandardCharsets.UTF_8);
            Files.writeString(dir.resolve("b-result.json"), """
                    {"labels":[{"name":"testClass","value":"io.github.kolomyychenkoai.allure.spring.demo.KafkaReportIT"}],
                     "steps":[{"name":"Kafka: отправлено → t [k]"},{"name":"Kafka: отправлено → t [k]"}]}
                    """, StandardCharsets.UTF_8);

            Range range = ReportInventory.scan(dir).perCase()
                    .get(kind("KafkaReportIT", "Kafka: отправлено → t [<KEY>]"));

            assertThat(range).isNotNull();
            assertThat(range.min()).isEqualTo(1);
            assertThat(range.max()).isEqualTo(2);
            assertThat(range.cases()).as("наблюдений должно быть два").isEqualTo(2);
        }

        @Test
        @DisplayName("несуществующий каталог даёт пустой результат, а не падение")
        void scanMissingDirectory(@TempDir Path dir) {
            Scan scan = ReportInventory.scan(dir.resolve("нет-такого"));
            assertThat(scan.owners()).isEmpty();
            assertThat(scan.resultFiles()).isZero();
        }

        @Test
        @DisplayName("вложение БЕЗ ФАЙЛА — отдельный диагноз, а не «содержимое есть»")
        void missingAttachmentFileIsReported(@TempDir Path dir) throws IOException {
            // худший исход прежней логики: «объявили вложение, байты потеряли» выглядело здоровым
            Files.writeString(dir.resolve("a-result.json"), """
                    {"labels":[{"name":"testClass","value":"io.github.kolomyychenkoai.allure.spring.demo.KafkaReportIT"}],
                     "attachments":[{"name":"DB Result","type":"text/plain","source":"нет-такого.txt"}]}
                    """, StandardCharsets.UTF_8);

            Scan scan = ReportInventory.scan(dir);

            assertThat(scan.missingFiles()).singleElement().asString()
                    .contains("DB Result").contains("нет-такого.txt");
        }

        @Test
        @DisplayName("технический мусор в имени шага попадает в отдельный список")
        void dirtyStepNameIsReported(@TempDir Path dir) throws IOException {
            Files.writeString(dir.resolve("a-result.json"), """
                    {"labels":[{"name":"testClass","value":"io.github.kolomyychenkoai.allure.spring.demo.AssertJReportIT"}],
                     "steps":[{"name":"Проверка: значение x — satisfies Demo$$Lambda/0x00007f@1a2b3c"},
                              {"name":"Проверка: значение [a, b] — contains [a]"}]}
                    """, StandardCharsets.UTF_8);

            Scan scan = ReportInventory.scan(dir);

            assertThat(scan.dirtyNames()).singleElement().asString().contains("$$Lambda");
        }

        @Test
        @DisplayName("технический мусор в ТЕЛЕ вложения попадает в отдельный список")
        void dirtyAttachmentBodyIsReported(@TempDir Path dir) throws IOException {
            // вид вложения знает только «пусто/непусто» — без этого гейта выродившееся тело
            // (типовой исход апгрейда: рендер выдал внутренности) проходит мимо сетки
            Files.writeString(dir.resolve("dirty-attachment.txt"),
                    "Method: WidgetRepo.save\nArguments:\n  [0]: [B@4a3f2b1c", StandardCharsets.UTF_8);
            Files.writeString(dir.resolve("clean-attachment.txt"),
                    "Widget{id=1, name=gadget}", StandardCharsets.UTF_8);
            Files.writeString(dir.resolve("a-result.json"), """
                    {"labels":[{"name":"testClass","value":"io.github.kolomyychenkoai.allure.spring.demo.AllureMockitoReportIT"}],
                     "attachments":[{"name":"Mock Call","type":"text/plain","source":"dirty-attachment.txt"},
                                    {"name":"DB Result","type":"text/plain","source":"clean-attachment.txt"}]}
                    """, StandardCharsets.UTF_8);

            Scan scan = ReportInventory.scan(dir);

            assertThat(scan.dirtyBodies()).singleElement().asString()
                    .contains("Mock Call")
                    .contains("toString массива")
                    .contains("[B@4a3f2b1c"); // в диагноз попадает кусок тела вокруг находки
        }

        @Test
        @DisplayName("АНТИ-правило: по НЕтекстовому вложению гигиена тел не гоняется")
        void binaryAttachmentBodyNotChecked(@TempDir Path dir) throws IOException {
            // регулярки по декодированным байтам картинки выдумывали бы нарушения на ровном месте
            Files.writeString(dir.resolve("shot-attachment.png"), "PNG…[B@4a3f2b1c", StandardCharsets.UTF_8);
            Files.writeString(dir.resolve("a-result.json"), """
                    {"labels":[{"name":"testClass","value":"io.github.kolomyychenkoai.allure.spring.demo.MockMvcReportIT"}],
                     "attachments":[{"name":"Скриншот","type":"image/png","source":"shot-attachment.png"}]}
                    """, StandardCharsets.UTF_8);

            assertThat(ReportInventory.scan(dir).dirtyBodies()).isEmpty();
        }

        @Test
        @DisplayName("вложение без файла: диагноз ровно ОДИН — про отсутствие файла, не про мусор")
        void missingBodyNotDiagnosedTwice(@TempDir Path dir) throws IOException {
            // Рядом кладём вложение с настоящим мусором в теле: без него тест был бы
            // демонстрацией — «пусто» получалось бы и от сломанной гигиены тоже.
            Files.writeString(dir.resolve("dirty-attachment.txt"), "  [0]: [B@4a3f2b1c", StandardCharsets.UTF_8);
            Files.writeString(dir.resolve("a-result.json"), """
                    {"labels":[{"name":"testClass","value":"io.github.kolomyychenkoai.allure.spring.demo.KafkaReportIT"}],
                     "attachments":[{"name":"DB Result","type":"text/plain","source":"нет-такого.txt"},
                                    {"name":"Mock Call","type":"text/plain","source":"dirty-attachment.txt"}]}
                    """, StandardCharsets.UTF_8);

            Scan scan = ReportInventory.scan(dir);

            assertThat(scan.missingFiles()).singleElement().asString().contains("DB Result");
            // гигиена тел жива и видит соседа — но про пропавший файл второй раз не говорит
            assertThat(scan.dirtyBodies()).singleElement().asString()
                    .contains("Mock Call").doesNotContain("DB Result");
        }

        @Test
        @DisplayName("битый result.json не роняет скан")
        void brokenJsonIgnored(@TempDir Path dir) throws IOException {
            Files.writeString(dir.resolve("broken-result.json"), "{не json", StandardCharsets.UTF_8);
            assertThat(ReportInventory.scan(dir).owners()).isEmpty();
        }
    }

    @Nested
    @Epic("Внутренние проверки библиотеки")
    @DisplayName("эталон: чтение и запись")
    class BaselineFile {

        @Test
        @DisplayName("читается: секции, «?», «*», подписи-комментарии")
        void baselineParsing(@TempDir Path dir) throws IOException {
            Path file = dir.resolve("baseline.txt");
            Files.writeString(file, """
                    # заголовок
                    [steps]
                    KafkaReportIT | Kafka: отправлено   # AllureKafkaProducerInstrumentation
                    ? LiquibaseReportIT | SQL SELECT
                    * | Liquibase: changeset <ID>

                    [attachments]
                    KafkaReportIT | Значение сообщения | application/json   # притти-JSON
                    """, StandardCharsets.UTF_8);

            Baseline baseline = ReportInventory.readBaseline(file);

            assertThat(baseline.steps()).containsExactly(
                    kind("*", "Liquibase: changeset <ID>"),
                    kind("KafkaReportIT", "Kafka: отправлено"),
                    kind("LiquibaseReportIT", "SQL SELECT"));
            assertThat(baseline.optional()).containsExactly(kind("LiquibaseReportIT", "SQL SELECT"));
            // mime — часть вида вложения, а не отдельная колонка
            assertThat(baseline.attachments()).containsExactly(
                    kind("KafkaReportIT", "Значение сообщения | application/json"));
            assertThat(baseline.comments()).containsEntry(
                    kind("KafkaReportIT", "Kafka: отправлено"), "AllureKafkaProducerInstrumentation");
            assertThat(baseline.owners()).containsExactly("KafkaReportIT", "LiquibaseReportIT");
        }

        @Test
        @DisplayName("маркер кратности переживает круг «запись → чтение» (иначе белый список пуст)")
        void countMarkerRoundTrip(@TempDir Path dir) throws IOException {
            // Звено «формат файла» раньше не проверял никто: сломанный COUNT_MARKER тихо обнулял
            // ВЕСЬ белый список, и гейт кратности становился декоративным при зелёной сборке.
            Path file = dir.resolve("inventory/baseline.txt");
            Kind exact = kind("A", "Шаг ровно один");
            Kind atLeast = kind("A", "Шаг не меньше трёх");
            Baseline previous = new Baseline(Set.of(exact, atLeast), Set.of(), Set.of(), Map.of(),
                    Map.of(exact, new ReportInventory.Count(2, false),
                            atLeast, new ReportInventory.Count(3, true)));

            ReportInventory.write(file, scanOf(Set.of(exact, atLeast), Set.of()), previous);
            Baseline reread = ReportInventory.readBaseline(file);

            assertThat(reread.counts()).containsOnlyKeys(exact, atLeast);
            assertThat(reread.counts().get(exact).value()).isEqualTo(2);
            assertThat(reread.counts().get(exact).atLeast()).isFalse();
            assertThat(reread.counts().get(atLeast).atLeast()).isTrue();
            // и сами виды не пострадали от «откусывания» маркера из строки
            assertThat(reread.steps()).containsExactlyInAnyOrder(exact, atLeast);
        }

        @Test
        @DisplayName("посев: одинаковое число → ×N (ловит задвоение), разброс → ×≥N (терпит рост)")
        void seedMarksStableAndFloating() {
            Kind onlyOnce = kind("A", "Виден один раз");
            Kind stable = kind("A", "Стабильный");
            Kind floating = kind("A", "Плавающий");
            Scan scan = new Scan(new TreeSet<>(), new TreeSet<>(), Set.of("A"), 1, List.of(), List.of(), List.of(),
                    Map.of(onlyOnce, new ReportInventory.Range(6, 6, 1),
                            stable, new ReportInventory.Range(1, 1, 4),
                            floating, new ReportInventory.Range(1, 3, 4)));

            Map<Kind, ReportInventory.Count> seeded =
                    ReportInventory.seedCounts(scan, baselineOf(Set.of(), Set.of()));

            // вид из одного кейса тоже пиннится жёстко: «×≥N» задвоение не ловит (2 ≥ 1),
            // а именно оно — главный класс регрессии. Размен объяснён в javadoc seedCounts.
            assertThat(seeded.get(onlyOnce).atLeast()).isFalse();
            assertThat(seeded.get(onlyOnce).value()).isEqualTo(6);
            assertThat(seeded.get(stable).atLeast()).isFalse();
            assertThat(seeded.get(floating).atLeast()).isTrue();
            assertThat(seeded.get(floating).value()).isEqualTo(1);
        }

        @Test
        @DisplayName("«#» внутри имени шага не съедается как комментарий")
        void hashInsideStepNameIsNotAComment(@TempDir Path dir) throws IOException {
            Path file = dir.resolve("baseline.txt");
            Files.writeString(file, "[steps]\nA | Проверка: значение #42 — isEqualTo <V>\n", StandardCharsets.UTF_8);

            assertThat(ReportInventory.readBaseline(file).steps())
                    .containsExactly(kind("A", "Проверка: значение #42 — isEqualTo <V>"));
        }

        @Test
        @DisplayName("перезапись сохраняет подписи, «?» и «*» (ручная работа не теряется)")
        void writeCarriesManualWork(@TempDir Path dir) throws IOException {
            Path file = dir.resolve("inventory/baseline.txt");
            Kind step = kind("KafkaReportIT", "Kafka: отправлено");
            Kind optional = kind("LiquibaseReportIT", "SQL SELECT");
            Kind anyOwner = kind(ReportInventory.ANY_OWNER, "Liquibase: changeset <ID>");
            Baseline previous = new Baseline(Set.of(step, optional, anyOwner), Set.of(), Set.of(optional),
                    Map.of(step, "AllureKafkaProducerInstrumentation"));
            // прогон: необязательного вида НЕТ, а «*»-вид пришёл с КОНКРЕТНЫМ владельцем
            Scan scan = scanOf(Set.of(step, kind("DataJpaReportIT", "Liquibase: changeset <ID>")), Set.of());

            ReportInventory.write(file, scan, previous);

            Baseline reread = ReportInventory.readBaseline(file);
            assertThat(reread.comments()).containsEntry(step, "AllureKafkaProducerInstrumentation");
            assertThat(reread.optional()).as("«?»-вид не должен исчезнуть из-за одного прогона")
                    .containsExactly(optional);
            assertThat(reread.steps()).as("«*» не должен вытесняться конкретным владельцем")
                    .containsExactlyInAnyOrder(step, optional, anyOwner);
        }

        @Test
        @DisplayName("запись идемпотентна (второй прогон не даёт diff)")
        void writeIsIdempotent(@TempDir Path dir) throws IOException {
            Path file = dir.resolve("inventory/baseline.txt");
            Scan scan = scanOf(Set.of(kind("KafkaReportIT", "Kafka: отправлено")),
                    Set.of(kind("KafkaReportIT", "Значение сообщения | application/json")));

            ReportInventory.write(file, scan, ReportInventory.readBaseline(file));
            String first = Files.readString(file, StandardCharsets.UTF_8);
            ReportInventory.write(file, scan, ReportInventory.readBaseline(file));

            assertThat(Files.readString(file, StandardCharsets.UTF_8)).isEqualTo(first);
        }
    }

    @Nested
    @Epic("Внутренние проверки библиотеки")
    @DisplayName("форма тела вложения (гейты сломанного гейта)")
    class Shapes {

        private final Kind nearMiss = kind("WireMockReportIT", "Near miss (почему не сматчилось) | text/plain");

        /** Наблюдения формы: сколько передали — столько и «видели». */
        private ReportInventory.ShapeStat stat(ReportInventory.Shape... observed) {
            return new ReportInventory.ShapeStat(
                    java.util.EnumSet.copyOf(java.util.List.of(observed)), observed.length);
        }


        @Test
        @DisplayName("многострочное тело схлопнулось в одну строку — красный")
        void collapsedBodyIsCaught() {
            // Ровно та регрессия, которая прошла мимо сетки руками: near-miss стал одной строкой,
            // а имя, mime и «непусто» не изменились.
            List<ReportInventory.ShapeMismatch> mismatches = ReportInventory.shapes(
                    Map.of(nearMiss, ReportInventory.Shape.MULTILINE),
                    Map.of(nearMiss, stat(ReportInventory.Shape.ONE_LINE)));

            assertThat(mismatches).singleElement()
                    .satisfies(m -> assertThat(m.kind()).isEqualTo(nearMiss));
        }

        @Test
        @DisplayName("форма стала ПЛАВАЮЩЕЙ — тоже красный (маркер обещал стабильность)")
        void unstableShapeIsCaught() {
            assertThat(ReportInventory.shapes(
                    Map.of(nearMiss, ReportInventory.Shape.MULTILINE),
                    Map.of(nearMiss, stat(ReportInventory.Shape.MULTILINE, ReportInventory.Shape.ONE_LINE))))
                    .hasSize(1);
        }

        @Test
        @DisplayName("АНТИ-правило: вид без маркера формой не стерегётся")
        void unmarkedKindNotGuarded() {
            assertThat(ReportInventory.shapes(Map.of(), Map.of(nearMiss, stat(ReportInventory.Shape.ONE_LINE))))
                    .isEmpty();
        }

        @Test
        @DisplayName("АНТИ-правило: вид, которого прогон не дал, — это missing, а не форма")
        void absentKindNotDiagnosedTwice() {
            assertThat(ReportInventory.shapes(Map.of(nearMiss, ReportInventory.Shape.MULTILINE), Map.of()))
                    .isEmpty();
        }

        @Test
        @DisplayName("посев: стабильная форма получает маркер, плавающая — теряет")
        void seedMarksOnlyStable() {
            Kind stable = kind("KafkaReportIT", "Значение сообщения | application/json");
            Kind floating = kind("DataJpaReportIT", "DB Call | text/plain");
            Kind oneLine = kind("JdbcReportIT", "DB Result | text/plain");
            Scan scan = new Scan(new TreeSet<>(), new TreeSet<>(), Set.of("KafkaReportIT"), 1,
                    List.of(), List.of(), List.of(), Map.of(), List.of(),
                    Map.of(stable, stat(ReportInventory.Shape.MULTILINE, ReportInventory.Shape.MULTILINE),
                            floating, stat(ReportInventory.Shape.MULTILINE, ReportInventory.Shape.ONE_LINE),
                            oneLine, stat(ReportInventory.Shape.ONE_LINE, ReportInventory.Shape.ONE_LINE)));
            Baseline previous = new Baseline(Set.of(), Set.of(), Set.of(), Map.of(), Map.of(),
                    Map.of(floating, ReportInventory.Shape.MULTILINE));

            Map<Kind, ReportInventory.Shape> seeded = ReportInventory.seedShapes(scan, previous);

            assertThat(seeded).containsEntry(stable, ReportInventory.Shape.MULTILINE);
            assertThat(seeded).as("поплывшая форма обязана ПОТЕРЯТЬ маркер: «не меньше» тут не бывает")
                    .doesNotContainKey(floating);
            assertThat(seeded).as("однострочное не помечаем: «стало несколько строк» — не деградация, "
                            + "а обычное поведение логов, и такой маркер только флакал бы")
                    .doesNotContainKey(oneLine);
        }

        @Test
        @DisplayName("маркер формы переживает запись и чтение эталона (рядом с кратностью)")
        void markerSurvivesRoundTrip(@TempDir Path dir) throws IOException {
            // Оба маркера на одной строке: чтение обязано снимать их в обратном порядке записи,
            // иначе один съест другой и белый список тихо распустится.
            Path file = dir.resolve("baseline.txt");
            Kind kind = kind("WireMockReportIT", "Near miss | text/plain");
            Scan scan = scanOf(Set.of(), Set.of(kind));
            Baseline previous = new Baseline(Set.of(), Set.of(kind), Set.of(),
                    Map.of(kind, "AllureWireMockSteps"),
                    Map.of(kind, new ReportInventory.Count(1, false)),
                    Map.of(kind, ReportInventory.Shape.MULTILINE));

            ReportInventory.write(file, scan, previous);
            Baseline reread = ReportInventory.readBaseline(file);

            assertThat(reread.shapes()).containsEntry(kind, ReportInventory.Shape.MULTILINE);
            assertThat(reread.counts()).containsEntry(kind, new ReportInventory.Count(1, false));
            assertThat(reread.comments()).containsEntry(kind, "AllureWireMockSteps");
            assertThat(reread.attachments()).containsExactly(kind);
        }

        @Test
        @DisplayName("скан читает форму из настоящих файлов вложений")
        void scanReadsShapeFromFiles(@TempDir Path dir) throws IOException {
            Files.writeString(dir.resolve("multi-attachment.txt"), "GET | GET\n/a | /b", StandardCharsets.UTF_8);
            Files.writeString(dir.resolve("one-attachment.txt"), "id=1", StandardCharsets.UTF_8);
            Files.writeString(dir.resolve("a-result.json"), """
                    {"labels":[{"name":"testClass","value":"io.github.kolomyychenkoai.allure.spring.demo.WireMockReportIT"}],
                     "attachments":[{"name":"Near miss","type":"text/plain","source":"multi-attachment.txt"},
                                    {"name":"DB Result","type":"text/plain","source":"one-attachment.txt"}]}
                    """, StandardCharsets.UTF_8);

            Map<Kind, ReportInventory.ShapeStat> shapes = ReportInventory.scan(dir).shapes();

            assertThat(shapes.get(kind("WireMockReportIT", "Near miss | text/plain")).seen())
                    .containsExactly(ReportInventory.Shape.MULTILINE);
            assertThat(shapes.get(kind("WireMockReportIT", "DB Result | text/plain")).seen())
                    .containsExactly(ReportInventory.Shape.ONE_LINE);
        }

        @Test
        @DisplayName("пустое тело формы не имеет (это уже отдельное измерение вида)")
        void emptyBodyHasNoShape(@TempDir Path dir) throws IOException {
            Files.writeString(dir.resolve("empty-attachment.txt"), "   ", StandardCharsets.UTF_8);
            Files.writeString(dir.resolve("a-result.json"), """
                    {"labels":[{"name":"testClass","value":"io.github.kolomyychenkoai.allure.spring.demo.KafkaReportIT"}],
                     "attachments":[{"name":"DB Result","type":"text/plain","source":"empty-attachment.txt"}]}
                    """, StandardCharsets.UTF_8);

            assertThat(ReportInventory.scan(dir).shapes()).isEmpty();
        }
    }
}
