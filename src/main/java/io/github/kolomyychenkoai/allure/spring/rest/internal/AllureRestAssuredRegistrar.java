package io.github.kolomyychenkoai.allure.spring.rest.internal;

import io.restassured.RestAssured;
import io.restassured.filter.Filter;

import java.util.List;

/**
 * Единственное место, где мы трогаем типы RestAssured из хука жизненного цикла.
 * <p>
 * Вынесено из {@code AllureRestAssuredListener} НЕ ради красоты. Гранулярность линковки в JVM —
 * класс, а не метод: верификатор байткода проверяет, что {@link AllureRestAssuredFilter}
 * присваивается к {@link Filter}, и ради этого грузит {@code Filter} ещё до того, как гейт
 * «RestAssured на classpath?» успеет выполниться. Пока этот код жил в листенере, весь листенер
 * без RestAssured не линковался — гейт внутри был мёртвым.
 * <p>
 * Полагаться на то, что Spring молча пропустит незагрузившийся листенер, не хочется: это
 * недокументированная деталь {@code SpringFactoriesLoader}, и она молчаливо превращает «модуль
 * выключен» в поведение, которое мы не выбирали. Проще держать листенер линкуемым всегда.
 * То же правило уже записано в {@code ByteBuddyPresence} и {@code AllureAwaitilityRegistrar}.
 * Закреплено тестом {@code unit/ListenerDegradationTest}.
 */
public final class AllureRestAssuredRegistrar {

    private static final Object LOCK = new Object();

    private AllureRestAssuredRegistrar() {
    }

    /** Ставит наш фильтр в глобальные фильтры RestAssured, если его там ещё нет. */
    public static void registerFilterOnce() {
        synchronized (LOCK) {
            List<Filter> current = RestAssured.filters();
            boolean present = current.stream().anyMatch(AllureRestAssuredFilter.class::isInstance);
            if (!present) {
                RestAssured.filters(new AllureRestAssuredFilter());
            }
        }
    }
}
