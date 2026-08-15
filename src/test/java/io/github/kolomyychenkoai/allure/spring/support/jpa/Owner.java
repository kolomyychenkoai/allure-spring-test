package io.github.kolomyychenkoai.allure.spring.support.jpa;

import jakarta.persistence.Entity;

/**
 * Владелец {@link Widget} — сторона ЛЕНИВОЙ связи в витрине.
 * <p>
 * ⚠️ Гигиена тел регрессию защиты НЕ поймает: без неё в теле оказывается {@code <?>},
 * а не identity-хэш — ловить ей нечего (проверено мутацией). Держат её ДВА адресных теста
 * в {@code demo/DataJpaReportIT}, по одному на каждый случай из javadoc
 * {@code internal/JpaLaziness}: {@code lazyAssociationIsNotWokenUp} и
 * {@code lazyAssociationCostsNoExtraQueryInsideTransaction}. Оба проверены мутацией.
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
