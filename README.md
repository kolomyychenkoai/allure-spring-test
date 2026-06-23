# allure-spring-test

Zero-config [Allure](https://allurereport.org/)-репортинг для **Spring-тестов**.
Подключаешь зависимость — и каждый тест автоматически даёт богатый Allure-отчёт
(HTTP-вызовы, SQL, Kafka, заглушки, ассерты, моки, логи, конфигурация) **без единой
строчки кода в тестах и без правок приложения**.

Работает по тому же принципу, что `allure-junit5` / `allure-rest-assured`: JAR на
test-classpath, который сам себя «вшивает». Точки входа — Spring `TestExecutionListener`
(авторегистрация через `META-INF/spring.factories`), Spring Boot auto-configuration и
байткод-инструментирование (ByteBuddy) там, где у библиотеки нет hook'а.

**Главный принцип:** детерминизм вперёд, перехват — только у успешных операций. Падения
показывает сам Allure (тест red + сообщение/стек), мы НЕ фабрикуем «красные» шаги.

---

## Содержание
- [Что попадает в отчёт](#что-попадает-в-отчёт)
- [Требования](#требования)
- [Установка](#установка)
- [Быстрый старт](#быстрый-старт)
- [Просмотр отчёта](#просмотр-отчёта)
- [Что именно логируется (по модулям)](#что-именно-логируется-по-модулям)
- [Настройка (тумблеры)](#настройка-тумблеры)
- [Mockito (по согласию)](#mockito-по-согласию)
- [Как устроено](#как-устроено)
- [Ограничения (by design)](#ограничения-by-design)
- [Разработка и поддержка](#разработка-и-поддержка)
- [Лицензия](#лицензия)

---

## Что попадает в отчёт

Без кода в тестах, само:

- **HTTP** — MockMvc и REST Assured: метод, путь, статус + тело запроса/ответа.
- **База данных** — вызовы Spring Data репозиториев + реальный SQL внутри них.
- **Kafka** — отправка (`producer.send`) и приём (`consumer.poll`) сообщений.
- **WireMock** — заглушки, `verify`, near-miss (почему не сматчилось), состояния
  сценариев, сброс, и каждый обслуженный запрос.
- **Ассерты** — AssertJ, Hamcrest, Spring `AssertionErrors`: каждая успешная проверка
  отдельным шагом с понятным именем.
- **Mockito** (по согласию) — заглушки/вызовы/проверки моков.
- **Логи приложения** — всё, что залогировано (SLF4J/Logback) за время теста.
- **Конфигурация** — срез свойств Spring `Environment`.

## Требования

- **Java 21+**
- **Spring (Spring Test)** — обычно через `spring-boot-starter-test`
- **Allure JUnit-интеграция**, чтобы результаты вообще записывались (например `allure-junit5`)
- ByteBuddy на test-classpath — обычно уже есть транзитивно (Mockito / spring-boot-starter-test)

## Установка

Maven:

```xml
<dependency>
    <groupId>io.github.kolomyychenkoai</groupId>
    <artifactId>allure-spring-test</artifactId>
    <version>0.1.0</version>
    <scope>test</scope>
</dependency>
```

Больше ничего. Любой Spring-управляемый тест (`@SpringBootTest`, `@SpringJUnitConfig`, …)
сразу начинает наполнять Allure-отчёт.

## Быстрый старт

```java
@SpringBootTest
@AutoConfigureMockMvc
class OrderServiceTest {
    private static final Logger log = LoggerFactory.getLogger(OrderServiceTest.class);

    @Autowired MockMvc mockMvc;

    @Test
    void createsOrder() throws Exception {
        log.info("создаём заказ");                 // → попадёт во вложение «Application Logs»

        mockMvc.perform(post("/api/orders")        // → шаг «HTTP POST /api/orders → 200»
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"product\":\"laptop\"}"))
            .andExpect(status().isOk());
        // вызовы репозитория/SQL, Kafka, ассерты внутри — тоже окажутся в отчёте сами
    }
}
```

## Просмотр отчёта

```bash
mvn test          # пишет target/allure-results
mvn allure:serve  # собирает отчёт и открывает в браузере
# либо: mvn allure:report  →  target/site/allure-maven-plugin/index.html
```

Витрину смотреть во вкладке **Behaviors** (группировка по Epic/Feature) — там «живые»
сценарии. Юнит-тесты библиотеки во вкладке **Suites** (для in-memory тестов файловый
результат пустой — это нормально).

## Что именно логируется (по модулям)

Перехватываются ВСЕ основные операции каждой библиотеки, автоматически:

| Модуль | Что ловится | Шаг / вложения |
|---|---|---|
| **Spring MockMvc** | каждый `perform(...)`, любой HTTP-метод, query, не-2xx | `HTTP <METHOD> <path> → <status>` + `HTTP Request` / `HTTP Response` (+ `HTTP Exception`) |
| **REST Assured** | каждый глобальный `given()...` запрос, любой метод | `HTTP <METHOD> <path> → <status>` + `HTTP Request` / `HTTP Response` |
| **Spring Data (JPA)** | каждый метод репозитория + реальный SQL внутри | `DB <Repo>.<method>` со вложенным `SQL <OP> <таблица>` + `DB Call` / `DB Result` / `SQL Query` |
| **Kafka** | `producer.send(record, callback)`, `consumer.poll(Duration)` | `Kafka: отправлено → …` / `Kafka: получено N сообщ.` + вложение сообщения |
| **WireMock** | `stubFor`, `verify(...)`, `resetAll()` / статический `reset()`, каждый обслуженный запрос, near-miss, состояние сценария | `Создана заглушка …` / `Проверка обращений …` / `Запрос к заглушке …` / `Near-miss …` / `WireMock сценарий …` / `WireMock: сброс …` + Request/Response/Stub |
| **AssertJ** | каждая **успешная** проверка на `AbstractAssert` и наследниках (вкл. кастомные ассерты) | `Проверка: значение X — <method> <args>` |
| **Hamcrest** | `MatcherAssert.assertThat(...)` (2- и 3-арг) | `Проверка: [reason:] значение X, ожидалось <matcher>` |
| **Spring-ассерты** | каждый успешный `AssertionErrors.assert*` | `Проверка: <message> — …` |
| **Mockito** (по согласию) | каждое взаимодействие с моком (заглушка / вызов / проверка) | `Мок-заглушка/вызов/проверка: Class.method(args)` + `Mock Call` / `Mock Result` |
| **Логи / конфигурация** | Logback-вывод за тест / срез `Environment` | вложение `Application Logs` / шаг `Configuration` + вложение `Properties` |

> **Падения — зона Allure, а не отдельный шаг.** Шаг создаётся для УСПЕШНО завершённой
> операции/проверки. Когда проверка падает — исключение пробрасывается, тест становится
> red, и Allure кладёт сообщение+стек на уровень теста. Библиотека НЕ рисует «красный»
> шаг и не дублирует текст исключения. (Единственное исключение — ошибка операции БД:
> шаг помечается `BROKEN`, но без текста исключения.)

## Настройка (тумблеры)

Все тумблеры читаются из Spring `Environment` (`application.yml`/`.properties`) с
фоллбэком на system property. По умолчанию всё ВКЛЮЧЕНО (кроме Mockito — см. ниже).

| Свойство | По умолчанию | Что делает |
|---|---|---|
| `allure.spring.web.enabled` | `true` | HTTP-шаги MockMvc и REST Assured |
| `allure.spring.data.enabled` | `true` | Шаги вызовов репозиториев (аспект) |
| `allure.spring.datasource.enabled` | `true` | Реальный SQL (datasource-proxy) |
| `allure.spring.kafka.enabled` | `true` | Kafka send/poll |
| `allure.spring.wiremock.enabled` | `true` | WireMock стабы/verify/запросы/сброс |
| `allure.spring.assertion.enabled` | `true` | Ассерты AssertJ / Hamcrest / Spring |
| `allure.spring.mock.enabled` | `true`* | Логирование Mockito (*ещё требует SPI-файл, см. ниже) |
| `allure.spring.logs.enabled` | `true` | Вложение `Application Logs` |
| `allure.spring.config.enabled` | `true` | Шаг `Configuration` |
| `allure.spring.config.include-prefixes` | `spring.,server.,logging.,management.` | CSV-префиксы свойств в срезе конфигурации |

> Значения свойств в срезе **не маскируются** — срез ограничен только префиксами. В тестах
> используйте нечувствительные (фейковые) данные.

```yaml
# пример: добавить свой namespace в срез конфигурации
allure:
  spring:
    config:
      include-prefixes: spring.,server.,logging.,management.,myapp.
```

## Mockito (по согласию)

Логирование взаимодействий с моками НЕ включается само: оно работает через глобальный
Mockito `MockMaker`, а его нельзя навязывать каждому потребителю (конфликт с его
собственным `MockMaker`). Чтобы включить — добавь ОДИН файл в test-resources:

`src/test/resources/mockito-extensions/org.mockito.plugins.MockMaker`
```
io.github.kolomyychenkoai.allure.spring.mock.AllureMockitoMockMaker
```

Модуль завязан на внутренности Mockito 5.x (inline mock maker) — держи Mockito на версии
из `spring-boot-starter-test`.

## Как устроено

- **`TestExecutionListener` + `spring.factories`** — логи (`AllureApplicationLogsListener`)
  и конфигурация (`AllureConfigurationListener`) подключаются на каждый тест без аннотаций.
- **Spring Boot auto-configuration** — аспект репозиториев, обёртка DataSource,
  кастомайзер MockMvc подключаются сами (выключаются тумблерами).
- **Байткод (ByteBuddy)** — там, где у библиотеки нет hook'а: ассерты (AssertJ/Hamcrest/
  Spring), Kafka `send`/`poll`, WireMock `stubFor`/`verify`/`reset`. Установка идемпотентна
  (один раз на JVM), сбой инструментирования логируется на WARNING и не роняет тест.
- **Уровни тестов в самой библиотеке:** A — детерминированные in-memory проверки логики
  (`InMemoryAllure`); B — «живые» `*ReportIT` (`@SpringBootTest`), которые гоняют реальную
  цепочку и проверяют записанный отчёт. Подробности — в `docs/`.

## Ограничения (by design)

- **Реактивные репозитории** (Spring Data R2DBC) не покрыты — аспект ловит синхронную
  (JPA) иерархию `Repository+`.
- **Вручную собранный MockMvc** (`MockMvcBuilders.standaloneSetup(...)` мимо
  авто-кастомайзера) не перехватывается — используй `@AutoConfigureMockMvc` / фикстуры Spring Boot.
- **REST Assured** логирует только глобальный `given()` API; изолированный
  `RequestSpecification` с локальными фильтрами не ловится.
- **Частичные сбросы WireMock** (`resetMappings/resetRequests/resetScenarios`,
  статические `resetAllRequests/resetScenario/resetAllScenarios`) не логируются — шаг даёт
  только полный сброс (`resetAll()` / статический `reset()`).
- **Параллельный запуск** (`@Execution(CONCURRENT)`) не поддержан: глобальные фильтры REST
  Assured, буфер обменов WireMock и корневой лог-аппендер — общее состояние. Гонять
  последовательно (forked-JVM surefire — норм).

## Разработка и поддержка

Для тех, кто дорабатывает библиотеку:

- `docs/acceptance-report-standard.md` — критерий приёмки по Allure-отчёту (+ эталонные модули).
- `docs/java-code-standard.md` — кодекс качества кода (идиомы, потокобезопасность, грабли).
- `docs/adr/0001-assertj-instrumentation.md` — решение по самому хрупкому узлу (AssertJ).
- `internal/InstrumentationApiCanaryTest` — канарейки версионных допущений: при апгрейде
  чужих библиотек краснеют точечно, показывая, какой матчер обновить.
- `.claude/agents/` — мандаты ревьюеров (architect/security/java-lead/tester/maintainer/qa-lead).

```bash
mvn clean test            # полный прогон (офлайн, без Docker)
mvn -q -DskipTests compile  # быстрый компайл-чек
```

## Лицензия

[Apache-2.0](LICENSE).
