package io.github.kolomyychenkoai.allure.spring.support.jpa;

import org.springframework.data.jpa.repository.JpaRepository;

public interface WidgetRepository extends JpaRepository<Widget, Long> {
}
