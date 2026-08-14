package io.github.kolomyychenkoai.allure.spring.internal;

import java.lang.reflect.Method;

/**
 * Определяет, что значение — ЕЩЁ НЕ ЗАГРУЖЕННАЯ ленивая связь JPA, не трогая её.
 * <p>
 * <b>Зачем.</b> Отчёт не имеет права менять поведение приложения, а обращение к
 * неинициализированному прокси — побочный эффект, а не чтение (тот же класс дефекта, что
 * {@code toString()} чужого объекта в имени шага). Последствие зависит от сессии, и это
 * ЕДИНСТВЕННОЕ место, где разобраны оба случая — остальные ссылаются сюда:
 * <ul>
 *   <li>сессия ОТКРЫТА (вызов из {@code @Transactional}) — обращение не бросает, а молча идёт
 *       в БД: лишний SELECT на связь, N+1 на коллекции, наружу уезжает уже инициализированная
 *       сущность. Страдает ПРИЛОЖЕНИЕ, тест потребителя про ленивость краснеет;</li>
 *   <li>сессия ЗАКРЫТА (репозиторий позвали прямо из теста) — обращение бросает, библиотека
 *       это ловит, и во вложении остаётся {@code <?>}. Страдает ОТЧЁТ, и молча.</li>
 * </ul>
 * <p>
 * <b>Почему здесь, а не в модуле БД.</b> Ленивое значение доезжает до рендера не только из
 * аспекта репозиториев: мок, возвращающий сущность, отдаёт его в {@code AllureMockitoHandler} —
 * тот же путь к тому же {@code toString()}. Защита стоит в ОБЩЕЙ точке рендера
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
 * теста, а не роняет чужой тест.
 * <p>
 * <b>Стоимость ЗАМЕРЕНА</b> inline-таймером на полном сьюте: ~2350 вызовов, 2,4 мс суммарно,
 * 1,0 мкс на вызов (в цифру входит и сам таймер) — распознавание класса кэшируется, чем
 * именно и какой ценой, см. {@link #PROBES}. Для сравнения: рефлексивный поиск сервера
 * WireMock стоит проекту 7,8 мс на тот же сьют и признан приемлемым.
 * <p>
 * ⚠️ <b>Покрыты Hibernate и EclipseLink.</b> На OpenJPA и прочих провайдерах ленивое
 * значение защиты НЕ получит и будет загружено рендером; если чтение при этом бросит, вызов
 * приложения уцелеет — рендер ответа под защитой ({@code AllureRepositoryAspect.describeResponse}),
 * потребитель увидит обеднённый отчёт, а не упавший тест. Имена всех четырёх интерфейсов
 * стережёт канарейка {@code canary/InstrumentationApiCanaryTest} — переименуют, узнаем
 * точечно, а не по возврату дефекта.
 * <p>
 * ⚠️ <b>Защищено ЗНАЧЕНИЕ, а не всё, что внутри него.</b> Рендер спрашивает защиту про само
 * значение и про элементы массива, но коллекция и мапа уходят одним {@code String.valueOf},
 * который сам зовёт {@code toString()} каждого элемента. Ленивый прокси, лежащий ВНУТРИ
 * обычного {@code List} (не {@code PersistentCollection}), будет разбужен. Обходить чужие
 * коллекции ради проверки нельзя — это ровно тот побочный эффект, от которого защита и стоит.
 * Аспекта репозиториев это не касается: он печатает элементы поштучно, каждый через рендер.
 */
public final class JpaLaziness {

    /** Маркер вместо значения: обращаться к нему нельзя, а сказать о нём в отчёте нужно. */
    public static final String NOT_LOADED = "<не загружено: ленивая связь>";

