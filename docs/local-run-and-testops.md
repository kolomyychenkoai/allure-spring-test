# Пробуем локально + грузим отчёт в TestOps

Нужен **Java 21+** и **Maven**.

---

## Шаг 0. Проверь Java

```bash
java -version    # должно быть 21 или новее
```

---

## Шаг 1. Собрать библиотеку и положить в локальный `~/.m2`

Внутри проекта `allure-spring-test` — одна команда:

```bash
cd ~/projects/allure-spring-test
mvn clean install       # прогонит тесты, соберёт jar и установит в ~/.m2/repository
```

Сборка прошла — библиотека готова и лежит в локальном репозитории.

После этого артефакт доступен любому проекту на этой машине как:

```
io.github.kolomyychenkoai:allure-spring-test:0.1.0-SNAPSHOT
```

Проверить, что легло:

```bash
ls ~/.m2/repository/io/github/kolomyychenkoai/allure-spring-test/0.1.0-SNAPSHOT/
```

> Если тесты библиотеки прогонять сейчас не нужно — `mvn clean install -DskipTests`.

---

## Шаг 2. Подключить в репозиторий своего сервиса

Открой `pom.xml` своего сервиса и добавь две зависимости (обе `test`):

```xml
<!-- сама библиотека -->
<dependency>
    <groupId>io.github.kolomyychenkoai</groupId>
    <artifactId>allure-spring-test</artifactId>
    <version>0.1.0-SNAPSHOT</version>
    <scope>test</scope>
</dependency>

<!-- чтобы в отчёт попал реальный SQL внутри вызовов БД -->
<dependency>
    <groupId>net.ttddyy</groupId>
    <artifactId>datasource-proxy</artifactId>
    <version>1.10.1</version>
    <scope>test</scope>
</dependency>
```

`datasource-proxy` показывает реальный SQL (`SQL <OP> <table>`) внутри шагов вызовов
репозиториев/`JdbcTemplate`. Spring Boot BOM его версию не задаёт — укажи явно.

Больше ничего добавлять не нужно: Allure JUnit-интеграция и настройка Surefire в нормально
настроенном Spring-сервисе уже есть. **Менять тесты не нужно** — библиотека работает сама,
без единой правки в коде. Если отчёт вдруг окажется пустым — см. «Возможные проблемы» в конце.

---

## Шаг 3. Прогнать тесты своего микросервиса

В корне сервиса:

```bash
mvn clean test          # прогон тестов → пишет target/allure-results
```

В `target/allure-results` появились сырые результаты — их и загружаем в TestOps.

---

## Шаг 4. Загрузить отчёт в Allure TestOps

TestOps принимает **архив с содержимым папки `allure-results`** (сырые результаты, не готовый HTML).

### 4.1. Собрать zip

```bash
cd target/allure-results
zip -r ../allure-results.zip .
cd -
```

Готовый файл: `target/allure-results.zip`.

> Важно: архивируем **содержимое** папки (`.`), а не саму папку — внутри архива должны лежать
> файлы `*-result.json` / `*-attachment.*` в корне, без вложенной папки `allure-results/`.

### 4.2. Загрузить через веб-интерфейс

1. Открой свой проект в Allure TestOps.
2. Раздел **Launches** → кнопка **Create launch** (или **+**).
3. Выбери загрузку результатов → **Upload** и укажи `target/allure-results.zip`.
4. Дай запуску имя (напр. `local-run` + дата) и создай.
5. TestOps разберёт архив — тесты, шаги и вложения появятся в запуске.

---

## Шпаргалка (весь путь одним куском)

```bash
# 1. библиотека → в локальный .m2
cd ~/projects/allure-spring-test
mvn clean install

# 2. в pom своего сервиса — зависимости allure-spring-test + datasource-proxy (test scope),
#    см. Шаг 2. Правок в тестах НЕ нужно.

# 3. прогон сервиса
cd ~/path/to/your-service
mvn clean test

# 4. zip и в TestOps
cd target/allure-results && zip -r ../allure-results.zip . && cd -
#    → Launches → Create launch → Upload → target/allure-results.zip
```

---

## Посмотреть отчёт локально (необязательно)

Можно и не смотреть — сразу заливать в TestOps (Шаг 4). Но если хочешь посмотреть у себя:

```bash
mvn allure:serve        # соберёт отчёт из target/allure-results и откроет в браузере
```

`allure-maven` плагин в сервисе обычно уже есть. Если вдруг нет — добавь в `<build><plugins>`:

