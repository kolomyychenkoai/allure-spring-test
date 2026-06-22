package io.github.kolomyychenkoai.allure.spring.support.jpa;

import jakarta.persistence.Entity;

@Entity
public class Widget extends BaseEntity {

    private String name;

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

    @Override
    public String toString() {
        // порядок полей как в аспекте (describeEntity: поля класса, затем суперкласса)
        return "Widget{name=" + name + ", id=" + getId() + "}";
    }
}
