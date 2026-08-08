package io.github.kolomyychenkoai.allure.spring.support.jpa;

import jakarta.persistence.Entity;

/**
 * Владелец {@link Widget} — сторона ЛЕНИВОЙ связи в витрине.
 * <p>
 * ⚠️ Гигиена тел на регрессию стража НЕ сработает — проверено мутацией. Без стража
 * значение печатается как {@code <?>}: {@code toString()} прокси бросает при закрытой
 * сессии, и библиотека это ловит. Ни identity-хэша, ни синтетического имени в теле нет,
 * ловить гигиене нечего.
 * <p>
 * Регрессию держат ДВА адресных теста в {@code demo/DataJpaReportIT}, и они про разное:
 * {@code lazyAssociationIsNotWokenUp} — сессия закрыта, деградирует отчёт (маркер вместо
 * {@code <?>}); {@code lazyAssociationCostsNoExtraQueryInsideTransaction} — сессия ОТКРЫТА,
 * и тогда страдает уже приложение: лишний SELECT. Оба проверены мутацией.
 */
@Entity
public class Owner extends BaseEntity {

    private String title;

    protected Owner() {
    }

    public Owner(String title) {
        this.title = title;
    }

    public String getTitle() {
        return title;
    }
}
