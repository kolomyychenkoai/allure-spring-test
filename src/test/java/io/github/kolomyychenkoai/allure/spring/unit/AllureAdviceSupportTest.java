package io.github.kolomyychenkoai.allure.spring.unit;

import io.qameta.allure.Epic;
import io.github.kolomyychenkoai.allure.spring.internal.AllureAdviceSupport;

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
@Epic("Внутренние проверки библиотеки")
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
    @DisplayName("safe: ПРИМИТИВНЫЙ массив тоже печатается поэлементно, а не [B@4a3f")
    void safePrimitiveArrays() {
        // имя шага читает человек: «Проверка: значение [B@4a3f — containsExactly [I@6d06» бесполезно
        assertThat(AllureAdviceSupport.safe(new int[]{1, 2, 3})).isEqualTo("[1, 2, 3]");
        assertThat(AllureAdviceSupport.safe(new long[]{7L})).isEqualTo("[7]");
        assertThat(AllureAdviceSupport.safe(new boolean[]{true, false})).isEqualTo("[true, false]");
        assertThat(AllureAdviceSupport.safe(new char[]{'a'})).isEqualTo("[a]");
        assertThat(AllureAdviceSupport.safe(new byte[]{1, 2})).isEqualTo("[1, 2]");
        // двоичное поэлементно нечитаемо — показываем размер (как в SQL-вложениях)
        assertThat(AllureAdviceSupport.safe(new byte[100])).isEqualTo("<двоичные данные, 100 байт>");
        // длинный массив не раздувает имя шага
        assertThat(AllureAdviceSupport.safe(new int[80])).contains("всего 80");
    }

    @Test
    @DisplayName("safe: лямбда и method-reference → «<лямбда>», а не Demo$$Lambda/0x…@1a2b")
    void safeLambda() {
        Runnable lambda = () -> {
        };
        assertThat(AllureAdviceSupport.safe(lambda)).isEqualTo("<лямбда>");
        assertThat(AllureAdviceSupport.safe((Runnable) AllureAdviceSupportTest::helper)).isEqualTo("<лямбда>");
        // varargs приходят МАССИВОМ (AssertJ satisfies — это Consumer<T>...): элементы обязаны
        // проходить ту же очистку, иначе лямбда внутри массива печаталась бы хэшем
        assertThat(AllureAdviceSupport.safe(new Object[]{lambda})).isEqualTo("[<лямбда>]");
    }

    @Test
    @DisplayName("safe: объект без своего toString → «<Класс>», а не Класс@хэш")
    void safeIdentityToString() {
        assertThat(AllureAdviceSupport.safe(new NoToString())).isEqualTo("<NoToString>");
    }

    @Test
    @DisplayName("АНТИ-правило: настоящий toString не подменяется (в т.ч. с «@» внутри)")
    void safeKeepsRealToString() {
        // проверка «идентичного toString» точная, поэтому легитимные значения не страдают
        assertThat(AllureAdviceSupport.safe(new WithToString())).isEqualTo("Widget{name=gadget}");
        assertThat(AllureAdviceSupport.safe("user@example.com")).isEqualTo("user@example.com");
        // Hamcrest-матчер рендерится через describeTo (BaseMatcher.toString переопределён) — не трогаем
        assertThat(AllureAdviceSupport.safe(org.hamcrest.Matchers.is("x"))).contains("\"x\"").doesNotContain("@");
    }

    private static void helper() {
    }

    private static final class NoToString {
    }

    private static final class WithToString {
        @Override
        public String toString() {
            return "Widget{name=gadget}";
        }
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
    @DisplayName("safe: имя шага — ОДНА строка (многострочное значение рвало бы вёрстку отчёта)")
    void safeCollapsesWhitespace() {
        assertThat(AllureAdviceSupport.safe("{\n  \"a\": 1\n}")).isEqualTo("{ \"a\": 1 }");
        assertThat(AllureAdviceSupport.safe("строка\tс\tтабами")).isEqualTo("строка с табами");
    }

    @Test
    @DisplayName("safe: обрезка не рвёт суррогатную пару пополам")
    void safeKeepsSurrogatePairs() {
        String emoji = "x".repeat(499) + "🚀" + "y".repeat(100);
        String rendered = AllureAdviceSupport.safe(emoji);
        assertThat(rendered).doesNotContain("\uD83D").endsWith("…");
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
    @DisplayName("step: без активного тест-кейса в отчёт ничего не уходит")
    void stepNoActiveTestCase() {
        InMemoryAllure allure = new InMemoryAllure().install();
        try {
            // вне allure.run(...) активного кейса нет → step должен тихо вернуться, ничего не записав
            AllureAdviceSupport.step("вне кейса", null);
            assertThat(allure.wroteNothing()).isTrue();
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
