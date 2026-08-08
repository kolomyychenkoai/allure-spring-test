package io.github.kolomyychenkoai.allure.spring.support.jpa;

import org.springframework.data.jpa.repository.JpaRepository;

/** Нужен витрине ленивой связи: владельца надо сохранить, чтобы у {@link Widget} появился прокси. */
public interface OwnerRepository extends JpaRepository<Owner, Long> {
}