    // Публичные, потому что их читает канарейка (canary/InstrumentationApiCanaryTest): копия
    // строкой у неё стерегла бы чужой API, а не НАШУ связь с ним. Тот же приём, что MovedTypeNames.
    public static final String HIBERNATE_PROXY_NAME = "org.hibernate.proxy.HibernateProxy";
    public static final String HIBERNATE_INITIALIZER_NAME = "org.hibernate.proxy.LazyInitializer";
    public static final String HIBERNATE_COLLECTION_NAME = "org.hibernate.collection.spi.PersistentCollection";
    public static final String ECLIPSELINK_HOLDER_NAME = "org.eclipse.persistence.indirection.ValueHolderInterface";
    public static final String ECLIPSELINK_CONTAINER_NAME = "org.eclipse.persistence.indirection.IndirectContainer";

    /** Предикаты состояния, которые зовём рефлексией: их имена канарейка стережёт так же. */
    public static final String PROXY_INITIALIZER_METHOD = "getHibernateLazyInitializer";
    public static final String HIBERNATE_PROXY_PROBE = "isUninitialized";
    public static final String HIBERNATE_COLLECTION_PROBE = "wasInitialized";
    public static final String ECLIPSELINK_PROBE = "isInstantiated";

    /**
     * Кэш «класс значения → как у него спросить»: рефлексия на каждое поле сущности дорога.
     * <p>
     * {@link ClassValue}, а НЕ {@code Map<Class<?>, …>}: защиту зовут на каждое отрендеренное
     * значение во всех модулях, то есть ключом сюда попадёт всякий встреченный класс, включая
     * генерируемые (моки, прокси). Статическая мапа держала бы их сильной ссылкой, а класс
     * держит свой загрузчик — и он не ушёл бы в GC до конца JVM. У проекта это не абстракция:
     * {@code HiddenClassLoader} заводит копии всего classpath, у потребителя — DevTools и
     * изолированные загрузчики. {@code ClassValue} привязан к самому классу и уходит вместе
     * с ним (та же причина, по которой рядом {@code WeakHashMap} в листенерах WireMock/MockMvc).
     * <p>
     * Цена: замером выше {@code ConcurrentHashMap} давал 0,87 мкс на вызов против 1,0 —
     * 0,3 мс на весь сьют за то, что кэш никого не удерживает.
     */
    private static final ClassValue<Probe> PROBES = new ClassValue<>() {
        @Override
        protected Probe computeValue(Class<?> type) {
            return probeFor(type);
        }
    };

    /** Как узнать состояние у конкретного класса значения; чем именно — видно в {@link #notLoaded}. */
    private enum Probe {
        HIBERNATE_PROXY,
        HIBERNATE_COLLECTION,
        ECLIPSELINK,
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
            return switch (PROBES.get(value.getClass())) {
                case HIBERNATE_PROXY -> uninitializedProxy(value);
                case HIBERNATE_COLLECTION -> !invokeBoolean(value, HIBERNATE_COLLECTION_PROBE);
                case ECLIPSELINK -> !invokeBoolean(value, ECLIPSELINK_PROBE);
                case NONE -> false;
            };
        } catch (Throwable unknown) {
            return false;
        }
    }

    private static Probe probeFor(Class<?> type) {
        if (implementsInterface(type, HIBERNATE_PROXY_NAME)) {
            return Probe.HIBERNATE_PROXY;
        }
        if (implementsInterface(type, HIBERNATE_COLLECTION_NAME)) {
            return Probe.HIBERNATE_COLLECTION;
        }
        // У EclipseLink оба типа отвечают одним и тем же isInstantiated(), поэтому ветка общая.
        if (implementsInterface(type, ECLIPSELINK_HOLDER_NAME) || implementsInterface(type, ECLIPSELINK_CONTAINER_NAME)) {
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
        Method getInitializer = proxy.getClass().getMethod(PROXY_INITIALIZER_METHOD);
        getInitializer.setAccessible(true);
        Object initializer = getInitializer.invoke(proxy);
        return initializer != null && invokeBoolean(initializer, HIBERNATE_PROXY_PROBE);
    }

    private static boolean invokeBoolean(Object target, String method) throws Exception {
        Method m = target.getClass().getMethod(method);
        m.setAccessible(true);
        return Boolean.TRUE.equals(m.invoke(target));
    }
}
