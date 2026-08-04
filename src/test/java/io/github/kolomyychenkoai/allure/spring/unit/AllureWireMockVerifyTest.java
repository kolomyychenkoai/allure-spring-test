package io.github.kolomyychenkoai.allure.spring.unit;

import io.qameta.allure.Epic;

import io.github.kolomyychenkoai.allure.spring.wiremock.internal.AllureWireMockSteps;
import io.github.kolomyychenkoai.allure.spring.wiremock.internal.AllureWireMockVerifyInstrumentation;
import io.github.kolomyychenkoai.allure.spring.support.InMemoryAllure;
import io.qameta.allure.model.Attachment;
import io.qameta.allure.model.Status;
import io.qameta.allure.model.StepResult;
import io.qameta.allure.model.TestResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.github.tomakehurst.wiremock.stubbing.StubMapping;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.lessThan;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

/** Уровень A: проверка содержимого отчёта для WireMock verify/reset (без брокера). */
@Epic("Внутренние проверки библиотеки")
class AllureWireMockVerifyTest {

    private InMemoryAllure allure;

    @BeforeEach
    void setUp() {
        allure = new InMemoryAllure().install();
    }

    @AfterEach
    void tearDown() {
        allure.uninstall();
    }

    private List<String> stepNames(TestResult result) {
        return result.getSteps().stream().map(StepResult::getName).toList();
    }

    @Test
    @DisplayName("verify(pattern): шаг + условие во вложении")
    void logsVerifyPattern() {
        TestResult result = allure.run("v", () -> AllureWireMockVerifyInstrumentation.onVerify(
                new Object[]{getRequestedFor(urlPathEqualTo("/api/prices"))}, null));

        assertThat(allure.hasStep(result, "Проверка обращений к заглушке")).isTrue();
        assertThat(allure.attachment(result, "Условие проверки").orElseThrow())
                .contains("/api/prices").contains("GET");
    }

    @Test
    @DisplayName("verify(count, ...) и verify(стратегия, ...): количество в имени шага")
    void logsVerifyCount() {
        TestResult exact = allure.run("count", () -> AllureWireMockVerifyInstrumentation.onVerify(
                new Object[]{2, getRequestedFor(urlPathEqualTo("/api/prices"))}, null));
        assertThat(allure.hasStep(exact, "Проверка обращений к заглушке (×2)")).isTrue();

        TestResult strategy = allure.run("strategy", () -> AllureWireMockVerifyInstrumentation.onVerify(
                new Object[]{lessThan(3), getRequestedFor(urlPathEqualTo("/api/prices"))}, null));
        assertThat(stepNames(strategy)).anyMatch(n ->
                n.startsWith("Проверка обращений к заглушке") && n.toLowerCase().contains("less than 3"));
    }

    @Test
    @DisplayName("непрошедший verify шага НЕ создаёт (падение покажет Allure)")
    void failedVerifyProducesNoStep() {
        TestResult result = allure.run("fail", () -> AllureWireMockVerifyInstrumentation.onVerify(
                new Object[]{getRequestedFor(urlPathEqualTo("/api/prices"))},
                new AssertionError("ожидалось обращение, которого не было")));

        assertThat(stepNames(result)).noneMatch(n -> n.startsWith("Проверка обращений"));
    }

    @Test
    @DisplayName("serverUp: шаг «WireMock: сервер поднят (:port)» с реальным портом")
    void logsServerUp() {
        WireMockServer server = new WireMockServer(options().dynamicPort());
        server.start();
        try {
            TestResult result = allure.run("up", () -> AllureWireMockSteps.serverUp(server));
            // мутация: убери порт из имени → рассинхрон с ассертом; убери шаг из beforeTestMethod → RED в IT
            assertThat(allure.hasStep(result, "WireMock: сервер поднят (:" + server.port() + ")")).isTrue();
        } finally {
            server.stop();
        }
    }

    @Test
    @DisplayName("serverUp на https-only сервере: шаг рисуется БЕЗ порта (safePort деградирует, не теряем шаг)")
    void serverUpHttpsOnlyDegradesGracefully() {
        // у https-only сервера нет HTTP-порта → server.port() кинет; safePort→null → имя без порта.
        // мутация: верни в serverUp прямой server.port() → шаг пропадёт (Throwable) → RED
        WireMockServer server = new WireMockServer(options().httpDisabled(true).dynamicHttpsPort());
        server.start();
        try {
            TestResult result = allure.run("up-https", () -> AllureWireMockSteps.serverUp(server));
            assertThat(allure.hasStep(result, "WireMock: сервер поднят")).isTrue();
        } finally {
            server.stop();
        }
    }

