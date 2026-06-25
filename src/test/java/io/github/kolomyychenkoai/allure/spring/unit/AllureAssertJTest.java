package io.github.kolomyychenkoai.allure.spring.unit;

import io.github.kolomyychenkoai.allure.spring.assertion.internal.AllureAssertJInstrumentation;
import io.github.kolomyychenkoai.allure.spring.support.InMemoryAllure;
import io.qameta.allure.model.StepResult;
import io.qameta.allure.model.TestResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Уровень A: детерминированная проверка содержимого отчёта для AssertJ. */
class AllureAssertJTest {

    @BeforeAll
    static void install() {
        AllureAssertJInstrumentation.install();
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

    private List<String> stepNames(TestResult result) {
        return result.getSteps().stream().map(StepResult::getName).toList();
    }

    @Test
    @DisplayName("isEqualTo даёт шаг «значение X — isEqualTo Y»")
    void logsIsEqualTo() {
        TestResult result = allure.run("eq", () -> assertThat("laptop").isEqualTo("laptop"));

        assertThat(stepNames(result))
                .anyMatch(n -> n.contains("значение laptop") && n.contains("isEqualTo laptop"));
    }

    @Test
    @DisplayName("цепочка ассертов даёт по шагу на каждый, isNotNull (blacklist) не логируется")
    void logsChainAndSkipsBlacklisted() {
        TestResult result = allure.run("chain", () ->
                assertThat("laptop").isNotNull().startsWith("lap").endsWith("top"));

        List<String> names = stepNames(result);
        assertThat(names).anyMatch(n -> n.contains("startsWith"));
        assertThat(names).anyMatch(n -> n.contains("endsWith"));
        assertThat(names).noneMatch(n -> n.contains("isNotNull")); // в blacklist
    }

    @Test
    @DisplayName("непройденный ассерт шага НЕ создаёт (падение покажет Allure)")
    void failedAssertProducesNoStep() {
        // внутри allure.run — провал ассерта-под-тестом ловим нейтральным JUnit assertThrows
        // (assertThatThrownBy сам инструментируется и залогировал бы лишний шаг в активный кейс)
        TestResult result = allure.run("fail", () ->
                assertThrows(AssertionError.class, () -> assertThat("laptop").isEqualTo("phone")));

        assertThat(stepNames(result)).noneMatch(n -> n.contains("isEqualTo phone"));
    }

    @Test
    @DisplayName("после падения счётчик глубины не «залипает» — следующий ассерт логируется")
    void depthBalancedAfterFailure() {
        TestResult result = allure.run("balance", () -> {
            assertThrows(AssertionError.class, () -> assertThat("a").isEqualTo("b"));
            assertThat("x").isEqualTo("x"); // если бы глубина утекла — шаг бы проглотился как внутренний
        });

        assertThat(stepNames(result)).anyMatch(n -> n.contains("значение x — isEqualTo x"));
    }

    @Test
    @DisplayName("конфигурационные методы (as) не логируются, многоарг и коллекции — да")
    void blacklistAndCollections() {
        TestResult result = allure.run("misc", () -> {
            assertThat("laptop").as("описание").isEqualTo("laptop"); // as — blacklist
            assertThat(5).isBetween(1, 10);                          // многоарг
            assertThat(List.of("a", "b")).contains("a").hasSize(2);  // коллекция
        });

        List<String> names = stepNames(result);
        assertThat(names).noneMatch(n -> n.contains(" — as "));
        assertThat(names).anyMatch(n -> n.contains("isBetween 1, 10")); // оба аргумента
        assertThat(names).anyMatch(n -> n.contains("contains [a]"));    // varargs развёрнут
        assertThat(names).anyMatch(n -> n.contains("hasSize 2"));
    }

    @Test
    @DisplayName("новые navigation/config-методы (map/size/usingDefaultElementComparator) шага НЕ создают")
    void newNavigationAndConfigSkipped() {
        TestResult result = allure.run("nav2", () -> {
            assertThat(List.of("ab", "cde")).map(String::length).contains(2);       // map — навигация
            assertThat(List.of("a", "b")).size().isGreaterThan(0);                  // size — навигация
            assertThat(List.of("a")).usingDefaultElementComparator().contains("a"); // config
        });

        List<String> names = stepNames(result);
        assertThat(names).noneMatch(n -> n.contains(" — map") || n.contains(" — size")
                || n.contains("usingDefaultElementComparator"));
        assertThat(names).anyMatch(n -> n.contains("contains"));
        assertThat(names).anyMatch(n -> n.contains("isGreaterThan"));
    }

    @Test
    @DisplayName("успешный ассерт даёт РОВНО один шаг (делегация в super не задваивает)")
    void successfulAssertSingleStep() {
        TestResult result = allure.run("dedup", () -> assertThat("laptop").isEqualTo("laptop"));

        long n = stepNames(result).stream().filter(s -> s.contains("isEqualTo")).count();
        assertThat(n).isEqualTo(1); // сломай дедуп (убери счётчик глубины) → станет 2
    }

    @Test
    @DisplayName("повторный install() не задваивает шаг (идемпотентность CAS)")
    void installIsIdempotent() {
        AllureAssertJInstrumentation.install(); // уже установлен в @BeforeAll — должен быть no-op
        AllureAssertJInstrumentation.install();

        TestResult result = allure.run("idem", () -> assertThat("x").isEqualTo("x"));

        long n = stepNames(result).stream().filter(s -> s.contains("isEqualTo")).count();
        assertThat(n).isEqualTo(1);
    }

    @Test
    @DisplayName("satisfies/matches ЛОГИРУЮТСЯ; навигация (get/extracting) — НЕТ")
    void logsRealAssertionsNotNavigation() {
        TestResult result = allure.run("nav", () -> {
            assertThat("laptop").satisfies(s -> { });
            assertThat("laptop").matches(s -> !s.isEmpty());
            assertThat(java.util.Optional.of("x")).get().isEqualTo("x"); // get — навигация
            assertThat(List.of("a")).extracting(o -> o).contains("a");    // extracting — навигация
        });

        List<String> names = stepNames(result);
        assertThat(names).anyMatch(n -> n.contains("satisfies"));
        assertThat(names).anyMatch(n -> n.contains("matches"));
        assertThat(names).noneMatch(n -> n.contains(" — get"));      // навигация не логируется
        assertThat(names).noneMatch(n -> n.contains("extracting"));
    }
}
