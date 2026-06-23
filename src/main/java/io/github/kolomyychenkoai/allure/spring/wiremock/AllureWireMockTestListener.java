package io.github.kolomyychenkoai.allure.spring.wiremock;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.github.kolomyychenkoai.allure.spring.internal.AllureInstrumentation;
import io.github.kolomyychenkoai.allure.spring.internal.AllureSpringSettings;
import org.springframework.core.Ordered;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.TestExecutionListener;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Находит {@code WireMockServer} в тест-классе рефлексией (в static И instance полях, по всей
 * цепочке наследования) и вешает {@link AllureWireMockListener} для логирования запросов/ответов.
 * Заглушки/verify/resetAll ловятся байткодом — см. {@link AllureWireMockVerifyInstrumentation}.
 * Код в тестах не нужен. Регистрируется через {@code META-INF/spring.factories}.
 * <p>
 * Перед установкой байткода проверяется {@link AllureInstrumentation#available()} — если
 * byte-buddy нет на classpath, тихий no-op. Выключить — system property
 * {@code allure.spring.wiremock.enabled=false}. Если WireMock нет — матчер ничего не находит.
 * <p>
 * near-miss и состояния сценариев снимаются в {@code afterTestMethod} для тестов БЕЗ
 * {@code resetAll()}; для тестов С {@code resetAll()} они уже сняты в reset-advice ДО сброса
 * (тогда в afterTestMethod сервер пуст — без дублей).
 */
public class AllureWireMockTestListener implements TestExecutionListener, Ordered {

    // По каким серверам уже повешен request-listener. Identity-семантика (WireMockServer не
    // переопределяет equals) + weak-ключи, чтобы не держать сервера всю жизнь JVM.
    private static final Set<WireMockServer> REGISTERED =
            Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public void beforeTestClass(TestContext testContext) {
        if (!AllureInstrumentation.available()
                || !AllureSpringSettings.enabled(AllureSpringSettings.WIREMOCK_ENABLED)) {
            return;
        }
        // verify()/resetAll/stubFor нет listener-хука — ставим байткод-инструментирование один раз
        AllureWireMockVerifyInstrumentation.install();
    }

    @Override
    public void beforeTestMethod(TestContext testContext) {
        if (!AllureSpringSettings.enabled(AllureSpringSettings.WIREMOCK_ENABLED)) {
            return;
        }
        AllureWireMockListener.clear();
        for (WireMockServer server : findServers(testContext)) {
            if (REGISTERED.add(server)) {
                server.addMockServiceRequestListener(AllureWireMockListener::onRequestReceived);
            }
        }
    }

    @Override
    public void afterTestMethod(TestContext testContext) {
        if (!AllureSpringSettings.enabled(AllureSpringSettings.WIREMOCK_ENABLED)) {
            return;
        }
        AllureWireMockListener.flushToAllure();
        // near-miss/сценарии для тестов без resetAll (с resetAll они сняты в reset-advice до сброса)
        for (WireMockServer server : findServers(testContext)) {
            AllureWireMockSteps.nearMisses(server);
            AllureWireMockSteps.scenarios(server);
        }
    }

    /** Поиск WireMockServer и в static, и в instance полях (по всей иерархии), без дублей по identity. */
    // package-private (не private) — на этот матчинг (статик/instance/иерархия/notRunning/null/identity)
    // висят прямые тесты уровня A; это самое хрупкое место листенера.
    List<WireMockServer> findServers(TestContext testContext) {
        Set<WireMockServer> seen = Collections.newSetFromMap(new IdentityHashMap<>());
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
                    if (server != null && server.isRunning() && seen.add(server)) {
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
}