    @Test
    @DisplayName("resetAll даёт шаг «WireMock: сброс заглушек»")
    void logsResetAll() {
        // server=null: near-miss/сценарии не снимаются, проверяем сам шаг сброса
        TestResult result = allure.run("reset", () -> AllureWireMockVerifyInstrumentation.onResetAll(null));

        assertThat(allure.hasStep(result, "WireMock: сброс заглушек")).isTrue();
    }

    @Test
    @DisplayName("несколько серверов: сброс каждого — свой шаг с его портом (не схлопывается в один)")
    void resetAllPerServerLabeledByPort() {
        WireMockServer a = new WireMockServer(options().dynamicPort());
        WireMockServer b = new WireMockServer(options().dynamicPort());
        a.start();
        b.start();
        try {
            // один тест-кейс, teardown сбрасывает оба сервера (как база-класс в реальном сервисе)
            TestResult result = allure.run("multi-server", () -> {
                AllureWireMockVerifyInstrumentation.onResetAll(a);
                AllureWireMockVerifyInstrumentation.onResetAll(b);
            });
            // мутация: убери порт из имени / ключа → оба шага совпадут и дедуп схлопнет в один → RED
            assertThat(stepNames(result)).contains(
                    "WireMock: сброс заглушек (:" + a.port() + ")",
                    "WireMock: сброс заглушек (:" + b.port() + ")");
        } finally {
            a.stop();
            b.stop();
        }
    }

    @Test
    @DisplayName("один сервер, сброшенный несколько раз за тест → шаг сброса ОДИН (дедуп по серверу)")
    void resetAllSameServerDedupedWithinTest() {
        WireMockServer server = new WireMockServer(options().dynamicPort());
        server.start();
        try {
            // teardown/Spring Cloud Contract зовёт resetAll не раз — в отчёте это должен быть ОДИН шаг
            TestResult result = allure.run("repeat-reset", () -> {
                AllureWireMockVerifyInstrumentation.onResetAll(server);
                AllureWireMockVerifyInstrumentation.onResetAll(server);
                AllureWireMockVerifyInstrumentation.onResetAll(server);
            });
            long resets = result.getSteps().stream()
                    .filter(s -> s.getName().startsWith("WireMock: сброс заглушек")).count();
            // мутация: убери дедуп (всегда логировать) → 3 шага → RED
            assertThat(resets).isEqualTo(1);
        } finally {
            server.stop();
        }
    }

    @Test
    @DisplayName("тот же сервер в СЛЕДУЮЩЕМ тест-кейсе снова даёт шаг сброса (набор чистится между кейсами)")
    void resetAllStepReappearsInNextTestCase() {
        WireMockServer server = new WireMockServer(options().dynamicPort());
        server.start();
        try {
            // два РАЗНЫХ тест-кейса подряд на одном потоке сбрасывают ОДИН и тот же сервер
            TestResult case1 = allure.run("case-1", () -> AllureWireMockVerifyInstrumentation.onResetAll(server));
            TestResult case2 = allure.run("case-2", () -> AllureWireMockVerifyInstrumentation.onResetAll(server));
            String expected = "WireMock: сброс заглушек (:" + server.port() + ")";
            // каждый кейс обязан получить СВОЙ шаг сброса; мутация «убрать resetKeys.clear()» →
            // у case2 шага нет (сервер «уже сброшен» из case1) → RED — это ловит инвертированный баг:
            // молча пропавший шаг сброса в каждом следующем тесте на переиспользуемом сервере (@SpringBootTest)
            assertThat(stepNames(case1)).contains(expected);
            assertThat(stepNames(case2)).contains(expected);
        } finally {
            server.stop();
        }
    }

    @Test
    @DisplayName("статический WireMock.reset() (старый DSL) тоже даёт шаг сброса")
    void logsStaticReset() {
        TestResult result = allure.run("static-reset", AllureWireMockVerifyInstrumentation::onStaticReset);

        assertThat(allure.hasStep(result, "WireMock: сброс заглушек")).isTrue();
    }

    @Test
    @DisplayName("частичный сброс на инстансе (resetRequests) даёт шаг с именем метода")
    void logsPartialReset() {
        // server=null: снимок near-miss пропускается, проверяем сам шаг частичного сброса
        TestResult result = allure.run("partial",
                () -> AllureWireMockVerifyInstrumentation.onPartialReset(null, "resetRequests"));

        assertThat(allure.hasStep(result, "WireMock: частичный сброс (resetRequests)")).isTrue();
    }

