package io.github.kolomyychenkoai.allure.spring.rest.internal;

import io.restassured.RestAssured;
import io.restassured.filter.Filter;

import java.util.List;

/**
 * Единственное место, где мы трогаем типы RestAssured из хука жизненного цикла.
 * <p>
 * ⚠️ Не переноси этот код обратно в {@code AllureRestAssuredListener}. Гранулярность линковки
 * в JVM — класс, а не метод: верификатор байткода проверяет, что {@link AllureRestAssuredFilter}
 * присваивается к {@link Filter}, и ради этого грузит {@code Filter} ещё до того, как гейт
 * «RestAssured на classpath?» успеет выполниться. В листенере такой гейт мёртв — без RestAssured
 * не линкуется весь листенер целиком.
 * <p>
 * Незагрузившийся листенер Spring, скорее всего, молча пропустит, но это недокументированная
 * деталь {@code SpringFactoriesLoader}: опираться на неё значит отдать ей решение «модуль
 * выключен». Дешевле держать листенер линкуемым всегда. То же правило — в
 * {@code ByteBuddyPresence} и {@code AllureAwaitilityRegistrar}, закреплено тестом
 * {@code unit/ListenerDegradationTest}.
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
