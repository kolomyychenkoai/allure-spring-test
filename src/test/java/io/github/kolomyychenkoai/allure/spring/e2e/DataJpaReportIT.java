package io.github.kolomyychenkoai.allure.spring.e2e;

import io.github.kolomyychenkoai.allure.spring.support.JpaTestApp;
import io.github.kolomyychenkoai.allure.spring.support.jpa.Widget;
import io.github.kolomyychenkoai.allure.spring.support.jpa.WidgetRepository;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Уровень B: «живой» прогон на H2. Никакой настройки Allure/AOP в тесте — аспект
 * подключается САМ (auto-configuration) и пишет шаги «DB …» в реальный отчёт.
 * Смотреть: {@code mvn allure:serve}.
 */
@SpringBootTest(classes = JpaTestApp.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Epic("allure-spring-test")
@Feature("База данных (JPA)")
class DataJpaReportIT {

    @Autowired
    private WidgetRepository widgets;

    @Test
    @DisplayName("вызовы репозитория (save/findById/findAll/промах) автоматически попадают в отчёт")
    void repositoryCallsAppearInReport() {
        Widget saved = widgets.save(new Widget("gadget"));
        widgets.findById(saved.getId());
        widgets.findAll();
        // чтение-промах — в отчёте виден результат Optional.empty
        widgets.findById(999_999L);
    }
}
