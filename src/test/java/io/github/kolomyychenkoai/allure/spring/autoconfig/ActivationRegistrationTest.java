package io.github.kolomyychenkoai.allure.spring.autoconfig;

import io.github.kolomyychenkoai.allure.spring.support.TestApp;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.annotation.ImportCandidates;
import org.springframework.core.io.support.SpringFactoriesLoader;
import org.springframework.test.context.TestContextManager;
import org.springframework.test.context.TestExecutionListener;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Тесты АКТИВАЦИИ библиотеки. Вся её ценность держится на двух файлах-регистраторах:
 * {@code META-INF/spring.factories} (13 {@code TestExecutionListener}) и
 * {@code META-INF/spring/…AutoConfiguration.imports} (4 автоконфига). Ни строчки кода
 * потребителя — только эти файлы.
 * <p>
 * <b>Почему это отдельный тест.</b> Если Spring сменит механизм discovery (или переименует
 * класс, чьё имя зашито в ИМЯ файла imports), умрёт вся библиотека СРАЗУ и МОЛЧА: контекст
 * поднимется, тесты пройдут, отчёт будет пустым. Косвенно это увидят 19 демо-{@code *IT},
 * но по 19 красным тестам причину искать долго — здесь она названа прямо.
 * <p>
 * Проверяем не «что написано в файле», а что настоящий механизм Spring его ВИДИТ:
 * {@link SpringFactoriesLoader} и {@link ImportCandidates} — те же классы, которыми
 * пользуются Spring Test и Spring Boot.
 */
class ActivationRegistrationTest {

    /** Полный инвентарь листенеров из spring.factories (держать в синхроне с файлом). */
    private static final List<String> EXPECTED_LISTENERS = List.of(
            "io.github.kolomyychenkoai.allure.spring.logs.AllureApplicationLogsListener",
            "io.github.kolomyychenkoai.allure.spring.config.AllureConfigurationListener",
            "io.github.kolomyychenkoai.allure.spring.rest.AllureRestAssuredListener",
            "io.github.kolomyychenkoai.allure.spring.rest.AllureMockMvcListener",
            "io.github.kolomyychenkoai.allure.spring.rest.AllureRestTemplateListener",
            "io.github.kolomyychenkoai.allure.spring.rest.AllureRestClientListener",
            "io.github.kolomyychenkoai.allure.spring.rest.AllureWebTestClientListener",
            "io.github.kolomyychenkoai.allure.spring.wiremock.AllureWireMockTestListener",
            "io.github.kolomyychenkoai.allure.spring.assertion.AllureAssertionsListener",
            "io.github.kolomyychenkoai.allure.spring.kafka.AllureKafkaListener",
            "io.github.kolomyychenkoai.allure.spring.data.AllureJdbcListener",
            "io.github.kolomyychenkoai.allure.spring.liquibase.AllureLiquibaseListener",
            "io.github.kolomyychenkoai.allure.spring.awaitility.AllureAwaitilityListener");

    private static final List<String> EXPECTED_AUTOCONFIGS = List.of(
            "io.github.kolomyychenkoai.allure.spring.rest.AllureMockMvcAutoConfiguration",
            "io.github.kolomyychenkoai.allure.spring.rest.AllureWebTestClientAutoConfiguration",
            "io.github.kolomyychenkoai.allure.spring.data.AllureDataJpaAutoConfiguration",
            "io.github.kolomyychenkoai.allure.spring.data.AllureDataSourceAutoConfiguration");

    @Test
    @DisplayName("spring.factories: все 13 листенеров видны настоящим SpringFactoriesLoader")
    void listenersDiscoverableBySpringFactoriesLoader() {
        // FailureHandler.ignoring: в spring.factories лежат и ЧУЖИЕ листенеры, часть из которых
        // не инстанцируется без своих зависимостей (micrometer). Нас интересуют только свои —
        // если НАШ не создастся, он просто не попадёт в список и тест покраснеет.
        List<String> found = SpringFactoriesLoader.forDefaultResourceLocation(getClass().getClassLoader())
                .load(TestExecutionListener.class,
                        SpringFactoriesLoader.FailureHandler.handleMessage((message, failure) -> {
                        })).stream()
                .map(listener -> listener.getClass().getName())
                .filter(name -> name.startsWith("io.github.kolomyychenkoai"))
                .toList();

        assertThat(found)
                .as("если механизм discovery TestExecutionListener через spring.factories уедет, "
                        + "вся библиотека выключится молча — отчёт станет пустым при зелёных тестах")
                .containsExactlyInAnyOrderElementsOf(EXPECTED_LISTENERS);
    }

    @Test
    @DisplayName("листенеры реально попадают в цепочку TestContextManager обычного @SpringBootTest")
    void listenersReachTestContextManager() {
        // сквозная проверка: не только «файл читается», но и «Spring Test взял их в работу»
        List<String> ours = new TestContextManager(TestApp.class).getTestExecutionListeners().stream()
                .map(listener -> listener.getClass().getName())
                .filter(name -> name.startsWith("io.github.kolomyychenkoai"))
                .toList();

        assertThat(ours).containsExactlyInAnyOrderElementsOf(EXPECTED_LISTENERS);
    }

    @Test
    @DisplayName("AutoConfiguration.imports: файл читается механизмом Boot и содержит 4 автоконфига")
    void autoConfigurationsDiscoverableByBoot() {
        // ИМЯ файла — это FQCN аннотации Boot: переименуют/переместят её при апгрейде,
        // и файл перестанет читаться. Тогда молча отвалятся кастомайзеры MockMvc/WebTestClient,
        // аспект репозиториев и обёртка DataSource.
        List<String> candidates = new java.util.ArrayList<>();
        ImportCandidates.load(org.springframework.boot.autoconfigure.AutoConfiguration.class,
                getClass().getClassLoader()).forEach(candidates::add);

        assertThat(candidates).containsAll(EXPECTED_AUTOCONFIGS);
    }

    @Test
    @DisplayName("все зарегистрированные классы действительно грузятся (файлы не разъехались с кодом)")
    void registeredClassesLoad() {
        for (String name : EXPECTED_LISTENERS) {
            assertThat(loadable(name)).as("листенер " + name + " указан в spring.factories, но не грузится").isTrue();
        }
        for (String name : EXPECTED_AUTOCONFIGS) {
            assertThat(loadable(name)).as("автоконфиг " + name + " указан в imports, но не грузится").isTrue();
        }
    }

    private static boolean loadable(String className) {
        try {
            Class.forName(className, false, ActivationRegistrationTest.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }
}
