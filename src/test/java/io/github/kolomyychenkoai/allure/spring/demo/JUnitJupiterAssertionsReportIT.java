package io.github.kolomyychenkoai.allure.spring.demo;

import io.github.kolomyychenkoai.allure.spring.support.CurrentReport;
import io.github.kolomyychenkoai.allure.spring.support.TestApp;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

/**
 * Уровень B: «живой» прогон через РЕАЛЬНУЮ регистрацию (spring.factories → AllureAssertionsListener
 * → байткод org.junit.jupiter.api.Assertions). JUnit-ассерты здесь — САМ предмет показа: их вызовы
 * пишут шаги в настоящий отчёт (showcase), а тест читает их через {@link CurrentReport}.
 * <p>
 * ⚠️ Verify — ТОЛЬКО через {@code CurrentReport.assertStep/check} (немой канал), НЕ через JUnit
 * assertTrue: иначе verify-ассерт сам стал бы шагом. Краснеет при снятии регистрации, поломке
 * матчера байткода или регрессе имени шага.
 */
@SpringBootTest(classes = TestApp.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Epic("allure-spring-test")
@Feature("JUnit Jupiter ассерты")
class JUnitJupiterAssertionsReportIT {

    @Test
    @DisplayName("JUnit-ассерты автоматически попадают в отчёт шагами (полная цепочка)")
    void junitAssertionsAppearInReport() {
        Assertions.assertEquals("laptop", "laptop");
        Assertions.assertTrue(2 > 0, "количество положительно");
        Assertions.assertNotNull("id-1");
        Assertions.assertInstanceOf(String.class, "id-1");
        IllegalStateException caught = Assertions.assertThrows(IllegalStateException.class,
                () -> { throw new IllegalStateException("boom"); });

        List<String> steps = CurrentReport.stepNames();
        // мутация «off install()» → любой из этих шагов пропадёт → RED
        CurrentReport.assertStep("Проверка: ожидалось laptop = laptop");
        CurrentReport.assertStep("Проверка: количество положительно — верно");
        CurrentReport.assertStep("Проверка: значение id-1 не null");
        CurrentReport.assertStep("Проверка: значение id-1 — экземпляр String");
        CurrentReport.assertStep("Проверка: брошено IllegalStateException");

        // ровно ОДИН шаг на один assertEquals (фасад не само-делегирует; иначе было бы 2)
        long eq = steps.stream().filter("Проверка: ожидалось laptop = laptop"::equals).count();
        CurrentReport.check(eq == 1, () -> "ожидался 1 шаг assertEquals, а их " + eq + ": " + steps);
        // assertThrows реально поймал ожидаемое исключение (драйвер отработал)
        CurrentReport.check(caught != null, () -> "assertThrows не вернул исключение");
    }

    @Test
    @DisplayName("остальные семейства ассертов Jupiter (12 из 17 матчера) тоже дают шаги")
    void remainingAssertionFamiliesAppearInReport() {
        // Матчер перехватывает 17 имён, витрина показывала 5 — то есть 12 веток разбора
        // не проверялись НИЧЕМ на живой цепочке. Там же живёт хрупкая эвристика «сообщение —
        // последний параметр String/Supplier по дескриптору»: берём перегрузки И с сообщением,
        // И без, чтобы разъезд разбора дескриптора был виден.
        Assertions.assertNotEquals("laptop", "phone");
        Assertions.assertFalse(1 > 2, "единица не больше двух");
        Assertions.assertNull(null, "значения нет");
        Assertions.assertSame(this, this);
        Assertions.assertNotSame(new Object(), new Object());
        Assertions.assertArrayEquals(new int[]{1, 2}, new int[]{1, 2});
        Assertions.assertIterableEquals(List.of("a"), List.of("a"));
        Assertions.assertLinesMatch(List.of("строка"), List.of("строка"));
        Assertions.assertThrowsExactly(IllegalArgumentException.class,
                () -> { throw new IllegalArgumentException("точный тип"); });
        String value = Assertions.assertDoesNotThrow(() -> "ок");
        Assertions.assertTimeout(java.time.Duration.ofSeconds(5), () -> "успели");

        List<String> steps = CurrentReport.stepNames();
        CurrentReport.check(value.equals("ок"), () -> "assertDoesNotThrow не вернул значение");
        for (String expected : new String[]{
                "Проверка: laptop ≠ phone",
                "Проверка: массивы равны",
                "Проверка: коллекции равны",
                "Проверка: строки совпали",
                "Проверка: брошено IllegalArgumentException",
                "Проверка: без исключения",
                "Проверка: уложились в таймаут"}) {
            CurrentReport.check(steps.contains(expected), () -> "нет шага «" + expected + "»: " + steps);
        }
        // identity-toString в именах больше не течёт: «<Object>», а не «java.lang.Object@1a2b»
        CurrentReport.check(steps.stream().anyMatch(n -> n.startsWith("Проверка: разные объекты") && n.contains("<Object>")),
                () -> "в имени шага должен быть читаемый «<Object>»: " + steps);
        // сообщение из перегрузки со String действительно попадает в имя шага (эвристика жива)
        CurrentReport.check(steps.stream().anyMatch(n -> n.contains("единица не больше двух")),
                () -> "сообщение assertFalse не попало в шаг: " + steps);
        CurrentReport.check(steps.stream().anyMatch(n -> n.contains("значения нет")),
                () -> "сообщение assertNull не попало в шаг: " + steps);
    }
}
