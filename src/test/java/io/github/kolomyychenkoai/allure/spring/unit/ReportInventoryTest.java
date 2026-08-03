package io.github.kolomyychenkoai.allure.spring.unit;

import io.github.kolomyychenkoai.allure.spring.inventory.ReportInventory;
import io.github.kolomyychenkoai.allure.spring.inventory.ReportInventory.Baseline;
import io.github.kolomyychenkoai.allure.spring.inventory.ReportInventory.Kind;
import io.github.kolomyychenkoai.allure.spring.inventory.ReportInventory.Scan;
import org.junit.jupiter.api.DisplayName;
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
 * Тесты логики инвентаря отчёта (скан → дифф → эталон). Сам {@code ReportInventoryCheck}
 * гоняется отдельным прогоном surefire и на живых результатах; здесь — его внутренности
 * на синтетике, включая случаи, которые в живом прогоне не воспроизвести
 * (владелец «*», необязательные виды, смена mime).
 */
class ReportInventoryTest {

    private static Kind kind(String owner, String text) {
        return new Kind(owner, text);
    }

    private static Scan scanOf(Set<Kind> steps, Set<Kind> attachments) {
        Set<String> owners = new TreeSet<>();
        steps.forEach(k -> owners.add(k.owner()));
        attachments.forEach(k -> owners.add(k.owner()));
        return new Scan(steps, attachments, owners, steps.size() + attachments.size());
    }

    @Test
    @DisplayName("пропажа вида у конкретного класса-владельца видна")
    void missingKindDetected() {
        Set<Kind> baseline = Set.of(kind("KafkaReportIT", "Kafka: отправлено"), kind("KafkaReportIT", "Kafka: получено"));
        Scan scan = scanOf(new TreeSet<>(Set.of(kind("KafkaReportIT", "Kafka: получено"))), new TreeSet<>());

        List<Kind> missing = ReportInventory.missing(baseline, Set.of(), scan, false);

        assertThat(missing).containsExactly(kind("KafkaReportIT", "Kafka: отправлено"));
    }

    @Test
    @DisplayName("тот же шаг у ДРУГОГО класса не считается присутствующим (иначе слепы к модулю)")
    void sameStepDifferentOwnerIsStillMissing() {
        // HTTP-шаг пишут пять модулей: если MockMvc отвалился, шаг RestAssured не должен его «прикрыть»
        Set<Kind> baseline = Set.of(kind("MockMvcReportIT", "HTTP GET /x → 200"));
        Scan scan = scanOf(new TreeSet<>(Set.of(kind("RestAssuredReportIT", "HTTP GET /x → 200"))), new TreeSet<>());

        assertThat(ReportInventory.missing(baseline, Set.of(), scan, false))
                .containsExactly(kind("MockMvcReportIT", "HTTP GET /x → 200"));
    }

    @Test
    @DisplayName("владелец «*»: вид засчитывается, если встретился у кого угодно")
    void anyOwnerMatchesAnywhere() {
        Set<Kind> baseline = Set.of(kind(ReportInventory.ANY_OWNER, "Liquibase: changeset <ID>"));
        Scan scan = scanOf(new TreeSet<>(Set.of(kind("DataJpaReportIT", "Liquibase: changeset <ID>"))), new TreeSet<>());

        assertThat(ReportInventory.missing(baseline, Set.of(), scan, false)).isEmpty();
        // и наоборот — если не встретился нигде, это пропажа
        assertThat(ReportInventory.missing(baseline, Set.of(), scanOf(new TreeSet<>(), new TreeSet<>()), false))
                .containsExactly(kind(ReportInventory.ANY_OWNER, "Liquibase: changeset <ID>"));
    }

    @Test
    @DisplayName("необязательный вид («?») не считается пропажей")
    void optionalKindNeverMissing() {
        Kind optional = kind("LiquibaseReportIT", "SQL INSERT PUBLIC.DATABASECHANGELOG");
        Scan empty = scanOf(new TreeSet<>(), new TreeSet<>());

        assertThat(ReportInventory.missing(Set.of(optional), Set.of(optional), empty, false)).isEmpty();
    }

    @Test
    @DisplayName("новые виды находятся, но «*» из эталона их не плодит")
    void extraKinds() {
        Set<Kind> baseline = Set.of(kind(ReportInventory.ANY_OWNER, "Общий шаг"), kind("A", "Старый"));
        Set<Kind> seen = new TreeSet<>(Set.of(
                kind("A", "Старый"), kind("B", "Общий шаг"), kind("A", "Новый")));

        assertThat(ReportInventory.extra(baseline, seen)).containsExactly(kind("A", "Новый"));
    }