```xml
<plugin>
    <groupId>io.qameta.allure</groupId>
    <artifactId>allure-maven</artifactId>
    <version>2.12.0</version>
    <configuration>
        <reportVersion>2.25.0</reportVersion>
    </configuration>
</plugin>
```

Отчёт откроется во вкладке **Behaviors** (сценарии сгруппированы по Epic/Feature).

---

## Возможные проблемы (и из-за чего)

На всякий случай — если что-то пошло не так, вот частые причины.

- **Отчёт пустой / шагов от библиотеки нет, хотя зависимость добавлена.**
  Скорее всего на test-classpath нет **Allure JUnit-интеграции** (`allure-junit5`) — без неё
  Allure вообще не записывает результаты, и подключать нечего. В нормально настроенном сервисе
  она уже есть; если нет — добавь:
  ```xml
  <dependency>
      <groupId>io.qameta.allure</groupId>
      <artifactId>allure-junit5</artifactId>
      <version>2.25.0</version>
      <scope>test</scope>
  </dependency>
  ```

- **TestOps/`allure:serve` не находит результаты («No results found»).**
  Allure по умолчанию пишет в `./allure-results` (корень проекта), а не в `target`.
  В большинстве сборок это уже увязано; если нет — либо заливай тот каталог, куда результаты
  реально легли (проверь `./allure-results`), либо скажи Surefire писать в `target/allure-results`
  (**один раз** в `pom.xml`):
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

- **В отчёте есть шаги вызовов БД, но нет вложенного реального SQL.**
  Не добавлена зависимость `datasource-proxy` из Шага 2 — именно она показывает реальный SQL.
  Добавь её (`test` scope).

- **Сборка падает на компиляции / странные ошибки версии.**
  Библиотека собрана под **Java 21** — на сервисе с Java 17 и ниже не заведётся. Проверь `java -version`.

- **Не хватает какого-то раздела (Kafka / WireMock / RestAssured …).**
  Модуль включается, только если его библиотека есть на test-classpath. Нет технологии в тестах —
  нет и раздела; это не ошибка. Полный список границ — в `README.md` (раздел «Ограничения»).

- **В TestOps архив загрузился, но тестов не видно.**
  Скорее всего запаковали саму папку, а не её содержимое: внутри zip должны лежать
  `*-result.json` в корне, без вложенной `allure-results/` (см. Шаг 4.1).

---

## Разработка библиотеки: инвентарь отчёта

> Этот раздел — для тех, кто правит САМУ библиотеку, а не подключает её к сервису.

Сборка гоняет тесты в ДВА прогона:

1. основной (`default-test`) — 299 тестов, как обычно;
2. `report-inventory` — отдельная JVM, сверяет виды шагов и вложений с эталоном
   `src/test/inventory/report-inventory.txt`.

Второй прогон краснеет, если из отчёта ПРОПАЛ вид шага или вложения — то есть инструментация
отвалилась молча. В сообщении указан класс-витрина и ответственный класс библиотеки.

**Осознанно изменил отчёт** — обнови эталон:

```bash
mvn clean test -Dinventory.update=true    # перезапишет эталон и НАМЕРЕННО упадёт
git diff src/test/inventory/report-inventory.txt
```

Падение при обновлении — так задумано: флаг нельзя случайно оставить в автопрогоне,
а изменение эталона обязано пройти через глаза человека и коммит.

Полезное:

- `mvn test -Dtest=KafkaReportIT` — точечный прогон; инвентарь при этом НЕ запускается
  (на неполных данных он бессмыслен) и эталон не трогает.
- `-Dinventory.strict=true` — падать и на НОВЫХ видах (для релизного прогона; в обычном
  новые виды только печатаются).
- `target/allure-results` чистится перед каждым прогоном (`maven-clean-plugin`, execution
  `wipe-allure-results`) — иначе проверки по содержимому каталога зеленели бы на файлах
  прошлых прогонов. Исключение: прямой вызов `mvn surefire:test` (без жизненного цикла).

**Про апгрейд Java/Spring.** Поломку ловят три независимых сигнала, держать все три:
`InstrumentationApiCanaryTest` (API чужой библиотеки уехал), `InstrumentationDiagnostics`
(перехват упал с ошибкой — счётчик в логе прогона), инвентарь (шаг исчез без ошибок).
Библиотека собирается под Java 21; для прогона на Java 25 её нужно сначала поставить
(`brew install openjdk@25` + `jenv add`) — локально сейчас есть 17, 21 и 26.
