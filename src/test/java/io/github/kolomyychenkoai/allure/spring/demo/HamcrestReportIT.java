package io.github.kolomyychenkoai.allure.spring.demo;

import io.github.kolomyychenkoai.allure.spring.support.CurrentReport;
import io.github.kolomyychenkoai.allure.spring.support.TestApp;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import org.hamcrest.MatcherAssert;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Уровень B: «живой» прогон через РЕАЛЬНУЮ регистрацию (spring.factories →
 * AllureAssertionsListener → байткод MatcherAssert). Hamcrest-ассерты пишут шаги в
 * настоящий отчёт (showcase); тест читает их через {@link CurrentReport}. Имена матчеров
 * (toString) разнятся по версиям — проверяем устойчивые части, формируемые НАШИМ кодом.
 */
@SpringBootTest(classes = TestApp.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Epic("allure-spring-test")
@Feature("Hamcrest")
class HamcrestReportIT {

    @Test
    @DisplayName("Hamcrest assertThat автоматически попадает в отчёт шагами")
    void hamcrestAppearsInReport() {
        MatcherAssert.assertThat("laptop", is("laptop"));
        MatcherAssert.assertThat("имя товара", "laptop", equalTo("laptop"));
        MatcherAssert.assertThat(2, greaterThan(0));
        MatcherAssert.assertThat("цена есть", 99, notNullValue());

        List<String> steps = CurrentReport.stepNames();
        CurrentReport.check(steps.stream().anyMatch(n -> n.startsWith("Проверка: значение laptop, ожидалось")),
                () -> "" + steps);
        CurrentReport.check(steps.stream().anyMatch(n -> n.startsWith("Проверка: имя товара: значение laptop, ожидалось")),
                () -> "" + steps);
        CurrentReport.check(steps.stream().anyMatch(n -> n.contains("значение 2, ожидалось")), () -> "" + steps);
        CurrentReport.check(steps.stream().anyMatch(n -> n.startsWith("Проверка: цена есть: значение 99, ожидалось")),
                () -> "" + steps);
    }

    @Test
    @DisplayName("описание матчера в шаге — РЕАЛЬНОЕ (describeTo), а не Класс@хэш")
    void matcherDescriptionIsRendered() {
        // Проверяем имя ЦЕЛИКОМ, а не начало («… ожидалось»): иначе деградация
        // «печатаем org.hamcrest.core.Is@1a2b вместо is "laptop"» пройдёт мимо.
        // Ожидаемый хвост вычисляет САМ Hamcrest — при смене формулировок в новой версии
        // обе стороны едут вместе, флака не будет.
        org.hamcrest.Matcher<String> matcher = org.hamcrest.Matchers.is("laptop");
        MatcherAssert.assertThat("laptop", matcher);

        CurrentReport.assertStep("Проверка: значение laptop, ожидалось "
                + org.hamcrest.StringDescription.toString(matcher));
    }
}
