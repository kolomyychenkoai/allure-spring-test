package io.github.kolomyychenkoai.allure.spring.support.jpa;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ManyToOne;

@Entity
public class Widget extends BaseEntity {

    private String name;

    /**
     * ЛЕНИВАЯ связь — витрина для стража {@code HibernateLaziness}: аспект обязан напечатать
     * маркер, а не разбудить прокси лишним SELECT'ом. Без этого поля страж был бы форвардным
     * (см. {@link Owner} — там же описан гейт, который ловит регрессию).
     */
    @ManyToOne(fetch = FetchType.LAZY)
    private Owner owner;

    protected Widget() {
    }

    public Widget(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Owner getOwner() {
        return owner;
    }

    public void setOwner(Owner owner) {
        this.owner = owner;
    }

    @Override
    public String toString() {
        // порядок полей как в аспекте (describeEntity: поля класса, затем суперкласса)
        return "Widget{name=" + name + ", id=" + getId() + "}";
    }
}
