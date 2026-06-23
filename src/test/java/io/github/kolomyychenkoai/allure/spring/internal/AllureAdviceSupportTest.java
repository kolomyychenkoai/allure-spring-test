package io.github.kolomyychenkoai.allure.spring.internal;

import io.github.kolomyychenkoai.allure.spring.support.InMemoryAllure;
import io.qameta.allure.model.Status;
import io.qameta.allure.model.StepResult;
import io.qameta.allure.model.TestResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Уровень A: общие хелперы inline-advice. Прямо проверяем безопасный рендер значений
 * ({@code safe}) и выбор статуса шага ({@code step}) — ветки, на которые опираются все
 * инструментирующие модули.
 */
class AllureAdviceSupportTest {

    @Test
    @DisplayName("safe: null и обычное значение рендерятся как String.valueOf")
    void safeNullAndPlain() {
        assertThat(AllureAdviceSupport.safe(null)).isEqualTo("null");
        assertThat(AllureAdviceSupport.safe("laptop")).isEqualTo("laptop");
        assertThat(AllureAdviceSupport.safe(42)).isEqualTo("42");
    }

    @Test
    @DisplayName("safe: массив печатается поэлементно (deepToString), а не [Ljava…")
    void safeArrayDeep() {
        assertThat(AllureAdviceSupport.safe(new Object[]{"a", "b"})).isEqualTo("[a, b]");
        assertThat(AllureAdviceSupport.safe(new Object[]{new Object[]{1, 2}})).isEqualTo("[[1, 2]]");
    }

    @Test
    @DisplayName("safe: бросающий toString не валит рендер — возвращается «<?>»")
    void safeThrowingToString() {
        Object boom = new Object() {
            @Override
            public String toString() {
                throw new IllegalStateException("boom");
            }
        };
        assertThat(AllureAdviceSupport.safe(boom)).isEqualTo("<?>");
    }

    @Test
    @DisplayName("safe: слишком длинное значение обрезается по лимиту с многоточием")
    void safeTruncatesLongValue() {
        String big = "x".repeat(1000);
        String rendered = AllureAdviceSupport.safe(big);
        assertThat(rendered).hasSize(501).endsWith("…");
    }

    @Test
    @DisplayName("step: успешная проверка → PASSED-шаг; упавшая → шага НЕТ")
    void stepLogsOnlySuccess() {
        InMemoryAllure allure = new InMemoryAllure().install();
        try {
            TestResult result = allure.run("steps", () -> {
                AllureAdviceSupport.step("ок", null);
                AllureAdviceSupport.step("упал", new RuntimeException("x"));
            });
            assertThat(step(result, "ок").getStatus()).isEqualTo(Status.PASSED);
            // упавшую проверку шагом не логируем — падение покажет Allure на уровне теста
            assertThat(result.getSteps().stream().noneMatch(s -> "упал".equals(s.getName()))).isTrue();
        } finally {
            allure.uninstall();
        }
    }

    @Test
    @DisplayName("step: без активного тест-кейса шаг не пишется и не бросает")
    void stepNoActiveTestCase() {
        InMemoryAllure allure = new InMemoryAllure().install();
        try {
            // вне allure.run(...) активного кейса нет → step должен тихо вернуться
            org.assertj.core.api.Assertions.assertThatCode(() ->
                    AllureAdviceSupport.step("вне кейса", null)).doesNotThrowAnyException();
        } finally {
            allure.uninstall();
        }
    }

    private StepResult step(TestResult result, String name) {
        return result.getSteps().stream()
                .filter(s -> name.equals(s.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("нет шага: " + name));
    }
}