    @Test
    @DisplayName("частичный сброс через статический DSL (resetAllRequests) тоже даёт шаг")
    void logsStaticPartialReset() {
        TestResult result = allure.run("static-partial",
                () -> AllureWireMockVerifyInstrumentation.onStaticPartialReset("resetAllRequests"));

        assertThat(allure.hasStep(result, "WireMock: частичный сброс (resetAllRequests)")).isTrue();
    }

    @Test
    @DisplayName("stubFor: шаг «Создана заглушка …» с вложением WireMock Stub")
    void logsStub() {
        StubMapping stub = get(urlPathEqualTo("/api/prices"))
                .willReturn(okJson("{\"price\":9.99}")).build();

        TestResult result = allure.run("stub", () ->
                AllureWireMockVerifyInstrumentation.onStub(stub));

        assertThat(allure.hasStep(result, "Создана заглушка: GET /api/prices → 200")).isTrue();
        // содержимое вложения, а не только наличие: url и тело ответа из самого стаба
        assertThat(allure.attachment(result, "WireMock Stub").orElseThrow())
                .contains("/api/prices").contains("9.99");
    }

    @Test
    @DisplayName("onResetAll снимает near-miss и состояние сценария ДО сброса (самое хрупкое место)")
    void resetAllSnapshotsNearMissAndScenario() throws Exception {
        WireMockServer server = new WireMockServer(options().dynamicPort());
        server.start();
        try {
            server.stubFor(get(urlPathEqualTo("/api/prices")).willReturn(okJson("{\"price\":1}")));
            server.stubFor(get(urlPathEqualTo("/api/flaky")).inScenario("retry")
                    .whenScenarioStateIs(Scenario.STARTED)
                    .willReturn(aResponse().withStatus(503)).willSetStateTo("recovered"));
            // незаматченный запрос → WireMock запишет near-miss (ближайший стаб)
            HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create(server.baseUrl() + "/api/wrong")).build(),
                    HttpResponse.BodyHandlers.ofString());

            // onResetAll должен СНЯТЬ near-miss и состояние сценария ДО сброса (иначе reset их стирает)
            TestResult result = allure.run("reset-snap", () ->
                    AllureWireMockVerifyInstrumentation.onResetAll(server));

            assertThat(stepNames(result)).anyMatch(n -> n.startsWith("Near-miss:") && n.contains("/api/wrong"));
            assertThat(stepNames(result)).anyMatch(n -> n.contains("сценарий") && n.contains("retry"));
            // имя шага сброса теперь несёт порт сервера: «WireMock: сброс заглушек (:<port>)»
            assertThat(stepNames(result)).contains("WireMock: сброс заглушек (:" + server.port() + ")");

            // near-miss — ИНФОРМАЦИОННЫЙ PASSED-шаг (тест им не роняем) и несёт diff во вложении
            StepResult nearMiss = result.getSteps().stream()
                    .filter(s -> s.getName().startsWith("Near-miss:")).findFirst().orElseThrow();
            assertThat(nearMiss.getStatus()).isEqualTo(Status.PASSED);
            // isNotBlank() мало: диф WireMock — колоночная простыня, и вся его польза в переносах
            // строк. Рендер через safe() (схлопывание в одну строку + обрезка) оставлял вложение
            // «непустым», поэтому такую деградацию не видит ни инвентарь отчёта, ни isNotBlank.
            String diff = allure.attachment(result, "Near miss (почему не сматчилось)").orElseThrow();
            assertThat(diff).isNotBlank();
            assertThat(diff.lines().count())
                    .as("диф near-miss обязан остаться многострочным, а не схлопнуться: <%s>", diff)
                    .isGreaterThan(1);
            // SOURCE вложения (имя файла на диске), а не только контент: по конвенции Allure
            // <uuid>-attachment.<ext>. Мутация: верни в writeAttachment голый UUID без суффикса →
            // внешние потребители results (TestOps/ReportPortal) не определят тип → RED.
            String source = nearMiss.getAttachments().stream()
                    .filter(a -> "Near miss (почему не сматчилось)".equals(a.getName()))
                    .map(Attachment::getSource).findFirst().orElseThrow();
            assertThat(source).endsWith("-attachment.txt");
        } finally {
            server.stop();
        }
    }
}
