package io.github.kolomyychenkoai.allure.spring.support.jpa;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ManyToOne;

@Entity
public class Widget extends BaseEntity {

    private String name;

    /**
     * ЛЕНИВАЯ связь — витрина для стража {@code JpaLaziness}: аспект обязан напечатать
     * маркер, а не разбудить прокси лишним SELECT'ом. Без этого поля страж был бы форвардным
     * (в javadoc {@link Owner} названы два теста, которые ловят регрессию).
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
        // Порядок полей как в аспекте (describeEntity: поля класса, затем суперкласса).
        // ⚠️ owner здесь НЕТ намеренно: он ленивый, и печать его в toString() будила бы
        // прокси — ровно то, от чего защищает JpaLaziness. Поэтому строка аспекта
        // («Widget{name=…, owner=…, id=…}») длиннее этой, и совпадать они не обязаны.
        return "Widget{name=" + name + ", id=" + getId() + "}";
    }
}
