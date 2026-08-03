package io.github.kolomyychenkoai.allure.spring.demo;

import io.github.kolomyychenkoai.allure.spring.support.CurrentReport;
import io.github.kolomyychenkoai.allure.spring.support.TestApp;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Уровень B: «живой» прогон через РЕАЛЬНУЮ регистрацию (spring.factories →
 * AllureAssertionsListener → байткод AssertJ). Обычные AssertJ-ассерты пишут шаги в
 * НАСТОЯЩИЙ отчёт (showcase), тест читает их через {@link CurrentReport}. Проверки — через
 * немой канал {@link CurrentReport#check}/{@link CurrentReport#assertStep} (не инструментируется,
 * не засоряет отчёт). Краснеет, если листенер не
 * зарегистрирован, матчер байткода сломан, имя шага съехало — или строковые/коллекционные
 * ассерты снова выпадут из отчёта (баг полноты иерархии AssertJ, чинился Reiterating-discovery).
 */
@SpringBootTest(classes = TestApp.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Epic("allure-spring-test")
@Feature("AssertJ")
class AssertJReportIT {

    @Test
    @DisplayName("AssertJ-ассерты (значение/строки/числа/коллекции) автоматически попадают в отчёт")
    void assertjAppearsInReport() {
        assertThat("laptop").isEqualTo("laptop");
        assertThat("laptop").startsWith("lap");          // строковый — был баг полноты
        assertThat(99).isGreaterThan(0);                 // comparable
        assertThat(List.of("a", "b")).contains("a");     // коллекция — был баг полноты
        assertThat(List.of("a", "b")).hasSize(2);

        CurrentReport.assertStep("Проверка: значение laptop — isEqualTo laptop");
        CurrentReport.assertStep("Проверка: значение laptop — startsWith lap");
        CurrentReport.assertStep("Проверка: значение 99 — isGreaterThan 0");
        CurrentReport.assertStep("Проверка: значение [a, b] — contains [a]");
        CurrentReport.assertStep("Проверка: значение [a, b] — hasSize 2");
    }

    @Test
    @DisplayName("проверки, объявленные в абстрактных классах AssertJ (isBetween/isCloseTo/поля объекта)")
    void assertjFamiliesDeclaredInAbstractClassesAppearInReport() {
        // Эти семьи объявлены в АБСТРАКТНЫХ классах, которые сами падали при трансформации
        // (публичный конструктор): isCloseTo — в AbstractDoubleAssert, hasFieldOrPropertyWithValue —
        // в AbstractObjectAssert. Наследовать их неоткуда, поэтому шага не было ВООБЩЕ.
        // Пока витрина их не показывала, вопрос «ломает ли not(isConstructor())» был неразрешим
        // прогоном: зелёная сборка ничего не доказывала. Теперь разрешим — см. ADR 0001.
        assertThat(5).isBetween(1, 10);                                   // AbstractComparableAssert
        assertThat(1.5).isCloseTo(1.4, within(0.2));                      // AbstractDoubleAssert
        assertThat(new Order("laptop")).hasFieldOrPropertyWithValue("name", "laptop"); // AbstractObjectAssert

        CurrentReport.assertStep("Проверка: значение 5 — isBetween 1, 10");
        CurrentReport.assertStep("Проверка: значение 1.5 — isCloseTo 1.4, Offset[value=0.2]");
        CurrentReport.assertStep("Проверка: значение Order[name=laptop] — hasFieldOrPropertyWithValue name, laptop");
    }

    /** Мишень для проверки полей объекта (hasFieldOrPropertyWithValue объявлен в AbstractObjectAssert). */
    record Order(String name) {
    }
}
