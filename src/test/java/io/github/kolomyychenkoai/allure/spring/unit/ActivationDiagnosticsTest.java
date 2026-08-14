package io.github.kolomyychenkoai.allure.spring.unit;

import io.github.kolomyychenkoai.allure.spring.internal.ActivationDiagnostics;
import io.github.kolomyychenkoai.allure.spring.internal.DiagnosticsReset;
import io.github.kolomyychenkoai.allure.spring.support.LibraryLog;
import io.qameta.allure.Epic;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.LogRecord;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Правило жалобы намеренно УЗКОЕ: «фича есть, крючка нет». Если жаловаться шире, WARNING
 * появятся в каждой сборке и их перестанут читать — а тогда сигнал мёртв.
 */
@Epic("Внутренние проверки библиотеки")
class ActivationDiagnosticsTest {

    private static final String MOCK_MVC = "org.springframework.test.web.servlet.MockMvc";
    private static final String HOOK_BOOT3 =
            "org.springframework.boot.test.autoconfigure.web.servlet.MockMvcBuilderCustomizer";
    private static final String HOOK_BOOT4 =
            "org.springframework.boot.webmvc.test.autoconfigure.MockMvcBuilderCustomizer";

    @Test
    @DisplayName("MockMvc есть, крючка нет, байткод мёртв → жалоба говорит «шаги НЕ ПОПАДУТ»")
    void loudWhenBothChannelsDead() {
        List<String> problems = ActivationDiagnostics.problems(Set.of(MOCK_MVC)::contains, false);

        assertThat(problems).singleElement().asString()
                .contains("НЕ ПОПАДУТ").contains("spring-boot-webmvc-test");
    }

    @Test
    @DisplayName("MockMvc есть, крючка нет, но байткод жив → жалоба мягче")
    void softerWhenBytecodeAlive() {
        assertThat(ActivationDiagnostics.problems(Set.of(MOCK_MVC)::contains, true))
                .singleElement().asString().contains("Пока держит байткод-перехват");
    }

    @Test
    @DisplayName("крючок под НОВЫМ именем (Boot 4) — жалоб нет")
    void quietOnBoot4Names() {
        assertThat(ActivationDiagnostics.problems(Set.of(MOCK_MVC, HOOK_BOOT4)::contains, true)).isEmpty();
        assertThat(ActivationDiagnostics.problems(Set.of(MOCK_MVC, HOOK_BOOT3)::contains, true)).isEmpty();
    }

    @Test
    @DisplayName("фичи нет вовсе (Kafka-only потребитель) → молчим, не шумим в каждой сборке")
    void quietWhenFeatureAbsent() {
        assertThat(ActivationDiagnostics.problems(name -> false, true)).isEmpty();
    }

    @Test
    @DisplayName("WebTestClient без крючка — жалоба ЖЁСТКАЯ: у него байткод-фолбэка нет")
    void webTestClientHasNoFallback() {
        List<String> problems = ActivationDiagnostics.problems(
                Set.of("org.springframework.test.web.reactive.server.WebTestClient")::contains, true);

        assertThat(problems).singleElement().asString()
                .contains("НЕ ПОПАДУТ").contains("spring-boot-webtestclient");
    }

    @Test
    @DisplayName("byte-buddy не знает формат классов JVM → жалоба про МОЛЧА выключенный перехват")
    void warnsOnTooOldByteBuddy() {
        // Сценарий «Boot 3.4 на Java 25»: комбинация выглядит рабочей, но весь байткод-слой мёртв.
        // Это и есть та поломка, ради которой в README объявлен минимум Boot 3.5 для Java 25.
        List<String> problems = ActivationDiagnostics.problems(
                name -> false, true, true, "1.15.11");

        assertThat(problems).singleElement().asString()
                .contains("byte-buddy 1.15.11")
                .contains("МОЛЧА выключен")
                .contains("Kafka");
    }

    @Test
    @DisplayName("byte-buddy нет вовсе → про формат классов не жалуемся (нечему быть старым)")
    void quietAboutFormatWhenByteBuddyAbsent() {
        assertThat(ActivationDiagnostics.problems(name -> false, false, true, "1.15.11")).isEmpty();
    }

    @Test
    @DisplayName("на нашем classpath (всё на месте) жалоб нет — гейт заводится в зелёном")
    void quietOnCurrentClasspath() {
        assertThat(ActivationDiagnostics.problems(
                name -> {
                    try {
                        Class.forName(name, false, getClass().getClassLoader());
                        return true;
                    } catch (ClassNotFoundException absent) {
                        return false;
                    }
                }, true)).isEmpty();
    }

