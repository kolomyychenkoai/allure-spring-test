package io.github.kolomyychenkoai.allure.spring.wiremock;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.github.kolomyychenkoai.allure.spring.support.InMemoryAllure;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestContext;

import java.util.ArrayList;
import java.util.List;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

/**
 * Уровень A: самое хрупкое в листенере — поиск {@code WireMockServer} рефлексией
 * (static/instance/иерархия/notRunning/null/identity-дедуп) и гейт активного тест-кейса.
 * Каждый тест падает при регрессии соответствующей ветки {@code findServers}.
 * <p>
 * Все взаимодействия с Mockito-моком {@link TestContext} идут под InMemoryAllure БЕЗ
 * активного кейса — иначе наш MockMaker залогировал бы шаг с рандомным hashCode мока
 * (недетерминизм в отчёте).
 */
class AllureWireMockTestListenerTest {

    private final AllureWireMockTestListener listener = new AllureWireMockTestListener();
    private final List<WireMockServer> started = new ArrayList<>();
    private InMemoryAllure allure;

    @BeforeEach
    void setUp() {
        allure = new InMemoryAllure().install(); // установлен, но run() не зовём → активного кейса нет
    }

    @AfterEach
    void tearDown() {
        allure.uninstall();
        started.forEach(WireMockServer::stop);
        WithStatic.SERVER = null;
    }

    private WireMockServer running() {
        WireMockServer server = new WireMockServer(options().dynamicPort());
        server.start();
        started.add(server);
        return server;
    }

    private TestContext context(Class<?> testClass, Object instance) {
        TestContext ctx = mock(TestContext.class);
        doReturn(testClass).when(ctx).getTestClass();
        doReturn(instance).when(ctx).getTestInstance();
        return ctx;
    }

    @Test
    @DisplayName("находит сервер в static-поле")
    void findsStaticField() {
        WithStatic.SERVER = running();
        assertThat(listener.findServers(context(WithStatic.class, null)))
                .containsExactly(WithStatic.SERVER);
    }

    @Test
    @DisplayName("находит сервер в instance-поле")
    void findsInstanceField() {
        WithInstance instance = new WithInstance();
        instance.server = running();
        assertThat(listener.findServers(context(WithInstance.class, instance)))
                .containsExactly(instance.server);
    }

    @Test
    @DisplayName("находит сервер в поле суперкласса")
    void findsSuperclassField() {
        Child instance = new Child();
        instance.base = running();
        assertThat(listener.findServers(context(Child.class, instance)))
                .containsExactly(instance.base);
    }

    @Test
    @DisplayName("не берёт остановленный сервер (isRunning=false)")
    void skipsNotRunning() {
        WithInstance instance = new WithInstance();
        instance.server = new WireMockServer(options().dynamicPort()); // не start()
        assertThat(listener.findServers(context(WithInstance.class, instance))).isEmpty();
    }

    @Test
    @DisplayName("пропускает null-поле")
    void skipsNullField() {
        WithInstance instance = new WithInstance(); // server == null
        assertThat(listener.findServers(context(WithInstance.class, instance))).isEmpty();
    }

    @Test
    @DisplayName("один и тот же сервер в двух полях — без дублей по identity")
    void dedupsByIdentity() {
        TwoFields instance = new TwoFields();
        instance.a = running();
        instance.b = instance.a; // тот же объект
        assertThat(listener.findServers(context(TwoFields.class, instance)))
                .containsExactly(instance.a);
    }

    @Test
    @DisplayName("instance-поле без экземпляра (instance=null) — пропускается, не падает")
    void skipsInstanceFieldWhenNoInstance() {
        assertThat(listener.findServers(context(WithInstance.class, null))).isEmpty();
    }

    @Test
    @DisplayName("без активного тест-кейса before/afterTestMethod не пишут в отчёт и не падают")
    void lifecycleHooksWithoutActiveCaseDoNotThrow() throws Exception {
        WireMockServer server = running();
        WithStatic.SERVER = server;
        // реальный near-miss: незаматченный запрос → afterTestMethod ПОПЫТАЛСЯ бы выложить его шагом+вложением,
        // если бы не гейт активного кейса. Без него (run() не звали) запись в отчёт идти НЕ должна.
        server.stubFor(com.github.tomakehurst.wiremock.client.WireMock.get(
                com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo("/api/prices"))
                .willReturn(com.github.tomakehurst.wiremock.client.WireMock.okJson("{}")));
        java.net.http.HttpClient.newHttpClient().send(
                java.net.http.HttpRequest.newBuilder(java.net.URI.create(server.baseUrl() + "/api/wrong")).build(),
                java.net.http.HttpResponse.BodyHandlers.ofString());

        TestContext ctx = context(WithStatic.class, null);
        assertThatCode(() -> {
            listener.beforeTestMethod(ctx);
            listener.afterTestMethod(ctx);
        }).doesNotThrowAnyException();

        // не просто «не падает»: без активного кейса в отчёт не должно уйти НИЧЕГО (ни шага, ни байтов вложения).
        // Мутация: убери гейт active() в AllureWireMockSteps.nearMisses → near-miss-вложение запишется → RED.
        assertThat(allure.wroteNothing()).isTrue();
    }

    // --- фикстуры с полями WireMockServer ---
    static class WithStatic {
        static WireMockServer SERVER;
    }

    static class WithInstance {
        WireMockServer server;
    }

    static class Base {
        WireMockServer base;
    }

    static class Child extends Base {
    }

    static class TwoFields {
        WireMockServer a;
        WireMockServer b;
    }
}
