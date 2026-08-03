package io.github.kolomyychenkoai.allure.spring.unit;

import io.qameta.allure.Epic;
import io.github.kolomyychenkoai.allure.spring.inventory.ReportInventory;
import io.github.kolomyychenkoai.allure.spring.inventory.ReportInventory.Baseline;
import io.github.kolomyychenkoai.allure.spring.inventory.ReportInventory.Kind;
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
                steps.size() + attachments.size(), List.of(), List.of(), List.of());
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
}
