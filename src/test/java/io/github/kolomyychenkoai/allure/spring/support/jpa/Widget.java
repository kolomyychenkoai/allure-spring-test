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
}
