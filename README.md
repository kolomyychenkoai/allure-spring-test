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

## How it works

- `AllureApplicationLogsListener` attaches a Logback appender to the root logger
  for the duration of each test, then writes the captured lines to Allure.
- `AllureConfigurationListener` reads the `ConfigurableEnvironment` before each
  test, filters by prefix and secrets, and attaches the result inside a
  `Configuration` step.
- Both are registered in `META-INF/spring.factories` under
  `org.springframework.test.context.TestExecutionListener`, so Spring activates
  them for every test — no annotations, no setup.

## License

[Apache-2.0](LICENSE).
