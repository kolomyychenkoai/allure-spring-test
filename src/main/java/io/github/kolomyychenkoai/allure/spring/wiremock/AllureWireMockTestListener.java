package io.github.kolomyychenkoai.allure.spring.wiremock;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.stubbing.StubMapping;
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
        String method = "";
        String url = "";
        if (stub.getRequest() != null) {
            if (stub.getRequest().getMethod() != null) {
                method = stub.getRequest().getMethod().getName();
            }
            url = firstNonNull(
                    stub.getRequest().getUrlPathPattern(),
                    stub.getRequest().getUrlPath(),
                    stub.getRequest().getUrl(),
                    stub.getRequest().getUrlPattern());
        }
        int status = stub.getResponse() != null ? stub.getResponse().getStatus() : 200;

        StepResult step = new StepResult()
                .setName("Создана заглушка: " + method + " " + url + " → " + status)
                .setStatus(Status.PASSED);
        step.getAttachments().add(new Attachment()
                .setName("WireMock Stub")
                .setType("text/plain")
                .setSource(writeAttachment(formatStub(stub, method, url, status))));
        return step;
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

    private String formatStub(StubMapping stub, String method, String url, int status) {
        StringBuilder sb = new StringBuilder();
        sb.append("Request:\n  Method: ").append(method).append("\n  URL: ").append(url).append('\n');
        if (stub.getRequest() != null && stub.getRequest().getQueryParameters() != null) {
            stub.getRequest().getQueryParameters().forEach((key, pattern) -> {
                String value;
                try {
                    value = pattern.getExpected();
                } catch (Exception e) {
                    value = key;
                }
                sb.append("  Query: ").append(key).append(" = ").append(value).append('\n');
            });
        }
        sb.append("\nResponse:\n  Status: ").append(status).append('\n');
        if (stub.getResponse() != null) {
            if (stub.getResponse().getBody() != null) {
                sb.append("  Body: ").append(stub.getResponse().getBody()).append('\n');
            }
            if (stub.getResponse().getFixedDelayMilliseconds() != null) {
                sb.append("  Delay: ").append(stub.getResponse().getFixedDelayMilliseconds()).append("ms\n");
            }
        }
        // полное правило целиком — чтобы НИЧЕГО не терять: матчеры по заголовкам/телу,
        // заголовки ответа, scenario, fault, проксирование и пр. (StubMapping.toString() = JSON)
        try {
            sb.append("\nFull mapping (JSON):\n").append(stub.toString()).append('\n');
        } catch (Throwable ignored) {
            // полный дамп не критичен — читаемая часть выше уже есть
        }
        return sb.toString();
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