    @Test
    @DisplayName("смена mime диагностируется отдельно: «имя есть, тип другой»")
    void mimeDriftIsDiagnosed() {
        Kind gone = kind("MockMvcReportIT", "HTTP Response Body | application/json");
        Scan scan = scanOf(new TreeSet<>(),
                new TreeSet<>(Set.of(kind("MockMvcReportIT", "HTTP Response Body | text/plain"))));

        Map<Kind, String> drift = ReportInventory.mimeDrift(List.of(gone), scan);

        assertThat(drift).containsEntry(gone, "text/plain");
    }

    @Test
    @DisplayName("вложение пропало целиком (нет и с другим mime) — дрейфом не считается")
    void noDriftWhenAttachmentGoneCompletely() {
        Kind gone = kind("MockMvcReportIT", "HTTP Response Body | application/json");
        assertThat(ReportInventory.mimeDrift(List.of(gone), scanOf(new TreeSet<>(), new TreeSet<>()))).isEmpty();
    }

    @Test
    @DisplayName("эталон читается: секции, «?», «*», подписи-комментарии")
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
    @DisplayName("перезапись эталона сохраняет подписи и пометки «?» для выживших видов")
    void writeCarriesCommentsAndOptionalMarks(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("inventory/baseline.txt");
        Kind step = kind("KafkaReportIT", "Kafka: отправлено");
        Kind optionalStep = kind("LiquibaseReportIT", "SQL SELECT");
        Baseline previous = new Baseline(Set.of(step, optionalStep), Set.of(), Set.of(optionalStep),
                Map.of(step, "AllureKafkaProducerInstrumentation"));

        ReportInventory.write(file, scanOf(new TreeSet<>(Set.of(step, optionalStep)), new TreeSet<>()), previous);

        Baseline reread = ReportInventory.readBaseline(file);
        assertThat(reread.comments()).containsEntry(step, "AllureKafkaProducerInstrumentation");
        assertThat(reread.optional()).containsExactly(optionalStep);
        assertThat(reread.steps()).containsExactlyInAnyOrder(step, optionalStep);
    }

    @Test
    @DisplayName("скан несуществующего каталога даёт пустой результат, а не падение")
    void scanMissingDirectory(@TempDir Path dir) {
        Scan scan = ReportInventory.scan(dir.resolve("нет-такого"));
        assertThat(scan.owners()).isEmpty();
        assertThat(scan.resultFiles()).isZero();
    }

    @Test
    @DisplayName("скан берёт только пакет demo и разворачивает вложенные шаги")
    void scanReadsDemoResultsOnly(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("a-result.json"), """
                {"labels":[{"name":"testClass","value":"io.github.kolomyychenkoai.allure.spring.demo.KafkaReportIT"}],
                 "attachments":[{"name":"Application Logs","type":"text/plain"}],
                 "steps":[{"name":"Kafka: получено 1 сообщ.","attachments":[{"name":"Принятые сообщения","type":"text/plain"}],
                           "steps":[{"name":"SQL INSERT widget"}]}]}
                """, StandardCharsets.UTF_8);
        // юнит-тест вне demo: его шаги недетерминированы (перехват мог встать от соседа) — игнорируем
        Files.writeString(dir.resolve("b-result.json"), """
                {"labels":[{"name":"testClass","value":"io.github.kolomyychenkoai.allure.spring.unit.SomeTest"}],
                 "steps":[{"name":"Проверка: чужое — верно"}]}
                """, StandardCharsets.UTF_8);

        Scan scan = ReportInventory.scan(dir);

        assertThat(scan.owners()).containsExactly("KafkaReportIT");
        assertThat(scan.steps()).containsExactlyInAnyOrder(
                kind("KafkaReportIT", "Kafka: получено <N> сообщ."),
                kind("KafkaReportIT", "SQL INSERT widget"));
        assertThat(scan.attachments()).containsExactlyInAnyOrder(
                kind("KafkaReportIT", "Application Logs | text/plain"),
                kind("KafkaReportIT", "Принятые сообщения | text/plain"));
    }

    @Test
    @DisplayName("битый result.json не роняет скан")
    void brokenJsonIgnored(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("broken-result.json"), "{не json", StandardCharsets.UTF_8);
        assertThat(ReportInventory.scan(dir).owners()).isEmpty();
    }
}
