package io.github.kolomyychenkoai.allure.spring.support.jpa;

import jakarta.persistence.Entity;

/**
 * Владелец {@link Widget} — сторона ЛЕНИВОЙ связи в витрине.
 * <p>
 * ⚠️ {@code toString()} здесь НЕ переопределён НАМЕРЕННО. Если страж ленивости
 * ({@code HibernateLaziness}) когда-нибудь сломается и прокси снова начнут будить, во
 * вложении «DB Result» появится {@code Owner@1a2b3c} — и на это КРАСНЕЕТ уже существующая
 * гигиена тел ({@code inventory/StepNameHygiene}, правило «identity-хэш вместо значения»).
 * Переопределишь {@code toString()} — этот бесплатный гейт замолчит.
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
