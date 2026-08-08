package io.github.kolomyychenkoai.allure.spring.support;

import io.github.kolomyychenkoai.allure.spring.internal.JpaLaziness;

import java.lang.reflect.Proxy;
import java.util.List;

/**
 * Фикстуры «незагруженная ленивая связь» для тестов ЛЮБОГО модуля, который рендерит значения.
 * <p>
 * Один и тот же прокси нужен минимум трём тестам (аспект БД, Mockito, общий рендер), и раньше
 * он был скопирован в каждый: тридцать строк с ручным {@code InvocationHandler}, из которых
 * важны две — какой предикат состояния отвечает и какое обращение считается «разбудили».
 * Копии живут ровно до первого расхождения, а расходятся они молча.
 * <p>
 * Берём НАСТОЯЩИЕ интерфейсы Hibernate (в тест-scope они есть) и НАСТОЯЩИЕ имена методов —
 * из {@link JpaLaziness}. Двойник с другим пакетом проверял бы фикцию: страж резолвит
 * провайдера рефлексивно по имени.
 */
public final class LazyProxies {

    private LazyProxies() {
    }

    /**
     * НЕинициализированный Hibernate-прокси сущности. Любое обращение к самому значению
     * ({@code toString()}) поднимает {@code touched[0]} — у потребителя это SELECT в БД.
     */
    public static Object uninitializedEntity(boolean[] touched) {
        Object initializer = handler(JpaLaziness.HIBERNATE_INITIALIZER_NAME,
                (name, args) -> JpaLaziness.HIBERNATE_PROXY_PROBE.equals(name) ? Boolean.TRUE : null);
        return handler(JpaLaziness.HIBERNATE_PROXY_NAME, (name, args) -> {
            if ("toString".equals(name)) {
                touched[0] = true;
                return "разбудили!";
            }
            if (JpaLaziness.PROXY_INITIALIZER_METHOD.equals(name)) {
                return initializer;
            }
            return "hashCode".equals(name) ? 1 : null;
        });
    }

    /**
     * НЕинициализированная ленивая КОЛЛЕКЦИЯ Hibernate (она же {@link List}, потому что
     * ветка {@code Collection} в аспекте зовёт {@code size()} и обход). Любое обращение
     * к содержимому поднимает {@code walked[0]} — у потребителя это N+1.
     */
    public static Object uninitializedCollection(boolean[] walked) {
        return Proxy.newProxyInstance(LazyProxies.class.getClassLoader(),
                new Class<?>[]{type(JpaLaziness.HIBERNATE_COLLECTION_NAME), List.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "wasInitialized" -> Boolean.FALSE;
                    case "size" -> {
                        walked[0] = true;
                        yield 0;
                    }
                    case "iterator", "stream" -> {
                        walked[0] = true;
                        yield List.of().iterator();
                    }
                    case "toString" -> {
                        walked[0] = true;
                        yield "разбудили!";
                    }
                    case "hashCode" -> 1;
                    case "equals" -> false;
                    default -> null;
                });
    }

    /** Прокси одного интерфейса: обработчик получает имя метода и аргументы. */
    private static Object handler(String interfaceName, Answer answer) {
        return Proxy.newProxyInstance(LazyProxies.class.getClassLoader(),
                new Class<?>[]{type(interfaceName)},
                (proxy, method, args) -> answer.answer(method.getName(), args));
    }

    /** Интерфейс провайдера ПО ИМЕНИ — тому же, по которому его ищет страж. */
    private static Class<?> type(String name) {
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException absent) {
            throw new IllegalStateException("нет интерфейса " + name
                    + " — фикстура проверяла бы двойник вместо настоящего типа", absent);
        }
    }

    @FunctionalInterface
    private interface Answer {
        Object answer(String method, Object[] args);
    }
}