    @Test
    @DisplayName("noteOnce говорит один раз на прогон, сколько бы контекстов ни поднялось")
    void noteOnceSaysItOnlyOnce() {
        // Сброс, как у всех соседей: иначе корректность держится на finally соседа,
        // а при runOrder=random это ~17% красных (замерено круга 5).
        DiagnosticsReset.forget();
        // У потребителя за прогон поднимается десяток контекстов, и новость на каждый
        // превращается в шум, который перестают читать (этим кончилась проверка, снятая
        // из reportOnce — см. предупреждение в её javadoc).
        // Текст уникален на вызов: тест не зависит ни от порядка классов (runOrder=random),
        // ни от того, сказал ли кто-то ту же новость раньше в этой JVM.
        // Мутация: убрать дедупликацию по SAID → две записи → RED.
        String unique = "проверка однократности " + UUID.randomUUID();

        List<LogRecord> said = LibraryLog.capture(() -> {
            ActivationDiagnostics.noteOnce("DbRepository", unique);
            ActivationDiagnostics.noteOnce("DbRepository", unique);
        });

        assertThat(said.stream().filter(r -> r.getMessage().contains(unique)).count()).isEqualTo(1);
    }

    private static final String SPRING_DATA = "org.springframework.data.repository.Repository";
    private static final String TRANSACTIONAL_PROXY =
            "org.springframework.transaction.interceptor.TransactionalProxy";
    private static final String ASPECTJ = "org.aspectj.lang.ProceedingJoinPoint";

    @Test
    @DisplayName("Spring Data есть, spring-tx нет → сказано, что шагов репозиториев не будет")
    void loudWhenSpringDataWithoutTransactionApi() {
        // Тихое @ConditionalOnClass: автоконфигурация не выполнится, пожаловаться может только
        // этот класс — он в листенере и регистрируется всегда.
        // Мутация: выключить ветку (if (false && ...)) → RED.
        List<String> problems = ActivationDiagnostics.problems(
                Set.of(SPRING_DATA, ASPECTJ)::contains, true);

        assertThat(problems).singleElement().asString()
                .contains("spring-tx").contains("DB Repo.method");
    }

    @Test
    @DisplayName("Spring Data есть, AspectJ нет → сказано про starter-aop")
    void loudWhenSpringDataWithoutAspectJ() {
        // Самая частая форма потери раздела БД: starter-data-jdbc/mongodb/redis тянут spring-tx,
        // но не тянут aspectjweaver.
        // Мутация: выключить ветку (if (false && ...)) → RED.
        List<String> problems = ActivationDiagnostics.problems(
                Set.of(SPRING_DATA, TRANSACTIONAL_PROXY)::contains, true);

        assertThat(problems).singleElement().asString()
                .contains("AspectJ").contains("spring-boot-starter-aop");
    }

    @Test
    @DisplayName("Spring Data нет вовсе → про репозитории молчим")
    void silentWithoutSpringDataAtAll() {
        // Прямой негатив к двум тестам выше: правило узкое — «фичи нет вовсе, молчим».
        // Без него обе ветки можно было бы сделать безусловными, и тесты остались бы зелёными.
        // Мутация: убрать springData из условия любой ветки → RED.
        assertThat(ActivationDiagnostics.problems(Set.<String>of()::contains, true)).isEmpty();
    }

    @Test
    @DisplayName("выключатель -Dallure.spring.diagnostics=off глушит и новость noteOnce")
    void switchSilencesNoteOnce() {
        // README и текст самой новости обещают потребителю этот тумблер. На канале noteOnce
        // он не был прибит ничем: снятие проверки давало 540 зелёных.
        // Мутация: убрать проверку SWITCH из noteOnce → RED.
        DiagnosticsReset.forget();
        String before = System.getProperty("allure.spring.diagnostics");
        System.setProperty("allure.spring.diagnostics", "off");
        try {
            List<LogRecord> said = LibraryLog.capture(() ->
                    ActivationDiagnostics.noteOnce("SwitchAxis", "новость, которую просили заглушить"));

            assertThat(said).as("выключатель обещан в README и в тексте самой новости, "
                    + "но канал noteOnce его не слушает").isEmpty();
        } finally {
            // Свойство глобальное: не вернуть его — значит заглушить диагностику всему прогону.
            if (before == null) {
                System.clearProperty("allure.spring.diagnostics");
            } else {
                System.setProperty("allure.spring.diagnostics", before);
            }
        }
    }

