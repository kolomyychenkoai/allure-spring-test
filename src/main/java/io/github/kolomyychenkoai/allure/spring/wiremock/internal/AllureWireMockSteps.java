package io.github.kolomyychenkoai.allure.spring.wiremock.internal;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.github.tomakehurst.wiremock.stubbing.StubMapping;
import com.github.tomakehurst.wiremock.verification.NearMiss;
import io.github.kolomyychenkoai.allure.spring.internal.AllureAdviceSupport;
import io.github.kolomyychenkoai.allure.spring.internal.AllureInstrumentationLogger;
import io.qameta.allure.Allure;
import io.qameta.allure.model.Attachment;
import io.qameta.allure.model.Status;
import io.qameta.allure.model.StepResult;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Общие шаги Allure-отчёта для WireMock-модуля: заглушки, near-miss, состояния сценариев.
 * Вызывается и из listener'а ({@code AllureWireMockTestListener#afterTestMethod}), и из
 * байткод-advice ({@code stubFor}/{@code resetAll}) — чтобы шаги попадали в отчёт даже когда
 * тест в конце зовёт {@code resetAll()} (сброс стирает стабы/журнал/сценарии). НЕ публичный API.
 * Всё под проверкой активного тест-кейса и в try/catch — инструментирование не роняет тест.
 */
public final class AllureWireMockSteps {

    private AllureWireMockSteps() {
    }

    static boolean active() {
        return Allure.getLifecycle().getCurrentTestCase().isPresent();
    }

    /**
     * Живой шаг «Создана заглушка …» в момент {@code stubFor} — верный хронологический
     * порядок, и шаг переживает последующий {@code resetAll()}.
     */
    static void stub(StubMapping stub) {
        try {
            if (stub == null || !active()) {
                return;
            }
            int status = stub.getResponse() != null ? stub.getResponse().getStatus() : 200;
            final String body = stubJson(stub);
            Allure.step("Создана заглушка: " + stubRequestLine(stub) + " → " + status, step -> {
                Allure.addAttachment("WireMock Stub", "application/json", body);
            });
        } catch (Throwable t) {
            AllureInstrumentationLogger.warn("WireMockStub", t);
        }
    }

    /**
     * Near-miss для незаматченных запросов. Вызывать ПЕРЕД {@code resetAll}, иначе журнал стёрт.
     * Шаг ИНФОРМАЦИОННЫЙ (PASSED): сам по себе near-miss тест не роняет — это диагностика
     * «почему запрос не сматчился» (diff во вложении). Красить в BROKEN без падающего теста
     * не надо (§ стандарта: не фабрикуем failed-узлы; падение, если оно есть, покажет Allure).
     */
    public static void nearMisses(WireMockServer server) {
        try {
            if (server == null || !active()) {
                return;
            }
            for (NearMiss nearMiss : server.findNearMissesForAllUnmatchedRequests()) {
                String method = "";
                String url = "";
                if (nearMiss.getRequest() != null) {
                    method = nearMiss.getRequest().getMethod().getName();
                    url = nearMiss.getRequest().getUrl();
                }
                String candidate = stubRequestLine(nearMiss.getStubMapping());
                StepResult step = new StepResult()
                        .setName("Near-miss: " + method + " " + url + " ≉ заглушка " + candidate)
                        .setStatus(Status.PASSED);
                step.getAttachments().add(new Attachment()
                        .setName("Near miss (почему не сматчилось)")
                        .setType("text/plain")
                        .setSource(writeAttachment(AllureAdviceSupport.safe(nearMiss.getDiff()))));
                addStep(step);
            }
        } catch (Throwable t) {
            AllureInstrumentationLogger.warn("WireMockNearMiss", t);
        }
    }

    /** Итоговые состояния сценариев (stateful-заглушки). Вызывать ПЕРЕД {@code resetAll}. */
    public static void scenarios(WireMockServer server) {
        try {
            if (server == null || !active()) {
                return;
            }
            for (Scenario scenario : server.getAllScenarios().getScenarios()) {
                addStep(new StepResult()
                        .setName("WireMock сценарий «" + scenario.getName() + "»: состояние " + scenario.getState())
                        .setStatus(Status.PASSED));
            }
        } catch (Throwable t) {
            AllureInstrumentationLogger.warn("WireMockScenario", t);
        }
    }

    /** «METHOD url» из стаба (для имени шага и near-miss кандидата). */
    static String stubRequestLine(StubMapping stub) {
        if (stub == null || stub.getRequest() == null) {
            return "";
        }
        String method = stub.getRequest().getMethod() != null
                ? stub.getRequest().getMethod().getName() : "";
        String url = firstNonNull(
                stub.getRequest().getUrlPathPattern(),
                stub.getRequest().getUrlPath(),
                stub.getRequest().getUrl(),
                stub.getRequest().getUrlPattern());
        return (method + " " + url).trim();
    }

    private static void addStep(StepResult step) {
        Allure.getLifecycle().updateTestCase(tr -> tr.getSteps().add(step));
    }

    private static String stubJson(StubMapping stub) {
        try {
            return stub.toString(); // StubMapping.toString() = полный JSON правила
        } catch (Throwable t) {
            return "{}";
        }
    }

    private static String firstNonNull(String... values) {
        for (String v : values) {
            if (v != null) {
                return v;
            }
        }
        return "";
    }

    private static String writeAttachment(String content) {
        String source = UUID.randomUUID().toString();
        Allure.getLifecycle().writeAttachment(source,
                new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)));
        return source;
    }
}
