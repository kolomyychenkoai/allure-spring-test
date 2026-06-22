package io.github.kolomyychenkoai.allure.spring.wiremock;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.github.tomakehurst.wiremock.stubbing.StubMapping;
import com.github.tomakehurst.wiremock.verification.NearMiss;
import io.qameta.allure.Allure;
import io.qameta.allure.model.Attachment;
import io.qameta.allure.model.Status;
import io.qameta.allure.model.StepResult;
import org.springframework.core.Ordered;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.TestExecutionListener;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Находит {@code WireMockServer} в тест-классе рефлексией (в static И instance полях,
 * по всей цепочке наследования) и:
 * <ul>
 *   <li>вешает {@link AllureWireMockListener} для логирования запросов/ответов;</li>
 *   <li>добавляет зарегистрированные стабы шагами «WireMock stub …» в начало теста.</li>
 * </ul>
 * Код в тестах не нужен. Регистрируется через {@code META-INF/spring.factories};
 * если WireMock нет на classpath, Spring пропустит этот листенер.
 */
public class AllureWireMockTestListener implements TestExecutionListener, Ordered {

    private static final Set<Integer> REGISTERED = ConcurrentHashMap.newKeySet();
    private static final String STUBS_BEFORE = AllureWireMockTestListener.class.getName() + ".stubsBefore";

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public void beforeTestClass(TestContext testContext) {
        // verify()/resetAll нет listener-хука — ставим байткод-инструментирование один раз
        AllureWireMockVerifyInstrumentation.install();
    }

    @Override
    public void beforeTestMethod(TestContext testContext) {
        AllureWireMockListener.clear();
        Set<UUID> stubsBefore = new HashSet<>();
        for (WireMockServer server : findServers(testContext)) {
            if (REGISTERED.add(System.identityHashCode(server))) {
                server.addMockServiceRequestListener(AllureWireMockListener::onRequestReceived);
            }
            for (StubMapping stub : server.getStubMappings()) {
                stubsBefore.add(stub.getId());
            }
        }
        // запомним стабы, что были ДО теста — в отчёт покажем только добавленные тестом
        testContext.setAttribute(STUBS_BEFORE, stubsBefore);
    }

    @Override
    public void afterTestMethod(TestContext testContext) {
        AllureWireMockListener.flushToAllure();
        prependStubSteps(testContext);
        appendNearMisses(testContext);
        appendScenarios(testContext);
    }

    /** Для незаматченных запросов — почему не сматчилось (ближайший стаб + diff). */
    private void appendNearMisses(TestContext testContext) {
        try {
            if (!Allure.getLifecycle().getCurrentTestCase().isPresent()) {
                return;
            }
            for (WireMockServer server : findServers(testContext)) {
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
                            .setStatus(Status.BROKEN);
                    step.getAttachments().add(new Attachment()
                            .setName("Near miss (почему не сматчилось)")
                            .setType("text/plain")
                            .setSource(writeAttachment(String.valueOf(nearMiss.getDiff()))));
                    addStep(step);
                }
            }
        } catch (Throwable ignored) {
            // инструментирование не должно ронять тест
        }
    }

    /** Итоговые состояния сценариев (stateful-заглушки). */
    private void appendScenarios(TestContext testContext) {
        try {
            if (!Allure.getLifecycle().getCurrentTestCase().isPresent()) {
                return;
            }
            for (WireMockServer server : findServers(testContext)) {
                for (Scenario scenario : server.getAllScenarios().getScenarios()) {
                    addStep(new StepResult()
                            .setName("WireMock сценарий «" + scenario.getName() + "»: состояние "
                                    + scenario.getState())
                            .setStatus(Status.PASSED));
                }
            }
        } catch (Throwable ignored) {
            // инструментирование не должно ронять тест
        }
    }

    private void addStep(StepResult step) {
        Allure.getLifecycle().updateTestCase(tr -> tr.getSteps().add(step));
    }

    @SuppressWarnings("unchecked")
    private void prependStubSteps(TestContext testContext) {
        try {
            if (!Allure.getLifecycle().getCurrentTestCase().isPresent()) {
                return;
            }
            Object beforeAttr = testContext.getAttribute(STUBS_BEFORE);
            Set<UUID> stubsBefore = (beforeAttr instanceof Set) ? (Set<UUID>) beforeAttr : Set.of();
            List<StepResult> stubSteps = new ArrayList<>();
            for (WireMockServer server : findServers(testContext)) {
                for (StubMapping stub : server.getStubMappings()) {
                    if (!stubsBefore.contains(stub.getId())) { // только стабы, добавленные тестом
                        stubSteps.add(buildStubStep(stub));
                    }
                }
            }
            if (!stubSteps.isEmpty()) {
                Allure.getLifecycle().updateTestCase(tr -> tr.getSteps().addAll(0, stubSteps));
            }
        } catch (Throwable ignored) {
            // инструментирование не должно ронять тест
        }
    }

    private StepResult buildStubStep(StubMapping stub) {
        int status = stub.getResponse() != null ? stub.getResponse().getStatus() : 200;

        StepResult step = new StepResult()
                .setName("Создана заглушка: " + stubRequestLine(stub) + " → " + status)
                .setStatus(Status.PASSED);
        step.getAttachments().add(new Attachment()
                .setName("WireMock Stub")
                .setType("application/json")
                .setSource(writeAttachment(stubJson(stub))));
        return step;
    }

    private static String stubJson(StubMapping stub) {
        try {
            return stub.toString(); // StubMapping.toString() = полный JSON правила
        } catch (Throwable t) {
            return "{}";
        }
    }

    private String writeAttachment(String content) {
        String source = UUID.randomUUID().toString();
        Allure.getLifecycle().writeAttachment(source,
                new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)));
        return source;
    }

    /** Поиск WireMockServer и в static, и в instance полях (по всей иерархии). */
    private List<WireMockServer> findServers(TestContext testContext) {
        List<WireMockServer> servers = new ArrayList<>();
        Object instance = safeInstance(testContext);
        Class<?> c = testContext.getTestClass();
        while (c != null && c != Object.class) {
            for (Field field : c.getDeclaredFields()) {
                if (!WireMockServer.class.isAssignableFrom(field.getType())) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    boolean isStatic = Modifier.isStatic(field.getModifiers());
                    if (!isStatic && instance == null) {
                        continue; // instance-поле, но экземпляра нет — пропускаем
                    }
                    WireMockServer server = (WireMockServer) field.get(isStatic ? null : instance);
                    if (server != null && server.isRunning()) {
                        servers.add(server);
                    }
                } catch (Throwable ignored) {
                    // недоступное поле — пропускаем
                }
            }
            c = c.getSuperclass();
        }
        return servers;
    }

    private static Object safeInstance(TestContext testContext) {
        try {
            return testContext.getTestInstance();
        } catch (Throwable e) {
            return null;
        }
    }

    /** «METHOD url» из стаба (для имени шага и near-miss кандидата). */
    private String stubRequestLine(StubMapping stub) {
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

    private static String firstNonNull(String... values) {
        for (String v : values) {
            if (v != null) {
                return v;
            }
        }
        return "";
    }
}
