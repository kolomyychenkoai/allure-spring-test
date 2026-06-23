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
- **Конфигурация** — все свойства приложения из Spring `Environment` (application.yml, тестовые overrides; JVM-свойства и переменные ОС не включаются).

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

Любой Spring-управляемый тест (`@SpringBootTest`, `@SpringJUnitConfig`, …) сразу начинает
наполнять Allure-отчёт.

### Что для какой интеграции нужно на classpath

Базовый набор работает только от зависимости (+ `allure-junit5`). Перехват каждой
внешней технологии включается, ТОЛЬКО если её библиотека есть на test-classpath — модуль
сам её не тянет (всё `provided`/`optional`, чтобы не навязывать лишнее). Логично: ты
подключаешь библиотеку, если этой технологией пользуешься. Единственный неочевидный
случай — **SQL** (нужен `datasource-proxy`, его обычно нет по умолчанию).

| Что попадает в отчёт | Нужно на classpath |
|---|---|
| HTTP MockMvc, ассерты, логи приложения, конфигурация | ничего сверх зависимости |
| Вызовы Spring Data репозиториев | `spring-boot-starter-data-jpa` (тянет AspectJ сам) |
| **Реальный SQL** внутри вызовов репозитория | **`net.ttddyy:datasource-proxy`** ← легко забыть |
| HTTP REST Assured | `io.rest-assured:rest-assured` |
| WireMock (стабы/verify/запросы) | `org.wiremock:wiremock-standalone` |
| Kafka (send/poll) | `org.apache.kafka:kafka-clients` (через `spring-kafka`) |
| Mockito (заглушки/вызовы/проверки) | по согласию — см. раздел ниже |

Нет библиотеки на classpath — соответствующий модуль просто молчит, тесты не падают.

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

⚠️ По умолчанию Allure пишет результаты в `./allure-results` (корень проекта), и тогда
`allure:serve`/`report` их не найдут в `target`. Чтобы результаты шли в `target/allure-results`
(и `mvn clean` их подчищал), укажи каталог в surefire — ОДИН раз в `pom.xml`:

```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-surefire-plugin</artifactId>
    <configuration>
        <systemPropertyVariables>
            <allure.results.directory>${project.build.directory}/allure-results</allure.results.directory>
        </systemPropertyVariables>
    </configuration>
</plugin>
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
| **Логи / конфигурация** | Logback-вывод за тест / все свойства приложения из `Environment` | вложение `Application Logs` / шаг `Configuration` + вложение `Properties` |

> **Падения — зона Allure, а не отдельный шаг.** Шаг создаётся для УСПЕШНО завершённой
> операции/проверки. Когда проверка падает — исключение пробрасывается, тест становится
> red, и Allure кладёт сообщение+стек на уровень теста. Библиотека НЕ рисует «красный»
> шаг и не дублирует текст исключения. (Единственное исключение — ошибка операции БД:
> шаг помечается `BROKEN`, но без текста исключения.)

## Mockito (по согласию)

Это единственная фича, которую надо включить руками. Всё остальное в библиотеке
работает само — логи моков нет.

Почему отдельно: чтобы писать моки в отчёт, библиотеке надо встать на место «фабрики
моков» Mockito (`MockMaker`). Фабрика в проекте одна на всех — у кого-то она уже занята
своей. Молча её занять = сломать чужой проект. Поэтому библиотека ждёт твоего согласия.

Как включить — положи ОДИН файл в test-resources:

`src/test/resources/mockito-extensions/org.mockito.plugins.MockMaker`
```
io.github.kolomyychenkoai.allure.spring.mock.AllureMockitoMockMaker
```

После этого каждая заглушка, вызов и проверка мока поедут в Allure. Без файла этой фичи
нет, и обойти нечем — другого включателя у Mockito не существует.

Версию Mockito бери из `spring-boot-starter-test` (модуль завязан на внутренности
inline mock maker из Mockito 5.x), вручную не задирай.

## Как устроено

- **`TestExecutionListener` + `spring.factories`** — логи (`AllureApplicationLogsListener`)
  и конфигурация (`AllureConfigurationListener`) подключаются на каждый тест без аннотаций.
- **Spring Boot auto-configuration** — аспект репозиториев, обёртка DataSource,
  кастомайзер MockMvc подключаются сами.
- **Байткод (ByteBuddy)** — там, где у библиотеки нет hook'а: ассерты (AssertJ/Hamcrest/
  Spring), Kafka `send`/`poll`, WireMock `stubFor`/`verify`/`reset`. Установка идемпотентна
  (один раз на JVM), сбой инструментирования логируется на WARNING и не роняет тест.
- **Уровни тестов в самой библиотеке:** A — детерминированные in-memory проверки логики
  (`InMemoryAllure`); B — «живые» `*ReportIT` (`@SpringBootTest`), которые гоняют реальную
  цепочку и проверяют записанный отчёт. Подробности — в `docs/`.

## Ограничения (by design)

Это не баги, а осознанные границы. Ниже — что не покрыто, почему и что делать.

- **Реактивные репозитории** (Spring Data R2DBC, `ReactiveCrudRepository`) — не покрыты.
  *Почему:* аспект оборачивает вызов метода репозитория и ждёт результат сразу. Реактивный
  метод возвращает `Mono`/`Flux` мгновенно, а настоящий поход в БД случается позже — при
  подписке. Шаг закрылся бы раньше, чем что-то реально произошло. Нужен отдельный аспект;
  модуль рассчитан на синхронный (JPA) стек.
  *Что делать:* для реактивного стека — пока никак, логируй вручную.

- **Вручную собранный MockMvc** (`MockMvcBuilders.standaloneSetup(...)`) — не перехватится.
  *Почему:* handler цепляется через `MockMvcBuilderCustomizer` Spring Boot, а его Spring
  применяет только к MockMvc, который собрал САМ (`@AutoConfigureMockMvc` / фикстуры).
  Собранный руками MockMvc мимо этого механизма проходит — цеплять нечего.
  *Что делать:* используй `@AutoConfigureMockMvc` или готовые фикстуры Spring Boot.

- **REST Assured** — ловится только глобальный `given()` API.
  *Почему:* наш фильтр ставится в глобальный список `RestAssured.filters`. Его читает только
  глобальный API. Изолированный `RequestSpecification` со своими локальными фильтрами в
  глобальный список не смотрит — значит, для нас невидим.
  *Что делать:* для логов используй глобальный `given()`, а не отдельную спеку с локальными
  фильтрами.

- **Частичные сбросы WireMock** (`resetMappings/resetRequests/resetScenarios` + статические
  `resetAllRequests/resetScenario/resetAllScenarios`) — без отдельного шага.
  *Почему:* байткод цепляется только на ПОЛНЫЙ сброс (`resetAll()` / статический `reset()`) —
  это единственная точка, где надо успеть снять near-miss и состояния сценариев ДО того, как
  всё сотрут. Частичные сбросы редки и не матчатся. Они работают как обычно — просто шага в
  отчёте нет.
  *Что делать:* ничего; если нужен шаг сброса в отчёте — зови полный `resetAll()`.

- **Параллельный запуск** (`@Execution(CONCURRENT)`) — не поддержан.
  *Почему:* глобальные фильтры REST Assured, буфер обращений WireMock и корневой
  лог-аппендер — это ОБЩЕЕ состояние на всю JVM, не потокобезопасное. Параллельные тесты
  начнут писать друг другу в чужие шаги и журналы.
  *Что делать:* гоняй тесты последовательно. Распараллеливание процессами (forked-JVM
  surefire) — штатно: у каждого процесса своя JVM и своё состояние.

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
