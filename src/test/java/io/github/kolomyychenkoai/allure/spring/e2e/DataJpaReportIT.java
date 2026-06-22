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

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Уровень B: «живой» прогон на H2. Тест пишет обычный код с обычными ассертами —
 * НИКАКИХ Allure.step руками. Шаги «DB …» в отчёт добавляет САМ аспект
 * (auto-configuration). Шаги-ассерты появятся автоматически, когда будет готов
 * модуль инструментирования ассертов (AssertJ/Hamcrest) — тоже без кода в тесте.
 * Смотреть: {@code mvn allure:serve}.
 */
@SpringBootTest(classes = JpaTestApp.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Epic("allure-spring-test")
@Feature("База данных (JPA)")
class DataJpaReportIT {

    @Autowired
    private WidgetRepository widgets;

    @Test
    @DisplayName("сохранение и чтение Widget через репозиторий")
    void savesAndReadsWidget() {
        Widget saved = widgets.save(new Widget("gadget"));
        assertThat(saved.getId()).isNotNull();

        Optional<Widget> found = widgets.findById(saved.getId());
        assertThat(found).get().extracting(Widget::getName).isEqualTo("gadget");

        assertThat(widgets.findAll()).extracting(Widget::getName).containsExactly("gadget");

        assertThat(widgets.findById(999_999L)).isEmpty();
    }
}
