package io.github.kolomyychenkoai.allure.spring.unit;

import io.qameta.allure.Epic;
import org.eclipse.persistence.indirection.IndirectContainer;
import org.eclipse.persistence.indirection.ValueHolderInterface;
import org.hibernate.collection.spi.PersistentCollection;
import org.hibernate.proxy.HibernateProxy;
import org.hibernate.proxy.LazyInitializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Уровень A: страж ленивости JPA — Hibernate и EclipseLink.
 * <p>
 * Берём НАСТОЯЩИЕ интерфейсы обоих провайдеров (в тест-scope они есть), а не двойники:
 * страж распознаёт их РЕФЛЕКСИВНО по имени, и двойник с другим пакетом проверял бы фикцию.
 * Реализация — динамические прокси: так видно, что страж СПРАШИВАЕТ состояние, а не трогает
 * значение.
 * <p>
 * Класс лежит в {@code internal} и виден, но зовём его рефлексией: тест не должен зависеть
 * от того, останется ли метод публичным.
 */
@Epic("Внутренние проверки библиотеки")
class JpaLazinessTest {

    private static boolean notLoaded(Object value) throws Exception {
        Class<?> type = Class.forName(
                "io.github.kolomyychenkoai.allure.spring.internal.JpaLaziness");
        Method method = type.getDeclaredMethod("notLoaded", Object.class);
        method.setAccessible(true);
        return (boolean) method.invoke(null, value);
    }

    private static Object proxy(Class<?> iface, InvocationHandler handler) {
        return Proxy.newProxyInstance(JpaLazinessTest.class.getClassLoader(),
                new Class<?>[]{iface}, handler);
    }

    private static Object lazyInitializer(boolean uninitialized) {
        return proxy(LazyInitializer.class, (p, m, a) ->
                "isUninitialized".equals(m.getName()) ? uninitialized : fallback(m));
    }

    @Test
    @DisplayName("НЕинициализированный прокси распознаётся — и его toString() при этом не зовут")
    void uninitializedProxyIsDetectedWithoutTouchingIt() throws Exception {
        boolean[] touched = {false};
        Object initializer = lazyInitializer(true);
        Object lazy = proxy(HibernateProxy.class, (p, m, a) -> {
            if ("toString".equals(m.getName())) {
                touched[0] = true; // тронул прокси — в реальности это SELECT в БД
                return "разбудили!";
            }
            return "getHibernateLazyInitializer".equals(m.getName()) ? initializer : fallback(m);
        });

        assertThat(notLoaded(lazy)).isTrue();
        assertThat(touched[0])
                .as("страж обязан СПРАШИВАТЬ состояние, а не обращаться к прокси")
                .isFalse();
    }

    @Test
    @DisplayName("УЖЕ инициализированный прокси — не ленивый, рендерим как обычно")
    void initializedProxyIsRenderedNormally() throws Exception {
        Object initializer = lazyInitializer(false);
        Object loaded = proxy(HibernateProxy.class, (p, m, a) ->
                "getHibernateLazyInitializer".equals(m.getName()) ? initializer : fallback(m));

        assertThat(notLoaded(loaded)).isFalse();
    }

    @Test
    @DisplayName("незагруженная ленивая КОЛЛЕКЦИЯ распознаётся (иначе обход дал бы N+1)")
    void uninitializedCollectionIsDetected() throws Exception {
        Object collection = proxy(PersistentCollection.class, (p, m, a) ->
                "wasInitialized".equals(m.getName()) ? Boolean.FALSE : fallback(m));

        assertThat(notLoaded(collection)).isTrue();
    }

    @Test
    @DisplayName("загруженная коллекция — не ленивая")
    void initializedCollectionIsNotLazy() throws Exception {
        Object collection = proxy(PersistentCollection.class, (p, m, a) ->
                "wasInitialized".equals(m.getName()) ? Boolean.TRUE : fallback(m));

        assertThat(notLoaded(collection)).isFalse();
    }

