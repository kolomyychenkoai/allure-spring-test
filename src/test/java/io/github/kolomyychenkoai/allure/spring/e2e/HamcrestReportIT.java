package io.github.kolomyychenkoai.allure.spring.e2e;

import io.github.kolomyychenkoai.allure.spring.support.TestApp;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import org.hamcrest.MatcherAssert;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Уровень B: «живой» прогон. Обычные Hamcrest-ассерты — НИКАКОГО Allure.step;
 * шаги «Проверка: …» появляются сами через байткод-инструментирование. Смотреть: mvn allure:serve.
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
    }
}
