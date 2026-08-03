package io.github.kolomyychenkoai.allure.spring.canary;

import io.qameta.allure.Epic;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;


/**
 * Канарейки ВЕРСИОННЫХ ДОПУЩЕНИЙ инструментирования. Матчеры байткода заданы СТРОКАМИ
 * ({@code named("send")} и т.п.) — компилятор их не проверяет, поэтому при апгрейде чужой
 * библиотеки переименование/смена сигнатуры метода ломает перехват МОЛЧА (матчер просто
 * перестаёт совпадать, шаг исчезает из отчёта).
 * <p>
 * Этот тест — ЕДИНЫЙ инвентарь «что мы предполагаем про API каждой инструментируемой
 * библиотеки». При апгрейде он краснеет ТОЧЕЧНО, с указанием, какой матчер в каком
 * {@code *Instrumentation} обновить — человеку не нужно реверсить причину по вагуэ-падению IT.
 * <p>
 * Внутренние ПОЛЯ Mockito (рефлексия по приватным полям, иной механизм) канареятся
 * отдельно — см. {@code mock.AllureMockitoTest#mockitoInternalFieldsExist}.
 * Решение по самому хрупкому узлу (AssertJ) описано в {@code docs/adr/0001-assertj-instrumentation.md}.
 */
@Epic("Внутренние проверки библиотеки")
@DisplayName("Канарейки версионных допущений матчеров (апгрейд библиотек ломает молча)")
class InstrumentationApiCanaryTest {

    /**
     * Немой ассерт: голый throw вместо JUnit-ассерта. JUnit Jupiter Assertions ПЕРЕХВАЧЕНЫ
     * модулем assertion, поэтому обычный assertTrue сам стал бы шагом «Проверка: …» —
     * сотня строк девелоперского жаргона в отчёте, который принимают ручные тестировщики
     * (то же правило, что для *ReportIT: verify только немым каналом).
     */
    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    /** Есть ли у класса метод с именем (и опц. арностью {@code paramCount>=0} / типом arg0). */
    private static boolean hasMethod(String className, String method, int paramCount, String firstParamType) {
        try {
            Class<?> c = Class.forName(className);
            for (Method m : c.getMethods()) {
                if (!m.getName().equals(method)) {
                    continue;
                }
                if (paramCount >= 0 && m.getParameterCount() != paramCount) {
                    continue;
                }
                if (firstParamType != null && (m.getParameterCount() == 0
                        || !m.getParameterTypes()[0].getName().equals(firstParamType))) {
                    continue;
                }
                return true;
            }
            return false;
        } catch (ClassNotFoundException e) {
            return false; // класс уехал → матчер тоже мёртв
        }
    }

