package io.github.kolomyychenkoai.allure.spring.assertion;

import io.github.kolomyychenkoai.allure.spring.support.InMemoryAllure;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.model.TestResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.AssertionErrors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Уровень A: детерминированная проверка содержимого отчёта для Spring-ассертов.
 * Инструментирование ставится один раз, ассерты зовутся напрямую — без Spring-контекста.
 */
@Epic("allure-spring-test")
@Feature("Spring-ассерты")
class AllureSpringAssertionsTest {

    @BeforeAll
    static void install() {
        AllureSpringAssertionsInstrumentation.install();
    }

    private InMemoryAllure allure;

    @BeforeEach
    void setUp() {
        allure = new InMemoryAllure().install();
    }

    @AfterEach
    void tearDown() {
        allure.uninstall();
    }

    @Test
    @DisplayName("assertEquals: шаг с message, expected и actual")
    void logsAssertEquals() {
        TestResult result = allure.run("eq", () ->
                AssertionErrors.assertEquals("имя", "laptop", "laptop"));

        assertThat(allure.hasStep(result, "Проверка: имя — ожидалось laptop = laptop")).isTrue();
    }

    @Test
    @DisplayName("assertTrue и assertNotNull логируются шагами")
    void logsAssertTrueAndNotNull() {
        TestResult result = allure.run("tn", () -> {
            AssertionErrors.assertTrue("количество положительно", true);
            AssertionErrors.assertNotNull("есть id", "id-1");
        });

        assertThat(allure.hasStep(result, "Проверка: количество положительно — верно")).isTrue();
        assertThat(allure.hasStep(result, "Проверка: есть id — значение id-1 не null")).isTrue();
    }

    @Test
    @DisplayName("assertNotEquals и assertNull логируются шагами")
    void logsAssertNotEqualsAndNull() {
        TestResult result = allure.run("ne", () -> {
            AssertionErrors.assertNotEquals("цена не ноль", 0, 100);
            AssertionErrors.assertNull("ссылка пуста", null);
        });

        assertThat(allure.hasStep(result, "Проверка: цена не ноль — 0 ≠ 100")).isTrue();
        assertThat(allure.hasStep(result, "Проверка: ссылка пуста — значение null")).isTrue();
    }

    @Test
    @DisplayName("assertFalse (проходящий) логируется; fail шага не создаёт")
    void logsAssertFalseAndFailMakesNoStep() {
        TestResult result = allure.run("ff", () -> {
            AssertionErrors.assertFalse("не должно быть нулём", false);
            try {
                AssertionErrors.fail("принудительный провал");
            } catch (AssertionError ignored) {
                // fail всегда бросает — ловим, чтобы тест дошёл до конца
            }
        });

        assertThat(allure.hasStep(result, "Проверка: не должно быть нулём — неверно")).isTrue();
        // упавший fail шага не создаёт — падение показывает Allure на уровне теста
        assertThat(result.getSteps().stream()
                .noneMatch(s -> s.getName().startsWith("Проверка провалена"))).isTrue();
    }

    @Test
    @DisplayName("упавший ассерт шага НЕ создаёт (падение покажет Allure)")
    void failedAssertProducesNoStep() {
        TestResult result = allure.run("fail-eq", () ->
                assertThatThrownBy(() -> AssertionErrors.assertEquals("имя", "laptop", "phone"))
                        .isInstanceOf(AssertionError.class));

        assertThat(result.getSteps().stream()
                .noneMatch(s -> s.getName().startsWith("Проверка: имя"))).isTrue();
    }

    @Test
    @DisplayName("после падения делегирующего ассерта счётчик глубины не «залипает» — следующий assertTrue логируется")
    void depthCounterDoesNotLeakAfterFailure() {
        TestResult result = allure.run("leak", () -> {
            assertThatThrownBy(() -> AssertionErrors.assertNotNull("должен быть", null))
                    .isInstanceOf(AssertionError.class);
            AssertionErrors.assertTrue("после провала", true);
        });

        // если бы счётчик утёк (остался > 0), этот шаг был бы проглочен как «внутренний»
        assertThat(allure.hasStep(result, "Проверка: после провала — верно")).isTrue();
    }

    @Test
    @DisplayName("упавший assertFalse шага не создаёт")
    void failingAssertFalseProducesNoStep() {
        TestResult result = allure.run("ff-fail", () ->
                assertThatThrownBy(() -> AssertionErrors.assertFalse("должно быть ложью", true))
                        .isInstanceOf(AssertionError.class));

        assertThat(result.getSteps().stream()
                .noneMatch(s -> s.getName().contains("должно быть ложью"))).isTrue();
        assertThat(result.getSteps().stream()
                .noneMatch(s -> s.getName().startsWith("Проверка провалена"))).isTrue();
    }

    @Test
    @DisplayName("упавший assertTrue шага не создаёт")
    void failingAssertTrueProducesNoStep() {
        TestResult result = allure.run("tt-fail", () ->
                assertThatThrownBy(() -> AssertionErrors.assertTrue("должно быть истиной", false))
                        .isInstanceOf(AssertionError.class));

        assertThat(result.getSteps().stream()
                .noneMatch(s -> s.getName().contains("должно быть истиной"))).isTrue();
    }
}

