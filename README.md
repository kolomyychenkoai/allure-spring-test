# allure-spring-test

Zero-config [Allure](https://allurereport.org/) reporting for **Spring tests**.
Add the dependency — and every test automatically gets two attachments in the
Allure report, **without touching your test or application code**:

- **Application Logs** — everything logged (via SLF4J/Logback) during the test.
- **Configuration** — a snapshot of the relevant Spring `Environment` properties
  (secrets filtered out).

It works the same way as `allure-junit5`, `allure-rest-assured` and friends: a JAR
on the test classpath that wires itself in. Here the entry point is a Spring
`TestExecutionListener` auto-registered via `META-INF/spring.factories`.

> Status: early (`0.1.0`). Currently ships the **logs** and **configuration**
> capture. HTTP / DB / Kafka / assertion capture are planned as follow-up modules.

## Requirements

- Java 21+
- Spring (Spring Test) — typically via `spring-boot-starter-test`
- An Allure JUnit integration so test results are recorded (e.g. `allure-junit5`)

## Install

Maven:

```xml
<dependency>
    <groupId>io.github.kolomyychenkoai</groupId>
    <artifactId>allure-spring-test</artifactId>
    <version>0.1.0</version>
    <scope>test</scope>
</dependency>
```

That's it. Any Spring-managed test (`@SpringBootTest`, `@SpringJUnitConfig`, …) now
attaches logs and a configuration snapshot automatically.

```java
@SpringBootTest
class OrderServiceTest {
    private static final Logger log = LoggerFactory.getLogger(OrderServiceTest.class);

    @Test
    void createsOrder() {
        log.info("creating order");   // <- ends up in the "Application Logs" attachment
        // ...
    }
}
```

## View the report

```bash
mvn test          # produces target/allure-results
mvn allure:serve  # builds the report and opens it in your browser
# or: mvn allure:report  ->  target/site/allure-maven-plugin/index.html
```

## Configuration

All toggles are read from the Spring `Environment` (your `application.yml`/
`application.properties`), with a system-property fallback. Everything is on by
default.

| Property | Default | Meaning |
|---|---|---|
| `allure.spring.logs.enabled` | `true` | Attach the **Application Logs**. |
| `allure.spring.config.enabled` | `true` | Attach the **Configuration** snapshot. |
| `allure.spring.config.include-prefixes` | `spring.,server.,logging.,management.` | CSV of property-name prefixes included in the snapshot. |

> Note: values are **not** masked. The snapshot is scoped by prefix only — assume
> test configuration uses non-sensitive (fake) data.

```yaml
# example: include your own namespace too
allure:
  spring:
    config:
      include-prefixes: spring.,server.,logging.,management.,myapp.
```

## Mockito (opt-in)

Mockito interaction logging (mock stub / call / verify steps) is **not enabled by
default** — it works by registering a global Mockito `MockMaker`, which would otherwise
be forced on every consumer and could clash with a project's own `MockMaker`. To enable
it, add one file to your test resources:

`src/test/resources/mockito-extensions/org.mockito.plugins.MockMaker`
```
io.github.kolomyychenkoai.allure.spring.mock.AllureMockitoMockMaker
```

Note: this module relies on Mockito 5.x internals (the inline mock maker); keep Mockito
at the version managed by `spring-boot-starter-test`.

## How it works

- `AllureApplicationLogsListener` attaches a Logback appender to the root logger
  for the duration of each test, then writes the captured lines to Allure.
- `AllureConfigurationListener` reads the `ConfigurableEnvironment` before each
  test, filters by prefix and secrets, and attaches the result inside a
  `Configuration` step.
- Both are registered in `META-INF/spring.factories` under
  `org.springframework.test.context.TestExecutionListener`, so Spring activates
  them for every test — no annotations, no setup.

## Coverage — what gets logged

Instrumentation captures all primary operations of each supported library, automatically:

| Library | Logged | Step / attachment |
|---|---|---|
| Spring MockMvc | every `perform(...)`, any HTTP method | `HTTP <METHOD> <path> → <status>` + Request/Response (+ Exception) |
| REST Assured | every global `given()...` request, any method | `HTTP <METHOD> <path> → <status>` + Request/Response |
| Spring Data (JPA) | every repository method + the real SQL it runs | `DB <Repo>.<method>` + nested `SQL <OP> <table>` + Call/Result/Query |
| Kafka | `producer.send(...)`, `consumer.poll(Duration)` | `Kafka: отправлено/получено` + message attachment |
| WireMock | `stubFor`, `verify(...)`, `resetAll()` / static `reset()`, every served request, near-miss, scenario state | stub/verify/reset/near-miss steps + Request/Response |
| AssertJ | every **passing** assertion on `AbstractAssert` & subtypes | `Проверка: значение X — <method> <args>` |
| Hamcrest | `assertThat(actual, matcher)` (2- and 3-arg) | `Проверка: …` |
| Spring asserts | every passing `AssertionErrors.assert*` | `Проверка: …` |
| Mockito (opt-in) | every mock interaction (stub / call / verify) | `Мок-заглушка/вызов/проверка` + Call/Result/Verify |
| App logs / config | per-test Logback output / `Environment` snapshot | `Application Logs` / `Configuration` + `Properties` |

> **Failures are reported by Allure, not fabricated as steps.** Steps are emitted for
> operations/checks that complete successfully. When a check fails, the exception
> propagates, the test fails, and Allure records the message + stack at the test level —
> the library does not create a "red" step or duplicate the exception text.

### Known limitations (by design)

- **Reactive repositories** (Spring Data R2DBC) are not covered — the aspect targets the
  synchronous (JPA) `Repository+` hierarchy only.
- **Manually built MockMvc** (`MockMvcBuilders.standaloneSetup(...)` outside the
  auto-configured customizer) is not intercepted — use `@AutoConfigureMockMvc` / Spring Boot fixtures.
- **REST Assured** logs only the global `given()` API; an isolated `RequestSpecification`
  with local-only filters is not captured.
- **WireMock partial resets** (`resetMappings/resetRequests/resetScenarios`,
  static `resetAllRequests/resetScenario/resetAllScenarios`) are not logged — only the full
  reset (`resetAll()` / static `reset()`) emits a step.
- **Parallel execution** (`@Execution(CONCURRENT)`) is not supported: REST Assured global
  filters, the WireMock exchange buffer and the root log appender are shared/global state.
  Run tests sequentially (forked-JVM surefire is fine).

## License

[Apache-2.0](LICENSE).
