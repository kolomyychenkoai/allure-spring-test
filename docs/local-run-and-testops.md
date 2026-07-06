# Пробуем локально + грузим отчёт в TestOps

Пошаговый копи-паст: как проверить библиотеку на своей машине, собрать её,
подключить в репозиторий своего сервиса, посмотреть отчёт и загрузить zip в Allure TestOps.

Всё офлайн, без Docker. Нужен **Java 21+** и **Maven**.

> Библиотека пока не в Maven Central — версия `0.1.0-SNAPSHOT`.
> Поэтому её сначала кладём в **локальный** Maven-репозиторий (`~/.m2`), а сервис берёт оттуда.

---

## Шаг 0. Проверь Java

```bash
java -version    # должно быть 21 или новее
```

Если версий несколько (jenv), в этом проекте дефолт — 21:

```bash
jenv global 21
```

---

## Шаг 1. Проверить саму библиотеку

Внутри проекта `allure-spring-test`:

```bash
cd ~/projects/allure-spring-test
mvn clean test          # полный прогон тестов (офлайн)
mvn allure:serve        # соберёт отчёт и откроет в браузере
```

Витрину смотри во вкладке **Behaviors** (сценарии сгруппированы по Epic/Feature).

Если всё зелёное — библиотека рабочая, идём дальше.

---

## Шаг 2. Собрать библиотеку и положить в локальный `~/.m2`

```bash
cd ~/projects/allure-spring-test
mvn clean install       # соберёт jar и установит в ~/.m2/repository
```

После этого артефакт доступен любому проекту на этой машине как:

```
io.github.kolomyychenkoai:allure-spring-test:0.1.0-SNAPSHOT
```

> Хочешь быстрее — можно без прогона тестов: `mvn clean install -DskipTests`.
> Но хотя бы раз прогони с тестами (Шаг 1), чтобы убедиться, что сборка честная.

Проверить, что легло:

```bash
ls ~/.m2/repository/io/github/kolomyychenkoai/allure-spring-test/0.1.0-SNAPSHOT/
```

---

## Шаг 3. Подключить в репозиторий своего сервиса

Открой `pom.xml` своего сервиса.

### 3.1. Добавь зависимость (scope `test`)

```xml
<dependency>
    <groupId>io.github.kolomyychenkoai</groupId>
    <artifactId>allure-spring-test</artifactId>
    <version>0.1.0-SNAPSHOT</version>
    <scope>test</scope>
</dependency>
```

### 3.2. Убедись, что есть Allure JUnit-интеграция

Без неё Allure вообще не пишет результаты. Обычно она уже есть; если нет — добавь:

```xml
<dependency>
    <groupId>io.qameta.allure</groupId>
    <artifactId>allure-junit5</artifactId>
    <version>2.25.0</version>
    <scope>test</scope>
</dependency>
```

### 3.3. Скажи Surefire писать результаты в `target/allure-results`

Иначе Allure пишет в `./allure-results` (корень проекта), и `mvn allure:serve` их не найдёт.
Добавь **один раз** в `<build><plugins>`:

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

### 3.4. (Опционально) реальный SQL в отчёте

Только если хочешь видеть SQL внутри вызовов репозиториев/`JdbcTemplate`:

```xml
<dependency>
    <groupId>net.ttddyy</groupId>
    <artifactId>datasource-proxy</artifactId>
    <version>1.10.1</version>
    <scope>test</scope>
</dependency>
```

Без него шаги вызовов БД остаются, пропадает только вложенный шаг с реальным SQL.

### 3.5. (Опционально) чтобы локально открывать отчёт из сервиса

Если хочешь в самом сервисе делать `mvn allure:serve`, добавь плагин:

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

> **Правки тестов не нужны.** Ничего в тест-коде не меняешь — библиотека вшивается сама.

---

## Шаг 4. Прогнать тесты сервиса и посмотреть отчёт

В корне сервиса:

```bash
mvn clean test          # прогон тестов → пишет target/allure-results
mvn allure:serve        # собрать и открыть отчёт в браузере (нужен Шаг 3.5)
```

Открылся отчёт, в шагах видно HTTP/SQL/Kafka/ассерты и т.д. — интеграция работает.

---

## Шаг 5. Сделать zip и загрузить в Allure TestOps

TestOps принимает **архив с содержимым папки `allure-results`** (сырые результаты, не готовый HTML).

### 5.1. Собрать zip

```bash
cd target/allure-results
zip -r ../allure-results.zip .
cd -
```

Готовый файл: `target/allure-results.zip`.

> Важно: zip'уем **содержимое** папки (`.`), а не саму папку — внутри архива должны лежать
> файлы `*-result.json` / `*-attachment.*` в корне, без вложенной папки `allure-results/`.

### 5.2. Загрузить в TestOps через веб-интерфейс

1. Открой свой проект в Allure TestOps.
2. Раздел **Launches** → кнопка **Create launch** (или **+**).
3. Выбери загрузку результатов → **Upload** и укажи `target/allure-results.zip`.
4. Дай запуску имя (напр. `local-run` + дата) и создай.
5. TestOps распарсит архив — тесты, шаги и вложения появятся в запуске.

### 5.3. (Опционально) загрузка через CLI `allurectl`

Если настроен `allurectl` (эндпоинт + токен + `ALLURE_PROJECT_ID`) — можно без zip и без UI:

```bash
export ALLURE_ENDPOINT="https://<твой-testops>"
export ALLURE_TOKEN="<токен из профиля TestOps>"
export ALLURE_PROJECT_ID="<id проекта>"

allurectl upload target/allure-results --launch-name "local-run"
```

`allurectl` сам заархивирует и зальёт `target/allure-results`.
Он **не установлен** на этой машине — поставь бинарь с GitHub Allure (`allure-framework/allurectl`),
если пойдёшь этим путём. Для разовой проверки проще zip + UI (Шаг 5.1–5.2).

---

## Шпаргалка (весь путь одним куском)

```bash
# 1. библиотека → в локальный .m2
cd ~/projects/allure-spring-test
mvn clean install

# 2. в pom своего сервиса: dependency + allure-junit5 + surefire allure.results.directory
#    (см. Шаг 3), правок в тестах НЕ нужно

# 3. прогон сервиса
cd ~/path/to/your-service
mvn clean test
mvn allure:serve                       # посмотреть локально

# 4. zip и в TestOps
cd target/allure-results && zip -r ../allure-results.zip . && cd -
#    → Launches → Create launch → Upload → target/allure-results.zip
```