    @Test
    @DisplayName("потолок запомненного не заставляет библиотеку замолчать")
    void capKeepsTalkingNotSilent() {
        // Потолок нужен от нарушения контракта «message — константа»: на переменном тексте
        // множество росло бы до конца JVM. Но дойдя до него, библиотека обязана ПОВТОРЯТЬСЯ,
        // а не глохнуть: первая редакция возвращала false и затыкала все последующие новости,
        // включая законные константные — ровно ту тихую потерю, против которой класс и заведён.
        // Мутация: вернуть порядок операндов (size() < LIMIT && add(...)) → RED.
        // ⚠️ Единственный тест, который НАМЕРЕННО забивает общее множество до потолка.
        // За собой обязан прибрать: на потолке дедупликация выключена, и сосед
        // noteOnceSaysItOnlyOnce, увидев полное множество, получил бы две строки вместо одной.
        // Замерено — без finally он краснеет при runOrder=random.
        DiagnosticsReset.forget();
        String component = "CapAxis" + UUID.randomUUID();
        try {
            List<LogRecord> said = LibraryLog.capture(() -> {
                for (int i = 0; i < 70; i++) {          // 70 > SAID_LIMIT (64)
                    ActivationDiagnostics.noteOnce(component, "переменный текст " + i);
                }
                // Один и тот же текст ДВАЖДЫ. На потолке множество больше не пополняется,
                // значит дедупликация выключена и вторая строка обязана прозвучать.
                ActivationDiagnostics.noteOnce(component, "законная константная новость");
                ActivationDiagnostics.noteOnce(component, "законная константная новость");
            });

            // ЯКОРЬ на сам потолок, и он ДОЛЖЕН считать повтор, а не число разных строк:
            // при поднятом SAID_LIMIT все 70 текстов уникальны и прозвучали бы всё равно,
            // а вот повтор — только если дедупликация выключена, то есть потолок пробит.
            // Замерено: без этого якоря мутация SAID_LIMIT=1024 не краснела.
            assertThat(said.stream()
                    .filter(r -> r.getMessage().contains("законная константная новость")).count())
                    .as("потолок не пробит: дедупликация ещё работает, и проверяемая ветка "
                            + "(«на потолке говорим, но не запоминаем») не выполнялась")
                    .isEqualTo(2);
        } finally {
            DiagnosticsReset.forget();
        }
    }

    @Test
    @DisplayName("в src/main второй аргумент noteOnce — только константа")
    void noteOnceIsCalledWithConstantsOnly() throws Exception {
        // Контракт из javadoc noteOnce, и до сих пор его держала только внимательность
        // ревьюера. Цена нарушения двойная: переменный текст превращает «один раз» в «раз на
        // каждое значение» И уводит данные потребителя (имя бина, путь, значение свойства)
        // в лог, а оттуда во вложение «логи приложения», то есть в артефакт CI.
        // Мутация: подставить в любой вызов конкатенацию вместо константы → RED.
        // ⚠️ Ищем ВЫЗОВ, не форму первого аргумента. Первая редакция требовала, чтобы первым
        // стоял строковый литерал, — и вызов вида noteOnce(COMPONENT, NOTICE + x) не видела
        // вовсе. Замерено: под такой мутацией гейт оставался зелёным.
        Pattern call = Pattern.compile("noteOnce\\s*\\(([^;]*?)\\)\\s*;", Pattern.DOTALL);
        List<String> offenders = new ArrayList<>();
        int calls = 0;
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).sorted().toList()) {
                Matcher m = call.matcher(Files.readString(f));
                while (m.find()) {
                    calls++;
                    String[] args = m.group(1).split(",", 2);
                    if (args.length < 2) {
                        offenders.add(f + "  →  не разобрать аргументы: " + m.group(1));
                        continue;
                    }
                    // Константа — ИДЕНТИФИКАТОР (NOTICE, Foo.BAR). Литерал по месту тоже не
                    // годится: ключ дедупликации — текст, и держать его надо там, где он
                    // объявлен один раз.
                    String message = args[1].trim().replaceAll("\\s+", " ");
                    if (!message.matches("[A-Za-z_$][\\w$]*(\\.[A-Za-z_$][\\w$]*)*")) {
                        offenders.add(f + "  →  " + message);
                    }
                }
            }
        }

        // ЯКОРЬ: «нарушителей нет» неотличимо от «не нашёл ни одного вызова».
        assertThat(calls).as("гейт не нашёл ни одного вызова noteOnce — он проверяет пустоту")
                .isGreaterThan(0);
        assertThat(offenders).as("второй аргумент noteOnce обязан быть константой: переменный "
                + "текст ломает однократность и уводит данные потребителя в артефакт CI")
                .isEmpty();
    }

}