    /** Есть ли класс на classpath (для канареек на сам класс, а не его метод). */
    private static boolean classPresent(String className) {
        try {
            Class.forName(className);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    @Test
    @DisplayName("REST: MockMvc.perform и RestTemplate.getInterceptors (матчеры rest-модуля)")
    void restMatchers() {
        require(hasMethod("org.springframework.test.web.servlet.MockMvc", "perform", -1, null),
                "MockMvc.perform уехал → обнови матчер в AllureMockMvcInstrumentation");
        require(hasMethod("org.springframework.web.client.RestTemplate", "getInterceptors", 0, null),
                "RestTemplate.getInterceptors уехал → AllureRestTemplateInstrumentation вешает интерсептор через него");
        // Матчер вешает advice на ОБЪЯВИТЕЛЯ setInterceptors. ByteBuddy вплетает только в методы,
        // ОБЪЯВЛЕННЫЕ в трансформируемом типе, поэтому переезд метода между классами иерархии
        // превратил бы перехват в тихий no-op — красным это не станет само.
        require(declaredIn("org.springframework.web.client.RestTemplate", "setInterceptors", java.util.List.class,
                        "org.springframework.http.client.support.InterceptingHttpAccessor"),
                "setInterceptors объявлен уже не в InterceptingHttpAccessor → матчер "
                        + "AllureRestTemplateInstrumentation не сматчит ничего и станет тихим no-op");
    }

    @Test
    @DisplayName("RestClient: внутренний DefaultRestClientBuilder.build() (матчер RestClient-модуля)")
    void restClientMatchers() {
        // ВНИМАНИЕ: DefaultRestClientBuilder — package-private ВНУТРЕННИЙ класс Spring (не публичный
        // API). Самое хрупкое допущение ветки: при апгрейде Spring его могут переименовать/убрать молча.
        require(classPresent("org.springframework.web.client.DefaultRestClientBuilder"),
                "DefaultRestClientBuilder уехал (внутренний класс Spring!) → обнови матчер в AllureRestClientInstrumentation");
        require(hasMethod("org.springframework.web.client.DefaultRestClientBuilder", "build", 0, null),
                "DefaultRestClientBuilder.build() уехал → AllureRestClientInstrumentation вешает интерсептор в build()");
    }

    @Test
    @DisplayName("RestAssured: внутренний ValidatableResponseOptionsImpl и его проверки (матчер RA-валидации)")
    void restAssuredValidationMatchers() {
        // ВНИМАНИЕ: ValidatableResponseOptionsImpl — ВНУТРЕННИЙ класс RestAssured (io.restassured.internal,
        // без гарантий совместимости) и носитель всех перегрузок проверок .then(). Самое хрупкое допущение
        // ветки: апгрейд RestAssured может переименовать класс/метод молча → шаги «Проверка ответа» исчезнут.
        String vroi = "io.restassured.internal.ValidatableResponseOptionsImpl";
        require(classPresent(vroi),
                "ValidatableResponseOptionsImpl уехал (внутренний класс RestAssured!) → обнови матчер в AllureRestAssuredValidationInstrumentation");
        for (String method : new String[]{"statusCode", "statusLine", "body", "header", "headers",
                "cookie", "cookies", "contentType", "time"}) {
            require(hasMethod(vroi, method, -1, null),
                    "ValidatableResponseOptionsImpl." + method + " уехал → обнови список имён в AllureRestAssuredValidationInstrumentation");
        }
        // допущение исключения log-вариантов: body()/headers()/cookies() 0-арг ДОЛЖНЫ существовать
        // (иначе not(takesArguments(0)) отсекает несуществующее — а значит меняется семантика перегрузок)
        require(hasMethod(vroi, "body", 0, null),
                "body() 0-арг (log-вариант) уехал → пересмотри исключение not(takesArguments(0)) в AllureRestAssuredValidationInstrumentation");
    }

    @Test
    @DisplayName("JDBC: ключевые методы JdbcTemplate/NamedParameterJdbcTemplate (матчеры JDBC-модуля)")
    void jdbcMatchers() {
        String jt = "org.springframework.jdbc.core.JdbcTemplate";
        // полный инвентарь METHODS из AllureJdbcInstrumentation (не подмножество)
        for (String method : new String[]{"query", "queryForObject", "queryForList", "queryForMap",
                "queryForRowSet", "queryForStream", "update", "batchUpdate", "execute"}) {
            require(hasMethod(jt, method, -1, null),
                    "JdbcTemplate." + method + " уехал → обнови METHODS в AllureJdbcInstrumentation");
        }
        String njt = "org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate";
        require(hasMethod(njt, "update", -1, null),
                "NamedParameterJdbcTemplate.update уехал → обнови матчер в AllureJdbcInstrumentation");
        require(hasMethod(njt, "queryForObject", -1, null),
                "NamedParameterJdbcTemplate.queryForObject уехал → обнови матчер в AllureJdbcInstrumentation");
    }

    @Test
    @DisplayName("Liquibase: ChangeSet.execute(3-арг) + геттеры id/author/filePath/comments")
    void liquibaseMatchers() {
        String cs = "liquibase.changelog.ChangeSet";
        // Матчим ТОЛЬКО 3-арг execute(DatabaseChangeLog, ChangeExecListener, Database): 2-арг overload
        // делегирует в него (проверено на Liquibase 4.x) — одна точка покрывает старт и ручной update без дублей.
        require(hasMethod(cs, "execute", 3, null),
                "ChangeSet.execute(3-арг) уехал → обнови матчер в AllureLiquibaseInstrumentation");
        require(hasMethod(cs, "getId", 0, null), "ChangeSet.getId уехал → AllureLiquibaseInstrumentation.details");
        require(hasMethod(cs, "getAuthor", 0, null), "ChangeSet.getAuthor уехал → AllureLiquibaseInstrumentation.details");
        require(hasMethod(cs, "getFilePath", 0, null), "ChangeSet.getFilePath уехал → AllureLiquibaseInstrumentation.details");
        require(hasMethod(cs, "getComments", 0, null), "ChangeSet.getComments уехал → AllureLiquibaseInstrumentation.details");
    }

    @Test
    @DisplayName("Awaitility: SPI ConditionEvaluationListener + EvaluatedCondition-геттеры")
    void awaitilityMatchers() {
        require(hasMethod("org.awaitility.Awaitility", "setDefaultConditionEvaluationListener", 1, null),
                "Awaitility.setDefaultConditionEvaluationListener уехал → AllureAwaitilityListener регистрирует слушатель через него");
        require(classPresent("org.awaitility.core.ConditionEvaluationListener"),
                "ConditionEvaluationListener уехал → AllureAwaitilityConditionListener реализует этот SPI");
        String ec = "org.awaitility.core.EvaluatedCondition";
        require(hasMethod(ec, "isSatisfied", 0, null), "EvaluatedCondition.isSatisfied уехал → AllureAwaitilityConditionListener");
        require(hasMethod(ec, "getDescription", 0, null), "EvaluatedCondition.getDescription уехал → AllureAwaitilityConditionListener");
        require(hasMethod(ec, "getElapsedTimeInMS", 0, null), "EvaluatedCondition.getElapsedTimeInMS уехал → AllureAwaitilityConditionListener");
    }

    @Test
    @DisplayName("WireMock сбросы: static resetAllRequests/resetScenario/resetAllScenarios + WireMockServer resetMappings/resetRequests/resetScenarios")
    void wireMockResetMatchers() {
        String stat = "com.github.tomakehurst.wiremock.client.WireMock";
        require(hasMethod(stat, "resetAllRequests", -1, null), "WireMock.resetAllRequests уехал → AllureWireMockVerifyInstrumentation");
        require(hasMethod(stat, "resetScenario", -1, null), "WireMock.resetScenario уехал → AllureWireMockVerifyInstrumentation");
        require(hasMethod(stat, "resetAllScenarios", -1, null), "WireMock.resetAllScenarios уехал → AllureWireMockVerifyInstrumentation");
        String server = "com.github.tomakehurst.wiremock.WireMockServer";
        require(hasMethod(server, "resetMappings", -1, null), "WireMockServer.resetMappings уехал → AllureWireMockVerifyInstrumentation");
        require(hasMethod(server, "resetRequests", -1, null), "WireMockServer.resetRequests уехал → AllureWireMockVerifyInstrumentation");
        require(hasMethod(server, "resetScenarios", -1, null), "WireMockServer.resetScenarios уехал → AllureWireMockVerifyInstrumentation");
    }

    @Test
    @DisplayName("WireMock near-miss/сценарии: API снятия журнала ДО сброса (AllureWireMockSteps)")
    void wireMockNearMissApi() {
        String server = "com.github.tomakehurst.wiremock.WireMockServer";
        require(hasMethod(server, "findNearMissesForAllUnmatchedRequests", -1, null),
                "WireMockServer.findNearMissesForAllUnmatchedRequests уехал → AllureWireMockSteps.nearMisses");
        require(hasMethod(server, "getAllScenarios", -1, null),
                "WireMockServer.getAllScenarios уехал → AllureWireMockSteps.scenarios");
        String nearMiss = "com.github.tomakehurst.wiremock.verification.NearMiss";
        require(hasMethod(nearMiss, "getDiff", -1, null), "NearMiss.getDiff уехал → AllureWireMockSteps");
        require(hasMethod(nearMiss, "getRequest", -1, null), "NearMiss.getRequest уехал → AllureWireMockSteps");
        require(hasMethod(nearMiss, "getStubMapping", -1, null), "NearMiss.getStubMapping уехал → AllureWireMockSteps");
        String scenario = "com.github.tomakehurst.wiremock.stubbing.Scenario";
        require(hasMethod(scenario, "getName", -1, null), "Scenario.getName уехал → AllureWireMockSteps.scenarios");
        require(hasMethod(scenario, "getState", -1, null), "Scenario.getState уехал → AllureWireMockSteps.scenarios");
    }

    @Test
    @DisplayName("datasource-proxy: ExecutionInfo/QueryInfo + форма ParameterSetOperation (инлайн значений SQL)")
    void dataSourceProxyApi() {
        require(classPresent("net.ttddyy.dsproxy.ExecutionInfo"), "datasource-proxy ExecutionInfo уехал → AllureDataSourceListener");
        require(classPresent("net.ttddyy.dsproxy.QueryInfo"), "datasource-proxy QueryInfo уехал → AllureDataSourceListener");
        // футер вложения «✓/✗ · N мс»
        require(hasMethod("net.ttddyy.dsproxy.ExecutionInfo", "isSuccess", 0, null),
                "ExecutionInfo.isSuccess уехал → AllureDataSourceListener.renderQuery (футер ✓/✗)");
        require(hasMethod("net.ttddyy.dsproxy.ExecutionInfo", "getElapsedTime", 0, null),
                "ExecutionInfo.getElapsedTime уехал → AllureDataSourceListener.renderQuery (футер · N мс)");
        // связанные параметры: QueryInfo.getParametersList() → List<List<ParameterSetOperation>>, форма getArgs()=[index,value]
        require(hasMethod("net.ttddyy.dsproxy.QueryInfo", "getParametersList", 0, null),
                "QueryInfo.getParametersList уехал → AllureDataSourceListener.renderQuery (инлайн значений сломается молча)");
        require(hasMethod("net.ttddyy.dsproxy.proxy.ParameterSetOperation", "getArgs", 0, null),
                "ParameterSetOperation.getArgs уехал → AllureDataSourceListener.inlineParams (ожидаем форму [0]=index, [1]=value)");
        // канонические предикаты спец-параметров (иначе КОД типа java.sql.Types попал бы в отчёт как значение)
        String pso = "net.ttddyy.dsproxy.proxy.ParameterSetOperation";
        require(hasMethod(pso, "isSetNullParameterOperation", 1, pso),
                "ParameterSetOperation.isSetNullParameterOperation уехал → AllureDataSourceListener.renderParam");
        require(hasMethod(pso, "isRegisterOutParameterOperation", 1, pso),
                "ParameterSetOperation.isRegisterOutParameterOperation уехал → AllureDataSourceListener.inlineParams");
    }

    @Test
    @DisplayName("Mockito MockMaker: InlineByteBuddyMockMaker (дефолтный inline-maker, который оборачиваем)")
    void mockitoMockMakerPresent() {
        require(classPresent("org.mockito.internal.creation.bytebuddy.InlineByteBuddyMockMaker"),
                "InlineByteBuddyMockMaker уехал → AllureMockitoMockMaker оборачивает именно его (иначе NoClassDefFound у всех моков потребителя)");
    }

    @Test
    @DisplayName("Kafka: KafkaProducer.send(ProducerRecord, Callback) и KafkaConsumer.poll(Duration)")
    void kafkaMatchers() {
        require(hasMethod("org.apache.kafka.clients.producer.KafkaProducer", "send", 2,
                        "org.apache.kafka.clients.producer.ProducerRecord"),
                "KafkaProducer.send(ProducerRecord, Callback) уехал → обнови матчер в AllureKafkaProducerInstrumentation");
        require(hasMethod("org.apache.kafka.clients.consumer.KafkaConsumer", "poll", 1, "java.time.Duration"),
                "KafkaConsumer.poll(Duration) уехал → обнови матчер в AllureKafkaConsumerInstrumentation");
    }

    @Test
    @DisplayName("WireMock: static WireMock.{verify,stubFor,reset} и WireMockServer.{verify,stubFor,resetAll}")
    void wireMockMatchers() {
        String stat = "com.github.tomakehurst.wiremock.client.WireMock";
        require(hasMethod(stat, "verify", -1, null), "WireMock.verify уехал → AllureWireMockVerifyInstrumentation");
        require(hasMethod(stat, "stubFor", -1, null), "WireMock.stubFor уехал → AllureWireMockVerifyInstrumentation");
        require(hasMethod(stat, "reset", -1, null), "WireMock.reset уехал → AllureWireMockVerifyInstrumentation");
        String server = "com.github.tomakehurst.wiremock.WireMockServer";
        require(hasMethod(server, "verify", -1, null), "WireMockServer.verify уехал → AllureWireMockVerifyInstrumentation");
        require(hasMethod(server, "stubFor", -1, null), "WireMockServer.stubFor уехал → AllureWireMockVerifyInstrumentation");
        require(hasMethod(server, "resetAll", -1, null), "WireMockServer.resetAll уехал → AllureWireMockVerifyInstrumentation");
    }

    @Test
    @DisplayName("Spring AssertionErrors: assertEquals/assertNotEquals(3), assertTrue/False/Null/NotNull(2)")
    void springAssertionMatchers() {
        String ae = "org.springframework.test.util.AssertionErrors";
        require(hasMethod(ae, "assertEquals", 3, null), "AssertionErrors.assertEquals(3-арг) уехал → AllureSpringAssertionsInstrumentation");
        require(hasMethod(ae, "assertNotEquals", 3, null), "AssertionErrors.assertNotEquals(3-арг) уехал → AllureSpringAssertionsInstrumentation");
        require(hasMethod(ae, "assertTrue", 2, null), "AssertionErrors.require(2-арг) уехал → AllureSpringAssertionsInstrumentation");
        require(hasMethod(ae, "assertFalse", 2, null), "AssertionErrors.assertFalse(2-арг) уехал → AllureSpringAssertionsInstrumentation");
        require(hasMethod(ae, "assertNull", 2, null), "AssertionErrors.assertNull(2-арг) уехал → AllureSpringAssertionsInstrumentation");
        require(hasMethod(ae, "assertNotNull", 2, null), "AssertionErrors.assertNotNull(2-арг) уехал → AllureSpringAssertionsInstrumentation");
    }

    @Test
    @DisplayName("Hamcrest: MatcherAssert.assertThat(reason, actual, matcher) — 3-арг")
    void hamcrestMatcher() {
        require(hasMethod("org.hamcrest.MatcherAssert", "assertThat", 3, null),
                "MatcherAssert.assertThat(3-арг) уехал → обнови матчер в AllureHamcrestInstrumentation");
    }

    @Test
    @DisplayName("AssertJ: иерархия AbstractAssert и ключевые проверки (isEqualTo/startsWith/contains)")
    void assertjHierarchy() {
        // матчер isSubTypeOf(AbstractAssert) + методы-проверки в абстрактных предках;
        // если уедут — перехват неполон (см. docs/adr/0001-assertj-instrumentation.md)
        require(hasMethod("org.assertj.core.api.AbstractAssert", "isEqualTo", 1, null),
                "AbstractAssert.isEqualTo уехал → пересмотри AllureAssertJInstrumentation");
        require(hasMethod("org.assertj.core.api.AbstractCharSequenceAssert", "startsWith", 1, null),
                "AbstractCharSequenceAssert.startsWith уехал → строковые ассерты выпадут из отчёта");
        require(hasMethod("org.assertj.core.api.AbstractIterableAssert", "contains", 1, null),
                "AbstractIterableAssert.contains уехал → коллекционные ассерты выпадут из отчёта");
    }

    @Test
    @DisplayName("JUnit Jupiter Assertions: все включённые имена + fail/assertAll (страхует исключение)")
    void junitJupiterAssertionMatchers() {
        String a = "org.junit.jupiter.api.Assertions";
        // ВСЕ включённые в матчер имена (не подмножество) — если уедут, перехват выпадет молча
        for (String m : new String[]{"assertEquals", "assertNotEquals", "assertTrue", "assertFalse",
                "assertNull", "assertNotNull", "assertSame", "assertNotSame", "assertArrayEquals",
                "assertIterableEquals", "assertLinesMatch", "assertInstanceOf", "assertThrows",
                "assertThrowsExactly", "assertDoesNotThrow", "assertTimeout", "assertTimeoutPreemptively"}) {
            require(hasMethod(a, m, -1, null),
                    "Assertions." + m + " уехал → обнови матчер в AllureJUnitJupiterAssertionsInstrumentation");
        }
        // допущение «фасад Assertions не само-делегирует → депт-счётчик не нужен» стерегут РАНТАЙМ-тесты
        // на реальной версии Jupiter: AllureJUnitJupiterAssertionsTest#singleAssertOneStep (level-A) +
        // JUnitJupiterAssertionsReportIT (eq==1, level-B) — покраснеют, если появится удвоение (эффект
        // сильнее статического байт-скана: проверяем результат, а не форму).
        // fail/assertAll ДОЛЖНЫ существовать — их мы ОСОЗНАННО исключили; если исчезнут, исключение врёт
        require(hasMethod(a, "fail", -1, null),
                "Assertions.fail уехал → пересмотри исключение fail в AllureJUnitJupiterAssertionsInstrumentation");
        require(hasMethod(a, "assertAll", -1, null),
                "Assertions.assertAll уехал → пересмотри исключение assertAll в AllureJUnitJupiterAssertionsInstrumentation");
    }

    @Test
    @DisplayName("AssertJ: приватное поле AbstractAssert.actual (@Advice.FieldValue(\"actual\"))")
    void assertjActualField() {
        // AllureAssertJInstrumentation читает значение через @Advice.FieldValue("actual") — это имя
        // ПРИВАТНОГО поля. Переименуют → трансформация типа падает целиком, и без счётчика сбоев
        // это было бы невидимо: исчезли бы ВСЕ шаги AssertJ при зелёных тестах.
        require(hasField("org.assertj.core.api.AbstractAssert", "actual"),
                "AbstractAssert.actual уехал → обнови @Advice.FieldValue в AllureAssertJInstrumentation");
    }

    /** Известные имена MockMvcBuilderCustomizer: Boot 3.x → Boot 4.x. Порядок = порядок поиска. */
    private static final String[] MOCKMVC_CUSTOMIZER = {
            // Boot 3.x, артефакт spring-boot-test-autoconfigure
            "org.springframework.boot.test.autoconfigure.web.servlet.MockMvcBuilderCustomizer",
            // Boot 4.x, артефакт org.springframework.boot:spring-boot-webmvc-test
            "org.springframework.boot.webmvc.test.autoconfigure.MockMvcBuilderCustomizer"};

    private static final String[] WEBTESTCLIENT_CUSTOMIZER = {
            // Boot 3.x, артефакт spring-boot-test-autoconfigure
            "org.springframework.boot.test.web.reactive.server.WebTestClientBuilderCustomizer",
            // Boot 4.x, артефакт org.springframework.boot:spring-boot-webtestclient
            "org.springframework.boot.webtestclient.autoconfigure.WebTestClientBuilderCustomizer"};

    @Test
    @DisplayName("Spring Boot: кастомайзеры MockMvc/WebTestClient есть под СТАРЫМ или НОВЫМ именем")
    void springBootTestCustomizers() {
        // Гейт @ConditionalOnClass читается ASM-ом БЕЗ загрузки класса: класс уехал → условие
        // просто false → автоконфиг молча не применяется. Канарейка обязана быть полезной и ДО,
        // и ПОСЛЕ апгрейда, поэтому проверяет НАБОР известных имён, а не одно.
        require(anyPresent(MOCKMVC_CUSTOMIZER),
                "MockMvcBuilderCustomizer не найден НИ ПОД ОДНИМ известным именем — @ConditionalOnClass "
                        + "в AllureMockMvcAutoConfiguration ложен, кастомайзер МОЛЧА не регистрируется "
                        + "(остаётся только байткод-канал MockMvc.perform).\n  Boot 3.x: " + MOCKMVC_CUSTOMIZER[0]
                        + "\n  Boot 4.x: " + MOCKMVC_CUSTOMIZER[1] + "  [spring-boot-webmvc-test]");
        require(anyPresent(WEBTESTCLIENT_CUSTOMIZER),
                "WebTestClientBuilderCustomizer не найден НИ ПОД ОДНИМ известным именем — у WebTestClient "
                        + "байткод-фолбэка НЕТ, шаги исчезнут ПОЛНОСТЬЮ.\n  Boot 3.x: " + WEBTESTCLIENT_CUSTOMIZER[0]
                        + "\n  Boot 4.x: " + WEBTESTCLIENT_CUSTOMIZER[1] + "  [spring-boot-webtestclient]");

        // Список имён — СТРОКИ, компилятор их не проверяет. Сверяем со ЗНАЧЕНИЕМ типа, против
        // которого реально скомпилирован main: если main переедет на имя вне списка — красный
        // здесь, а не «странно зелёная канарейка при мёртвом модуле».
        require(java.util.List.of(MOCKMVC_CUSTOMIZER).contains(
                        org.springframework.boot.test.autoconfigure.web.servlet.MockMvcBuilderCustomizer.class.getName()),
                "AllureMockMvcAutoConfiguration скомпилирован против имени вне списка — обнови MOCKMVC_CUSTOMIZER");
        require(java.util.List.of(WEBTESTCLIENT_CUSTOMIZER).contains(
                        org.springframework.boot.test.web.reactive.server.WebTestClientBuilderCustomizer.class.getName()),
                "AllureWebTestClientAutoConfiguration скомпилирован против имени вне списка — обнови WEBTESTCLIENT_CUSTOMIZER");

        require(classPresent("org.springframework.test.web.servlet.ResultHandler"),
                "ResultHandler уехал → AllureMockMvcAutoConfiguration вешает AllureMockMvcResultHandler через него");
    }

    /** Есть ли класс хотя бы под одним из известных имён (класс переезжает между мажорами). */
    private static boolean anyPresent(String... classNames) {
        for (String name : classNames) {
            if (classPresent(name)) {
                return true;
            }
        }
        return false;
    }

    @Test
    @DisplayName("RestAssured: метод content и класс-исключение ResponseAwareMatcher")
    void restAssuredValidationExtras() {
        String vroi = "io.restassured.internal.ValidatableResponseOptionsImpl";
        require(hasMethod(vroi, "content", -1, null),
                "ValidatableResponseOptionsImpl.content уехал → обнови список имён в AllureRestAssuredValidationInstrumentation");
        // матчер ИСКЛЮЧАЕТ перегрузки с ResponseAwareMatcher: уедет класс — исключение начнёт
        // молча пропускать их в отчёт (лишние/битые шаги), а не падать
        require(classPresent("io.restassured.matcher.ResponseAwareMatcher"),
                "ResponseAwareMatcher уехал → пересмотри исключение hasType(...) в AllureRestAssuredValidationInstrumentation");
    }

    @Test
    @DisplayName("JPA: jakarta.persistence.Entity (разбор полей сущностей во вложении DB Result)")
    void jpaEntityAnnotation() {
        // AllureRepositoryAspect ищет аннотацию через Class.forName и при ClassNotFoundException
        // ТИХО деградирует к generic toString() — вложение остаётся, но становится бесполезным
        require(classPresent("jakarta.persistence.Entity"),
                "jakarta.persistence.Entity уехал → AllureRepositoryAspect перестанет разбирать поля сущностей");
    }

    @Test
    @DisplayName("Гейты присутствия (ClassPresence): строки-имена из листенеров")
    void classPresenceGates() {
        // Каждая строка — выключатель целого модуля: не найден класс → листенер молча не включается.
        // Полный инвентарь строк из *Listener (grep ClassPresence.isPresent в src/main).
        for (String[] gate : new String[][]{
                {"org.springframework.test.web.servlet.MockMvc", "AllureMockMvcListener"},
                {"org.springframework.web.client.RestTemplate", "AllureRestTemplateListener"},
                {"org.springframework.web.client.RestClient", "AllureRestClientListener"},
                {"io.restassured.RestAssured", "AllureRestAssuredListener"},
                {"org.springframework.jdbc.core.JdbcTemplate", "AllureJdbcListener"},
                {"ch.qos.logback.classic.LoggerContext", "AllureApplicationLogsListener"},
                {"com.github.tomakehurst.wiremock.WireMockServer", "AllureWireMockTestListener"},
                {"liquibase.changelog.ChangeSet", "AllureLiquibaseListener"},
                {"org.awaitility.Awaitility", "AllureAwaitilityListener"}}) {
            require(classPresent(gate[0]),
                    gate[0] + " уехал → " + gate[1] + " молча выключится (обнови строку ClassPresence.isPresent)");
        }
    }

    @Test
    @DisplayName("byte-buddy знает формат классов текущей JVM (иначе весь перехват тихо мёртв)")
    void byteBuddySupportsCurrentJvmClassFormat() {
        // Апгрейд JDK опережает byte-buddy: если формат class-файлов новее известного ему,
        // трансформация падает на КАЖДОМ типе. Прямая канарейка на Java 25+.
        // Версию берём у самой JVM, БЕЗ фолбэка ofThisVm(JAVA_V21): фолбэк отдаётся, когда версию
        // определить не удалось, и канарейка зеленела бы ровно тогда, когда ничего не известно —
        // ветки «не смог проверить → считаю, что всё хорошо» у детектора быть не должно.
        require(net.bytebuddy.ClassFileVersion.ofJavaVersion(Runtime.version().feature())
                        .isAtMost(net.bytebuddy.ClassFileVersion.latest()),
                "byte-buddy не знает формат классов этой JVM → весь байткод-слой мёртв. "
                        + "Подними версию byte-buddy (или включи -Dnet.bytebuddy.experimental=true ОСОЗНАННО)");
    }

    /** Объявлен ли метод ИМЕННО в этом классе иерархии (ByteBuddy вплетает только в объявителя). */
    private static boolean declaredIn(String className, String method, Class<?> paramType, String expectedDeclarer) {
        try {
            return Class.forName(className).getMethod(method, paramType).getDeclaringClass()
                    .getName().equals(expectedDeclarer);
        } catch (ReflectiveOperationException e) {
            return false;
        }
    }

    /** Есть ли у класса поле (в т.ч. приватное) — для канареек на {@code @Advice.FieldValue}. */
    private static boolean hasField(String className, String field) {
        try {
            Class<?> c = Class.forName(className);
            for (Class<?> k = c; k != null; k = k.getSuperclass()) {
                try {
                    k.getDeclaredField(field);
                    return true;
                } catch (NoSuchFieldException next) {
                    // ищем выше по иерархии
                }
            }
            return false;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
