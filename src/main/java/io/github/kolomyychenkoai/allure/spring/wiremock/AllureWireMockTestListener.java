package io.github.kolomyychenkoai.allure.spring.wiremock;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.github.kolomyychenkoai.allure.spring.internal.AllureInstrumentation;
import io.github.kolomyychenkoai.allure.spring.internal.ByteBuddyPresence;
import io.github.kolomyychenkoai.allure.spring.internal.ClassPresence;
import io.github.kolomyychenkoai.allure.spring.wiremock.internal.AllureWireMockListener;
import io.github.kolomyychenkoai.allure.spring.wiremock.internal.AllureWireMockSteps;
import io.github.kolomyychenkoai.allure.spring.wiremock.internal.AllureWireMockVerifyInstrumentation;
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
 * Для каждого найденного запущенного сервера в {@code beforeTestMethod} рисует шаг «WireMock: сервер
 * поднят (:port)» ({@link AllureWireMockSteps#serverUp}) — в НАЧАЛЕ теста, per-test (без протечки).
 * Заглушки/verify/resetAll ловятся байткодом — см. {@link AllureWireMockVerifyInstrumentation}.
 * Код в тестах не нужен. Регистрируется через {@code META-INF/spring.factories}.
 * <p>
 * Перед установкой байткода проверяется {@link ByteBuddyPresence#available()} — если
 * byte-buddy нет на classpath, тихий no-op. Если WireMock нет — матчер ничего не находит.
 * <p>
 * near-miss и состояния сценариев снимаются в {@code afterTestMethod} для тестов БЕЗ
 * {@code resetAll()}; для тестов С {@code resetAll()} они уже сняты в reset-advice ДО сброса
 * (тогда в afterTestMethod сервер пуст — без дублей).
 * <p>
 * <b>Как находим сервер</b> (в порядке поиска): поля тест-класса и его предков — по ЗНАЧЕНИЮ,
 * с разворотом чужого объекта на один уровень (так достаётся сервер внутри
 * {@code WireMockExtension} из {@code @RegisterExtension}); затем бины контекста (так достаётся
 * {@code @AutoConfigureWireMock} из Spring Cloud Contract).
 * <p>
 * <b>Граница:</b> декларативный {@code @WireMockTest} не поддержан — там сервер живёт в
 * {@code ExtensionContext.Store} JUnit, ни полем, ни бином его не достать. Отвергнут и вариант
 * «перехват {@code WireMockServer.start()} + JVM-глобальный реестр»: у сервера в реестре нет
 * владельца, и статический сервер одного класса протекал бы шагами во все последующие.
 */
public class AllureWireMockTestListener implements TestExecutionListener, Ordered {

    // WireMock в scope provided — у потребителя его может не быть. Листенер регистрируется
    // всегда (spring.factories), поэтому БЕЗ этого гейта прямое обращение к WireMockServer
    // в хуках дало бы NoClassDefFoundError и уронило бы тест потребителя. См. ClassPresence.
    private static final boolean WIREMOCK_PRESENT =
            ClassPresence.isPresent("com.github.tomakehurst.wiremock.WireMockServer");

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
        if (!WIREMOCK_PRESENT || !ByteBuddyPresence.available()) {
            return;
        }
        // verify()/resetAll/stubFor нет listener-хука — ставим байткод-инструментирование один раз
        AllureWireMockVerifyInstrumentation.install();
    }

    @Override
    public void beforeTestMethod(TestContext testContext) {
        if (!WIREMOCK_PRESENT) {
            return;
        }
        AllureWireMockListener.clear();
        for (WireMockServer server : findServers(testContext)) {
            AllureWireMockSteps.serverUp(server); // шаг «сервер поднят (:port)» — в начале теста
            if (REGISTERED.add(server)) {
                server.addMockServiceRequestListener(AllureWireMockListener::onRequestReceived);
            }
        }
    }

    @Override
    public void afterTestMethod(TestContext testContext) {
        if (!WIREMOCK_PRESENT) {
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
                try {
                    field.setAccessible(true);
                    boolean isStatic = Modifier.isStatic(field.getModifiers());
                    if (!isStatic && instance == null) {
                        continue; // instance-поле, но экземпляра нет — пропускаем
                    }
                    // Фильтруем по ЗНАЧЕНИЮ, а не по объявленному типу поля: WireMockExtension
                    // (@RegisterExtension) наследует DslWrapper, а не WireMockServer, и держит
                    // сервер внутри — по типу поля он не находится, и модуль замолчал бы на нём.
                    collect(field.get(isStatic ? null : instance), servers, seen, 1);
                } catch (Throwable ignored) {
                    // недоступное поле — пропускаем
                }
            }
            c = c.getSuperclass();
        }
        // @AutoConfigureWireMock (Spring Cloud Contract) держит сервер БИНОМ, а не полем теста
        for (WireMockServer bean : beans(testContext)) {
            add(bean, servers, seen);
        }
        return servers;
    }

    /**
     * Сервер — либо сам объект, либо ОДИН уровень вглубь чужого (у {@code WireMockExtension} это
     * приватное {@code wireMockServer} плюс унаследованные {@code admin}/{@code stubbing} — все три
     * ссылаются на один экземпляр, дубли гасит identity-дедуп).
     * <p>
     * Глубже НЕ идём осознанно: обход графа объектов дорог и способен разбудить ленивые прокси.
     * Пакеты JDK пропускаем — там серверов нет, а полей много.
     * <p>
     * <b>Стоимость замерена:</b> на полном сьюте 90 вызовов, 457 чтений полей,
     * 7,8 мс СУММАРНО — около 5 полей и 86 мкс на вызов. Дёшево, потому что сканируются поля
     * ТЕСТ-КЛАССА (их единицы), а вглубь идём ровно на один уровень. Сужать по префиксу пакета
     * не стали: выигрыш в пределах шума, а любой фильтр рискует потерять сервер в чужой обёртке.
     */
    private void collect(Object value, List<WireMockServer> servers, Set<WireMockServer> seen, int depth) {
        if (value == null) {
            return;
        }
        if (value instanceof WireMockServer server) {
            add(server, servers, seen);
            return;
        }
        if (depth == 0 || value.getClass().getName().startsWith("java.")
                || value.getClass().getName().startsWith("jdk.")) {
            return;
        }
        for (Class<?> c = value.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field field : c.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    collect(field.get(value), servers, seen, depth - 1);
                } catch (Throwable ignored) {
                    // недоступное поле — пропускаем
                }
            }
        }
    }

    private void add(WireMockServer server, List<WireMockServer> servers, Set<WireMockServer> seen) {
        try {
            if (server.isRunning() && seen.add(server)) {
                servers.add(server);
            }
        } catch (Throwable ignored) {
            // сервер в непонятном состоянии — пропускаем
        }
    }

    private static List<WireMockServer> beans(TestContext testContext) {
        // hasApplicationContext, а не getApplicationContext: наш afterTestMethod идёт ПОСЛЕДНИМ
        // (HIGHEST_PRECEDENCE = крайний с обеих сторон), то есть уже после того, как
        // @DirtiesContext закрыл и выселил контекст. Безусловный геттер ВОСКРЕСИЛ БЫ его —
        // лишняя полная загрузка контекста в чужом прогоне.
        if (!testContext.hasApplicationContext()) {
            return List.of();
        }
        try {
            return new ArrayList<>(testContext.getApplicationContext()
                    .getBeansOfType(WireMockServer.class).values());
        } catch (Throwable noContext) {
            return List.of(); // контекста у теста может не быть или он упал — серверов-бинов тогда нет
        }
    }

    private static Object safeInstance(TestContext testContext) {
        try {
            return testContext.getTestInstance();
        } catch (Throwable e) {
            return null;
        }
    }
}
