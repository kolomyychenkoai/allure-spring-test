package io.github.kolomyychenkoai.allure.spring.internal;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Определяет, что значение — ЕЩЁ НЕ ЗАГРУЖЕННАЯ ленивая связь Hibernate, не трогая её.
 * <p>
 * <b>Зачем.</b> Отчёт не имеет права менять поведение приложения. Обращение к
 * неинициализированному прокси при ОТКРЫТОЙ сессии не бросает исключение — оно молча идёт
 * в БД: лишний SELECT на каждую ленивую связь, а на ленивой коллекции ещё и N+1. Отсоединённая
 * сущность уезжает к вызывающему уже инициализированной, и его тест про ленивость краснеет.
 * Тот же класс дефекта, что {@code toString()} чужого объекта в имени шага: это побочный
 * эффект, а не чтение.
 * <p>
 * <b>Почему здесь, а не в модуле БД.</b> Ленивое значение доезжает до рендера не только из
 * аспекта репозиториев: мок, возвращающий сущность, отдаёт его в {@code AllureMockitoHandler},
 * и тот будил прокси ровно так же. Страж стоит в ОБЩЕЙ точке рендера
 * ({@link AllureAdviceSupport}), поэтому закрыты все модули сразу, а не тот, где заметили.
 * <p>
 * <b>Почему рефлексия.</b> Hibernate не в compile-classpath библиотеки (тот же приём, что для
 * {@code jakarta.persistence.Entity} в аспекте репозиториев): у потребителя Spring Data может
 * быть без Hibernate вовсе.
 * <p>
 * <b>Почему это безопасно спрашивать.</b> Ни {@code getHibernateLazyInitializer()}, ни
 * {@code isUninitialized()}, ни {@code wasInitialized()} прокси НЕ инициализируют — это
 * штатные предикаты состояния.
 * <p>
 * При любом сбое определения отвечаем {@code false}: «не знаю» должно вести себя как раньше,
 * а не ронять чужой тест.
 */
public final class HibernateLaziness {

    /** Маркер вместо значения: обращаться к нему нельзя, а сказать о нём в отчёте нужно. */
    public static final String NOT_LOADED = "<не загружено: ленивая связь>";

    private static final String PROXY = "org.hibernate.proxy.HibernateProxy";
    private static final String COLLECTION = "org.hibernate.collection.spi.PersistentCollection";

    /** Кэш «класс значения → как у него спросить»: рефлексия на каждое поле сущности дорога. */
    private static final Map<Class<?>, Probe> PROBES = new ConcurrentHashMap<>();

    /** Как узнать состояние у конкретного класса значения. */
    private enum Probe {
        /** Прокси: {@code getHibernateLazyInitializer().isUninitialized()}. */
        PROXY_INITIALIZER,
        /** Коллекция: {@code !wasInitialized()}. */
        PERSISTENT_COLLECTION,
        /** Ни то, ни другое — спрашивать нечего. */
        NONE
    }

    private HibernateLaziness() {
    }

    /** Ленивая связь, которую ещё не загружали? {@code false} — если это не она либо неизвестно. */
    public static boolean notLoaded(Object value) {
        if (value == null) {
            return false;
        }
        try {
            return switch (PROBES.computeIfAbsent(value.getClass(), HibernateLaziness::probeFor)) {
                case PROXY_INITIALIZER -> uninitializedProxy(value);
                case PERSISTENT_COLLECTION -> !invokeBoolean(value, "wasInitialized");
                case NONE -> false;
            };
        } catch (Throwable unknown) {
            return false;
        }
    }

    private static Probe probeFor(Class<?> type) {
        if (implementsInterface(type, PROXY)) {
            return Probe.PROXY_INITIALIZER;
        }
        return implementsInterface(type, COLLECTION) ? Probe.PERSISTENT_COLLECTION : Probe.NONE;
    }

    /**
     * Ищем интерфейс ПО ИМЕНИ и по всей иерархии: класс прокси синтетический
     * ({@code Customer$HibernateProxy$xyz}), Hibernate-типы в compile-classpath отсутствуют,
     * а {@code isAssignableFrom} требовал бы загруженного {@code Class}.
     */
    private static boolean implementsInterface(Class<?> type, String name) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            for (Class<?> iface : current.getInterfaces()) {
                if (iface.getName().equals(name) || implementsInterface(iface, name)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean uninitializedProxy(Object proxy) throws Exception {
        Method getInitializer = proxy.getClass().getMethod("getHibernateLazyInitializer");
        getInitializer.setAccessible(true);
        Object initializer = getInitializer.invoke(proxy);
        return initializer != null && invokeBoolean(initializer, "isUninitialized");
    }

    private static boolean invokeBoolean(Object target, String method) throws Exception {
        Method m = target.getClass().getMethod(method);
        m.setAccessible(true);
        return Boolean.TRUE.equals(m.invoke(target));
    }
}
