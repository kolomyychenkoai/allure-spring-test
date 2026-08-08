package io.github.kolomyychenkoai.allure.spring.support.jpa;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Сервис с ОТКРЫТОЙ транзакцией вокруг вызова репозитория — витрина для самого опасного
 * случая с ленивой связью.
 * <p>
 * Разница принципиальная. Когда репозиторий зовут напрямую из теста, к моменту рендера
 * отчёта сессия УЖЕ закрыта: обращение к прокси бросает, библиотека это ловит и печатает
 * {@code <?>} — деградация отчёта, но приложение цело. А когда вызов идёт из
 * {@code @Transactional}-метода, сессия ОТКРЫТА, и то же обращение не падает, а молча
 * уходит в БД: лишний SELECT на каждую связь, N+1 на коллекции.
 * <p>
 * Именно этот случай нашла A/B-проверка на чужом сервисе. Без этого класса он закреплён
 * только в сервисах-потребителях, а они лежат вне git — то есть в репозитории не закреплён.
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
