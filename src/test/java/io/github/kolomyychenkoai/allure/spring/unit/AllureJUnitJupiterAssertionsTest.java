package io.github.kolomyychenkoai.allure.spring.unit;

import io.github.kolomyychenkoai.allure.spring.assertion.internal.AllureJUnitJupiterAssertionsInstrumentation;
import io.github.kolomyychenkoai.allure.spring.support.InMemoryAllure;
import io.qameta.allure.model.StepResult;
import io.qameta.allure.model.TestResult;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Уровень A: содержимое отчёта для JUnit Jupiter ассертов. Инструментирование ставится один раз,
 * РЕАЛЬНЫЕ {@code Assertions.*} зовутся напрямую (через байткод), проверяем через AssertJ ВНЕ
 * активного кейса (гейт активного кейса не даёт verify-ассертам засорять). InMemoryAllure не
 * восстанавливаем через new — {@link InMemoryAllure#uninstall} возвращает прежний lifecycle.
 */
class AllureJUnitJupiterAssertionsTest {

    @BeforeAll
    static void install() {
        AllureJUnitJupiterAssertionsInstrumentation.install();
    }

    private InMemoryAllure allure;

    @BeforeEach
    void setUp() {
        allure = new InMemoryAllure().install();
    }

    // uninstall в конце каждого теста через try-finally внутри run() не нужен — run() сам stop-ит кейс;
    // глобальный lifecycle возвращаем в tearDown
    @org.junit.jupiter.api.AfterEach
    void tearDown() {
        allure.uninstall();
    }

    private List<String> names(TestResult r) {
        return r.getSteps().stream().map(StepResult::getName).toList();
    }

    // ── Матрица перегрузок: message vs delta vs Supplier разбирается по СТАТИЧЕСКОЙ сигнатуре ──

    @Test
    @DisplayName("assertEquals: int/long/double/Object без сообщения — «ожидалось X = Y»")
    void equalsNoMessageAxes() {
        assertThat(names(allure.run("i", () -> Assertions.assertEquals(200, 200))))
                .contains("Проверка: ожидалось 200 = 200");
        assertThat(names(allure.run("l", () -> Assertions.assertEquals(5L, 5L))))
                .contains("Проверка: ожидалось 5 = 5");
        assertThat(names(allure.run("o", () -> Assertions.assertEquals("laptop", "laptop"))))
                .contains("Проверка: ожидалось laptop = laptop");
    }

    @Test
    @DisplayName("один assertEquals → РОВНО один шаг (фасад не само-делегирует → депт-счётчик не нужен)")
    void singleAssertOneStep() {
        // если JUnit заведёт само-делегацию фасад→фасад (обе точки перехвачены), станет 2 → RED.
        // Это проверка допущения «депт-счётчик не нужен» на РЕАЛЬНОЙ версии Jupiter.
        long steps = names(allure.run("one", () -> Assertions.assertEquals(7, 7))).stream()
                .filter(n -> n.equals("Проверка: ожидалось 7 = 7")).count();
        assertThat(steps).isEqualTo(1);
    }

    @Test
    @DisplayName("assertEquals(double,double,delta): 3-й арг — DELTA, НЕ сообщение (по сигнатуре)")
    void equalsDoubleDeltaIsNotMessage() {
        // мутация «последний арг = сообщение» → «Проверка: 0.01 — ожидалось …» → этот тест RED
        assertThat(names(allure.run("d", () -> Assertions.assertEquals(1.0, 1.0, 0.01))))
                .contains("Проверка: ожидалось 1.0 = 1.0")
                .noneMatch(n -> n.contains("0.01"));
    }

    @Test
    @DisplayName("assertEquals(...,String): последний String — это СООБЩЕНИЕ")
    void equalsWithStringMessage() {
        assertThat(names(allure.run("m", () -> Assertions.assertEquals("a", "a", "цена совпала"))))
                .contains("Проверка: цена совпала — ожидалось a = a");
    }

    @Test
    @DisplayName("assertArrayEquals(int[],int[]) — «массивы равны»")
    void arrayEquals() {
        assertThat(names(allure.run("arr", () -> Assertions.assertArrayEquals(new int[]{1, 2}, new int[]{1, 2}))))
                .contains("Проверка: массивы равны");
    }

    // ── Словарь Spring для true/false/null/notNull ──

    @Test
    @DisplayName("assertTrue/False/Null/NotNull — словарь Spring, с сообщением и без")
    void springVocabulary() {
        assertThat(names(allure.run("t0", () -> Assertions.assertTrue(true))))
                .contains("Проверка: условие верно");
        assertThat(names(allure.run("t1", () -> Assertions.assertTrue(true, "цена положительна"))))
                .contains("Проверка: цена положительна — верно");
        assertThat(names(allure.run("f0", () -> Assertions.assertFalse(false))))
                .contains("Проверка: условие неверно");
        assertThat(names(allure.run("n0", () -> Assertions.assertNull(null))))
                .contains("Проверка: значение null");
        assertThat(names(allure.run("nn", () -> Assertions.assertNotNull("id-1"))))
                .contains("Проверка: значение id-1 не null");
    }

    @Test
    @DisplayName("assertInstanceOf — «значение X — экземпляр <Тип>»")
    void instanceOf() {
        assertThat(names(allure.run("io", () -> Assertions.assertInstanceOf(String.class, "x"))))
                .contains("Проверка: значение x — экземпляр String");
    }

    // ── assertThrows: успех = ПОЙМАНО (возврат), не падение ──

    @Test
    @DisplayName("assertThrows успешный (поймал ожидаемое) → шаг «брошено <Тип>»")
    void throwsSuccess() {
        assertThat(names(allure.run("th", () -> Assertions.assertThrows(
                IllegalStateException.class, () -> {
                    throw new IllegalStateException("boom");
                }))))
                .contains("Проверка: брошено IllegalStateException");
    }

    @Test
    @DisplayName("assertThrows провальный (ничего не брошено) → шага НЕ создаёт")
    void throwsFailureNoStep() {
        // сам assertThrows бросит AssertionFailedError → @Thrown != null → шага нет.
        // ловим это падение снаружи, чтобы тест не упал; мутация «снять гейт thrown» → ложный PASSED-шаг
        TestResult result = allure.run("thf", () -> {
            try {
                Assertions.assertThrows(IllegalStateException.class, () -> { /* не бросает */ });
            } catch (AssertionError expected) {
                // ожидаемо: assertThrows сам провалился
            }
        });
        assertThat(names(result)).noneMatch(n -> n.startsWith("Проверка: брошено"));
    }

    // ── Депт-счётчик НЕ нужен: вложенные проверки дают ДВА шага (не схлопываются) ──

    @Test
    @DisplayName("вложенный assert внутри assertDoesNotThrow → ДВА шага (нет дедупа)")
    void nestedNotDeduped() {
        // мутация «добавить депт-счётчик а-ля Spring» → вложенный шаг исчезнет → RED
        List<String> steps = names(allure.run("nest", () ->
                Assertions.assertDoesNotThrow(() -> Assertions.assertEquals(1, 1))));
        assertThat(steps).contains("Проверка: без исключения");        // внешний
        assertThat(steps).contains("Проверка: ожидалось 1 = 1");        // вложенный
    }

    // ── Supplier-сообщение НЕ резолвится (.get() не зовём — без побочек) ──

    @Test
    @DisplayName("Supplier<String>-сообщение НЕ дёргается на успехе (без побочек)")
    void supplierMessageNoSideEffect() {
        int[] counter = {0};
        List<String> steps = names(allure.run("sup", () ->
                Assertions.assertEquals(1, 1, () -> {
                    counter[0]++;
                    return "дорогое сообщение";
                })));
        // мутация «резолвить .get()» → counter[0]==1 → RED
        assertThat(counter[0]).isZero();
        assertThat(steps).contains("Проверка: ожидалось 1 = 1"); // без текста Supplier-сообщения
    }

    // ── Исключения: fail/assertAll фасадного шага НЕ дают; preemptive — даёт ──

    @Test
    @DisplayName("fail и assertAll фасадного шага не создают (исключены из матчера)")
    void excludedMethodsNoFacadeStep() {
        TestResult failRes = allure.run("fail", () -> {
            try {
                Assertions.fail("boom");
            } catch (AssertionError expected) {
                // fail всегда бросает
            }
        });
        assertThat(names(failRes)).noneMatch(n -> n.startsWith("Проверка:"));

        // assertAll успешный: сам фасадного шага не даёт; вложенные Executable дали бы свои (тут пустые)
        TestResult allRes = allure.run("all", () -> Assertions.assertAll(() -> { }, () -> { }));
        assertThat(names(allRes)).noneMatch(n -> n.startsWith("Проверка:"));
    }

    @Test
    @DisplayName("assertTimeout/Preemptively успешные → внешний шаг «уложились в таймаут»")
    void timeoutSteps() {
        assertThat(names(allure.run("to", () ->
                Assertions.assertTimeout(Duration.ofSeconds(5), () -> { }))))
                .anyMatch(n -> n.startsWith("Проверка: уложились в таймаут"));
        assertThat(names(allure.run("tp", () ->
                Assertions.assertTimeoutPreemptively(Duration.ofSeconds(5), () -> "ok"))))
                .anyMatch(n -> n.startsWith("Проверка: уложились в таймаут"));
    }

    @Test
    @DisplayName("упавший assertEquals шага НЕ создаёт (падение — зона Allure)")
    void failedAssertionNoStep() {
        TestResult result = allure.run("fe", () -> {
            try {
                Assertions.assertEquals(1, 2);
            } catch (AssertionError expected) {
                // ожидаемо
            }
        });
        assertThat(names(result)).noneMatch(n -> n.startsWith("Проверка:"));
    }
}
