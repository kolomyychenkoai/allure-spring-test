package io.github.kolomyychenkoai.allure.spring.support.jpa;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Открытая транзакция вокруг вызова репозитория: случай, где пробуждение ленивой связи бьёт
 * по ПРИЛОЖЕНИЮ (лишний SELECT), а не по отчёту — разбор обоих случаев в javadoc
 * {@code internal/JpaLaziness}.
 * <p>
 * В репозитории этот случай закрепляет только этот класс: сервисы-потребители, на которых
 * он всплыл, лежат вне git.
 */
@Service
public class WidgetService {

    private final WidgetRepository widgets;

    public WidgetService(WidgetRepository widgets) {
        this.widgets = widgets;
    }

    /** Возвращает {@link Widget} с НЕинициализированным {@code owner}, пока транзакция открыта. */
    @Transactional(readOnly = true)
    public Widget loadWithinTransaction(Long id) {
        return widgets.findById(id).orElseThrow();
    }
}
