package io.github.kolomyychenkoai.allure.spring.e2e;

import io.github.kolomyychenkoai.allure.spring.support.JpaTestApp;
import io.github.kolomyychenkoai.allure.spring.support.jpa.Widget;
import io.github.kolomyychenkoai.allure.spring.support.jpa.WidgetRepository;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
@TestMethodOrder(MethodOrderer.OrderAnnotation.class) // фикс порядка → id в отчёте детерминированы
class DataJpaReportIT {

    @Autowired
    private WidgetRepository widgets;

    @Test
    @Order(1)
    @DisplayName("сохранение и чтение Widget через репозиторий")
    void savesAndReadsWidget() {
        Widget saved = widgets.save(new Widget("gadget"));
        assertThat(saved.getId()).isNotNull();

        Optional<Widget> found = widgets.findById(saved.getId());
        assertThat(found).get().extracting(Widget::getName).isEqualTo("gadget");

        assertThat(widgets.findAll()).extracting(Widget::getName).containsExactly("gadget");

        assertThat(widgets.findById(999_999L)).isEmpty();
    }

    @Test
    @Order(2)
    @DisplayName("UPDATE и DELETE тоже ловятся как реальный SQL")
    void updateAndDeleteAreLogged() {
        Widget saved = widgets.save(new Widget("old"));

        saved.setName("new");
        widgets.save(saved);                 // существующая сущность → UPDATE

        widgets.deleteById(saved.getId());   // → DELETE
        assertThat(widgets.findById(saved.getId())).isEmpty();
    }

    @Test
    @Order(3)
    @DisplayName("ошибка репозитория видна шагом (BROKEN), исключение проброшено")
    void repositoryErrorIsVisibleAsBrokenStep() {
        // findById(null) бросает в Spring Data — аспект покажет BROKEN-шаг с текстом и пробросит
        assertThatThrownBy(() -> widgets.findById(null))
                .isInstanceOf(RuntimeException.class);
    }
}
