package io.github.kolomyychenkoai.allure.spring.e2e;

import io.github.kolomyychenkoai.allure.spring.support.JpaTestApp;
import io.github.kolomyychenkoai.allure.spring.support.jpa.Widget;
import io.github.kolomyychenkoai.allure.spring.support.jpa.WidgetRepository;
import io.qameta.allure.Allure;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Уровень B: «живой» прогон на H2. Шаги «DB …» аспект пишет САМ (auto-configuration);
 * шаги «When/Then» здесь явные — чтобы в отчёте была видна и сверка результата
 * (пока нет модуля инструментирования ассертов — он сделает это автоматически).
 * Смотреть: {@code mvn allure:serve}.
 */
@SpringBootTest(classes = JpaTestApp.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Epic("allure-spring-test")
@Feature("База данных (JPA)")
class DataJpaReportIT {

    @Autowired
    private WidgetRepository widgets;

    @Test
    @DisplayName("Сохранение и чтение Widget: данные корректно проходят через БД")
    void savesAndReadsWidget() {
        Widget saved = Allure.step("When: сохраняем Widget(name=gadget)",
                () -> widgets.save(new Widget("gadget")));
        Allure.step("Then: БД присвоила id, имя сохранилось", () -> {
            assertThat(saved.getId()).isNotNull();
            assertThat(saved.getName()).isEqualTo("gadget");
        });

        Optional<Widget> found = Allure.step("When: findById(" + saved.getId() + ")",
                () -> widgets.findById(saved.getId()));
        Allure.step("Then: вернулась та же сущность (id и name совпали)", () -> {
            assertThat(found).isPresent();
            assertThat(found.get().getId()).isEqualTo(saved.getId());
            assertThat(found.get().getName()).isEqualTo("gadget");
        });

        List<Widget> all = Allure.step("When: findAll()", () -> widgets.findAll());
        Allure.step("Then: в таблице ровно одна запись — gadget", () ->
                assertThat(all).extracting(Widget::getName).containsExactly("gadget"));

        Optional<Widget> missing = Allure.step("When: findById(999999) — несуществующий id",
                () -> widgets.findById(999_999L));
        Allure.step("Then: результат пуст (Optional.empty)", () ->
                assertThat(missing).isEmpty());
    }
}