    @Test
    @DisplayName("обычные значения и null — не ленивые (страж не вмешивается)")
    void ordinaryValuesAreUntouched() throws Exception {
        assertThat(notLoaded(null)).isFalse();
        assertThat(notLoaded("строка")).isFalse();
        assertThat(notLoaded(List.of(1, 2, 3))).isFalse();
        assertThat(notLoaded(new Object())).isFalse();
    }

    @Test
    @DisplayName("сбой при опросе состояния → ведём себя как без стража, а не роняем чужой тест")
    void brokenProbeDegradesToFalse() throws Exception {
        Object broken = proxy(HibernateProxy.class, (p, m, a) -> {
            if ("getHibernateLazyInitializer".equals(m.getName())) {
                throw new IllegalStateException("внутренности Hibernate уехали");
            }
            return fallback(m);
        });

        assertThat(notLoaded(broken)).isFalse();
    }


    @Test
    @DisplayName("EclipseLink: незагруженный ValueHolder распознаётся, getValue() не зовут")
    void uninitializedValueHolderIsDetected() throws Exception {
        boolean[] loaded = {false};
        Object holder = proxy(ValueHolderInterface.class, (p, m, a) -> {
            if ("isInstantiated".equals(m.getName())) {
                return Boolean.FALSE;
            }
            if ("getValue".equals(m.getName()) || "toString".equals(m.getName())) {
                loaded[0] = true; // у ленивых наследников getValue() — это поход в БД
                return "загрузили!";
            }
            return fallback(m);
        });

        assertThat(notLoaded(holder)).isTrue();
        assertThat(loaded[0]).as("страж дёрнул значение вместо предиката состояния").isFalse();
    }

    @Test
    @DisplayName("EclipseLink: загруженный ValueHolder — не ленивый")
    void instantiatedValueHolderIsNotLazy() throws Exception {
        Object holder = proxy(ValueHolderInterface.class, (p, m, a) ->
                "isInstantiated".equals(m.getName()) ? Boolean.TRUE : fallback(m));

        assertThat(notLoaded(holder)).isFalse();
    }

    @Test
    @DisplayName("EclipseLink: у незагруженного IndirectContainer НЕ зовут size() — это и есть загрузка")
    void uninitializedIndirectContainerIsDetectedWithoutCallingSize() throws Exception {
        boolean[] loaded = {false};
        // ⚠️ Стережём size(), а не toString(): у EclipseLink опасен именно он (почему —
        // javadoc JpaLaziness), и ветка Collection в аспекте зовёт как раз size() и обход.
        Object container = proxy(IndirectContainer.class, (p, m, a) -> {
            if ("isInstantiated".equals(m.getName())) {
                return Boolean.FALSE;
            }
            if ("size".equals(m.getName()) || "iterator".equals(m.getName())
                    || "getValueHolder".equals(m.getName())) {
                loaded[0] = true;
                return null;
            }
            return fallback(m);
        });

        assertThat(notLoaded(container)).isTrue();
        assertThat(loaded[0]).as("страж тронул коллекцию — у потребителя это загрузка из БД").isFalse();
    }

    @Test
    @DisplayName("EclipseLink: загруженный IndirectContainer — не ленивый")
    void instantiatedIndirectContainerIsNotLazy() throws Exception {
        Object container = proxy(IndirectContainer.class, (p, m, a) ->
                "isInstantiated".equals(m.getName()) ? Boolean.TRUE : fallback(m));

        assertThat(notLoaded(container)).isFalse();
    }

    /** {@code toString}/{@code hashCode}/{@code equals} у прокси — иначе падаем на диагностике. */
    private static Object fallback(Method method) {
        return switch (method.getName()) {
            case "toString" -> "<двойник>";
            case "hashCode" -> 1;
            case "equals" -> false;
            default -> null;
        };
    }
}
