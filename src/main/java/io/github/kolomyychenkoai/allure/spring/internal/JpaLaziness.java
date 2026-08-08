package io.github.kolomyychenkoai.allure.spring.internal;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Определяет, что значение — ЕЩЁ НЕ ЗАГРУЖЕННАЯ ленивая связь JPA, не трогая её.
 * <p>
 * <b>Зачем.</b> Отчёт не имеет права менять поведение приложения. Обращение к
 * неинициализированному прокси при ОТКРЫТОЙ сессии не бросает исключение — оно молча идёт
 * в БД: лишний SELECT на каждую ленивую связь, а на ленивой коллекции ещё и N+1. Отсоединённая
 * сущность уезжает к вызывающему уже инициализированной, и его тест про ленивость краснеет.
 * Тот же класс дефекта, что {@code toString()} чужого объекта в имени шага: это побочный
 * эффект, а не чтение.
 * <p>
 * <b>Почему здесь, а не в модуле БД.</b> Ленивое значение доезжает до рендера не только из
 * аспекта репозиториев: мок, возвращающий сущность, отдаёт его в {@code AllureMockitoHandler} —
 * тот же путь к тому же {@code toString()}. Страж стоит в ОБЩЕЙ точке рендера
 * ({@link AllureAdviceSupport}), поэтому закрыты все модули сразу, а не тот, где заметили.
 * <p>
 * <b>Почему рефлексия.</b> Ни Hibernate, ни EclipseLink не в compile-classpath библиотеки
 * (тот же приём, что для {@code jakarta.persistence.Entity} в аспекте репозиториев):
 * у потребителя Spring Data может быть вовсе без JPA.
 * <p>
 * <b>Распознаём по ИНТЕРФЕЙСАМ, а не эвристикой</b> вроде «найти метод с похожим именем»:
 * эвристика однажды совпадёт с чужим классом и подменит настоящее значение маркером.
 * Цена — каждый новый провайдер надо добавлять руками; зато ложных срабатываний нет.
 * <p>
 * <b>Почему это безопасно спрашивать.</b> Ни {@code getHibernateLazyInitializer()}, ни
 * {@code isUninitialized()}/{@code wasInitialized()}/{@code isInstantiated()} значение НЕ
 * загружают — это штатные предикаты состояния.
 * <p>
 * <b>У EclipseLink риск в ДРУГОМ месте, чем у Hibernate</b> (проверено по байткоду, а не
 * предположено): {@code IndirectList.toString()} безопасен — он сам сперва спрашивает
 * {@code isInstantiated()}. Зато {@code size()} идёт через {@code getDelegate()} и ГРУЗИТ
 * коллекцию, а именно {@code size()} и обход зовёт ветка {@code Collection} в
 * {@code AllureRepositoryAspect}. Плюс {@code describeEntity} перебирает ВСЕ поля сущности,
 * включая служебные {@code _persistence_*_vh} с {@code ValueHolder} внутри.
 * <p>
 * При любом сбое определения отвечаем {@code false}: «не знаю» ведёт себя как отсутствие
 * стража, а не роняет чужой тест.
 * <p>
 * <b>Стоимость ЗАМЕРЕНА</b> на полном сьюте: 2216 вызовов, 1,9 мс суммарно, 0,86 мкс на вызов.
 * Дёшево потому, что распознавание класса кэшируется в {@link #PROBES}, и на повторных
 * значениях остаётся один поиск в {@code ConcurrentHashMap}. Для сравнения: рефлексивный
 * поиск сервера WireMock стоит проекту 7,8 мс на тот же сьют и признан приемлемым.
 * <p>
 * ⚠️ <b>Покрыты Hibernate и EclipseLink.</b> На OpenJPA и прочих провайдерах ленивое
 * значение защиты НЕ получит и будет загружено рендером. Имена всех четырёх интерфейсов
 * стережёт канарейка {@code canary/InstrumentationApiCanaryTest} — переименуют, узнаем
 * точечно, а не по возврату дефекта.
 */
public final class JpaLaziness {

    /** Маркер вместо значения: обращаться к нему нельзя, а сказать о нём в отчёте нужно. */
    public static final String NOT_LOADED = "<не загружено: ленивая связь>";

    private static final String HIBERNATE_PROXY = "org.hibernate.proxy.HibernateProxy";
    private static final String HIBERNATE_COLLECTION = "org.hibernate.collection.spi.PersistentCollection";
    private static final String ECLIPSELINK_HOLDER = "org.eclipse.persistence.indirection.ValueHolderInterface";
    private static final String ECLIPSELINK_CONTAINER = "org.eclipse.persistence.indirection.IndirectContainer";

    /** Кэш «класс значения → как у него спросить»: рефлексия на каждое поле сущности дорога. */
    private static final Map<Class<?>, Probe> PROBES = new ConcurrentHashMap<>();

    /** Как узнать состояние у конкретного класса значения. */
    private enum Probe {
        /** Hibernate-прокси: {@code getHibernateLazyInitializer().isUninitialized()}. */
        HIBERNATE_PROXY,
        /** Hibernate-коллекция: {@code !wasInitialized()}. */
        HIBERNATE_COLLECTION,
        /** EclipseLink (и держатель значения, и контейнер): {@code !isInstantiated()}. */
        ECLIPSELINK,
        /** Ничего из перечисленного — спрашивать нечего. */
        NONE
    }

    private JpaLaziness() {
    }

    /** Ленивая связь, которую ещё не загружали? {@code false} — если это не она либо неизвестно. */
    public static boolean notLoaded(Object value) {
        if (value == null) {
            return false;
        }
        try {
            return switch (PROBES.computeIfAbsent(value.getClass(), JpaLaziness::probeFor)) {
                case HIBERNATE_PROXY -> uninitializedProxy(value);
                case HIBERNATE_COLLECTION -> !invokeBoolean(value, "wasInitialized");
                case ECLIPSELINK -> !invokeBoolean(value, "isInstantiated");
                case NONE -> false;
            };
        } catch (Throwable unknown) {
            return false;
        }
    }

    private static Probe probeFor(Class<?> type) {
        if (implementsInterface(type, HIBERNATE_PROXY)) {
            return Probe.HIBERNATE_PROXY;
        }
        if (implementsInterface(type, HIBERNATE_COLLECTION)) {
            return Probe.HIBERNATE_COLLECTION;
        }
        // У EclipseLink оба типа отвечают одним и тем же isInstantiated(), поэтому ветка общая.
        if (implementsInterface(type, ECLIPSELINK_HOLDER) || implementsInterface(type, ECLIPSELINK_CONTAINER)) {
            return Probe.ECLIPSELINK;
        }
        return Probe.NONE;
    }

    /**
     * Ищем интерфейс ПО ИМЕНИ и по всей иерархии: класс прокси синтетический
     * ({@code Customer$HibernateProxy$xyz}), типы провайдеров в compile-classpath отсутствуют,
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
