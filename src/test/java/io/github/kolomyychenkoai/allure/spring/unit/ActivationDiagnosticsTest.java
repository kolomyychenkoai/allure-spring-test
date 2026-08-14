package io.github.kolomyychenkoai.allure.spring.unit;

import io.github.kolomyychenkoai.allure.spring.internal.ActivationDiagnostics;
import io.github.kolomyychenkoai.allure.spring.internal.AllureInstrumentationLogger;
import io.qameta.allure.Epic;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

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
        // У потребителя за прогон поднимается десяток контекстов, и новость на каждый
        // превращается в шум, который перестают читать (этим кончилась проверка, снятая
        // из reportOnce — см. предупреждение в её javadoc).
        // Текст уникален на вызов: тест не зависит ни от порядка классов (runOrder=random),
        // ни от того, сказал ли кто-то ту же новость раньше в этой JVM.
        // Мутация: убрать дедупликацию по SAID → две записи → RED.
        String unique = "проверка однократности " + UUID.randomUUID();

        List<LogRecord> said = logWhile(() -> {
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
        ActivationDiagnostics.forgetForTests();
        String before = System.getProperty("allure.spring.diagnostics");
        System.setProperty("allure.spring.diagnostics", "off");
        try {
            List<LogRecord> said = logWhile(() ->
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
        ActivationDiagnostics.forgetForTests();
        String component = "CapAxis" + UUID.randomUUID();
        try {
            List<LogRecord> said = logWhile(() -> {
                for (int i = 0; i < 70; i++) {          // 70 > SAID_LIMIT (64)
                    ActivationDiagnostics.noteOnce(component, "переменный текст " + i);
                }
                ActivationDiagnostics.noteOnce(component, "законная константная новость");
            });

            assertThat(said).as("законная новость утонула вместе с нарушителем контракта: "
                            + "один переменный текст затыкает канал до конца JVM")
                    .anyMatch(r -> r.getMessage().contains("законная константная новость"));
        } finally {
            ActivationDiagnostics.forgetForTests();
        }
    }

    /** Слушаем логгер библиотеки: наружу новость видна ТОЛЬКО этой строкой. */
    private static List<LogRecord> logWhile(Runnable action) {
        List<LogRecord> records = new ArrayList<>();
        Logger logger = AllureInstrumentationLogger.logger();
        Handler collector = new Handler() {
            @Override
            public void publish(LogRecord record) {
                records.add(record);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        collector.setLevel(Level.ALL);
        logger.addHandler(collector);
        try {
            action.run();
        } finally {
            logger.removeHandler(collector);
        }
        return records;
    }
}
