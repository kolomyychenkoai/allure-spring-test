package io.github.kolomyychenkoai.allure.spring.data.internal;

import io.github.kolomyychenkoai.allure.spring.internal.AllureInstrumentationLogger;
import net.ttddyy.dsproxy.support.ProxyDataSource;
import net.ttddyy.dsproxy.support.ProxyDataSourceBuilder;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.springframework.aop.SpringProxy;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.aop.support.DefaultPointcutAdvisor;
import org.springframework.aop.support.NameMatchMethodPointcut;
import org.springframework.util.ClassUtils;

import javax.sql.DataSource;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Оборачивает бин {@link DataSource} так, что он остаётся объектом своего класса.
 * <p>
 * <b>Зачем.</b> Класс бина — часть публичного договора приложения: потребители инжектят пул
 * по конкретному типу ({@code HikariDataSource} у ShedLock), зовут
 * {@code getBean(HikariDataSource.class)}, вешают {@code @ConditionalOnBean} по типу пула и
 * проверяют {@code isInstanceOf} в своих тестах. Подмена бина объектом чужого класса роняет
 * контекст целиком, то есть библиотека, добавленная ради отчёта, убивает весь прогон до
 * первого шага (issue #54).
 * <p>
 * <b>Что нужно каналу SQL.</b> Ровно одно: чтобы {@link DataSource#getConnection()} вернул
 * соединение, обёрнутое datasource-proxy — из него и стреляет {@link AllureDataSourceListener}.
 * Прочие методы {@link ProxyDataSource} просто делегируют. Поэтому подменяем не объект,
 * а поведение вызовов {@code getConnection}: отдаём прокси исходного класса (Spring AOP),
 * остальное попадает в настоящий бин.
 * <p>
 * <b>Что AOP-прокси даёт сверх сохранённого класса.</b> Spring Boot достаёт настоящий пул
 * через {@code AopProxyUtils.getSingletonTarget} ({@code DataSourceUnwrapper}), поэтому метрики
 * Hikari, health-контрибьютор и {@code DataSourcePoolMetadataProvider} работают. Сквозь
 * {@link ProxyDataSource} он этого не умеет.
 * <p>
 * <b>Подкласс создаётся без конструктора</b> (Objenesis внутри Spring AOP), то есть поля
 * подкласса пусты, и любой невперехваченный метод выполнится на пустом объекте. Отсюда
 * ступень «не проксируем класс с {@code final}-методом»: перехватить такой метод нельзя, и он
 * тихо вернёт {@code null} вместо значения. К бину, который САМ является JDK-прокси (чужой
 * декоратор по интерфейсу, Spring AOP в режиме {@code proxyTargetClass=false}), это не
 * относится: подкласс там не нужен, хватает прокси по интерфейсам. ⚠️ Встроенная база сюда
 * НЕ относится, хоть это и напрашивается: {@code EmbeddedDatabaseFactory$EmbeddedDataSourceProxy} —
 * обычный класс, проверено. ⚠️ При {@code -Dspring.objenesis.ignore=true} Spring уходит в штатный фолбэк и
 * конструктор пула всё-таки вызывается — один раз, на создании прокси.
 * <p>
 * ⚠️ <b>Чужие перегрузки {@code getConnection} уходят в пул нетронутыми.</b> У Oracle UCP
 * ({@code oracle.ucp.jdbc.PoolDataSource}) это {@code getConnection(Properties)} и
 * {@code getConnection(String, String, Properties)} — метки соединения, по которым пул
 * выбирает, что отдать; сверено по документации Oracle, самого jar в проекте нет. Поэтому
 * в datasource-proxy заводим ТОЛЬКО две сигнатуры из {@link DataSource}, остальное идёт
 * в настоящий пул без логирования SQL.
 * Разбирать чужие перегрузки «как умеем» нельзя: приведение аргумента к {@code String} падает
 * прямо в коде потребителя, а выброшенный лишний аргумент меняет смысл вызова молча.
 * <p>
 * ⚠️ <b>Цепочку бинов считаем один раз, и держат это ДВА механизма.</b>
 * {@code LazyConnectionDataSourceProxy} поверх {@code AbstractRoutingDataSource} поверх пула —
 * это три бина, и обёртка на каждом писала бы один запрос в отчёт трижды.
 * <ol>
 *   <li>{@link #wrapsOurProxy} не оборачивает бин, внутри которого наш прокси уже есть. Знает
 *       акцессоры Spring и потому неполон: чужой декоратор вправе назвать свой
 *       {@code getDelegate};</li>
 *   <li>{@link #HANDING_OUT} гасит вложенную выдачу соединения по стеку. Полон по построению,
 *       но работает только когда делегация СИНХРОННАЯ.</li>
 * </ol>
 * Порознь ни один не закрывает всё, и это замер, а не рассуждение:
 * {@code LazyConnectionDataSourceProxy} спрашивает цель не в {@code getConnection}, а позже,
 * при первом запросе — счётчик к тому времени уже снят, и без первого механизма запрос
 * попадал в отчёт дважды. ⚠️ Остаётся щель: чужой декоратор с НЕизвестным акцессором И
 * ленивой делегацией — или делегацией в ДРУГОМ потоке — даст задвоение. Незнакомым считается
 * не только чужое ИМЯ акцессора: цель остаётся непрочитанной и когда акцессор бросил, и когда
 * у него чужой тип возврата (обе формы замерены, под них лежат фикстуры). Известных таких
 * пулов нет, но обещать полноту нечестно.
 * <p>
 * ⚠️ <b>Идентичность бина меняется</b> (issue #59): {@code ds != исходный}, {@code getClass()}
 * даёт класс прокси, рефлексия по полям видит пустой экземпляр, {@code equals}/{@code hashCode}
 * идут по правилам Spring AOP (сравнение с исходным объектом ложно в обе стороны — бин нельзя
 * держать ключом в {@code Map}). Настоящий объект достаётся через
 * {@code AopProxyUtils.getSingletonTarget}.
 * <p>
 * ⚠️ <b>{@code ProxyDataSource} строится поверх СЫРОГО бина, а не поверх нашего прокси</b> —
 * иначе {@code getConnection} звал бы сам себя до переполнения стека.
 * <p>
 * ⚠️ <b>Не ставить {@code factory.setOpaque(true)}</b>: непрозрачный прокси не отдаёт
 * {@code Advised}, и Spring Boot перестанет находить настоящий пул.
 * <p>
 * ⚠️ <b>Не реализовывать {@code Ordered} на постпроцессоре, который сюда ходит.</b> Без
 * интерфейса он попадает в последнюю группу и оборачивает уже готовые чужие обёртки, то есть
 * видит их SQL. {@code Ordered.LOWEST_PRECEDENCE} парадоксально сдвинул бы его РАНЬШЕ них.
 */
public final class AllureDataSourceProxies {

    /**
     * Маркер нашего прокси: отвечает на вопрос «этот бин уже обёрнут?» без разбора советов
     * внутри {@code Advised}.
     * <p>
     * Без маркера второй заход тоже вернул бы тот же объект, но окольным путём:
     * сгенерированный класс прокси объявляет свои методы {@code final}, и сработала бы
     * предпроверка из {@link #wrap} — с предупреждением в логе на каждый бин. Замерено
     * мутацией, а не выведено: по одному «тот же объект» два пути неразличимы, и стережёт
     * разницу {@code autoconfig/AllureDataAutoConfigurationTest.doesNotDoubleWrapOwnProxy}.
     */
    public interface AllureProxiedDataSource {
    }

    /**
     * Идёт ли уже выдача соединения выше по стеку. Гасит дубли от делегации: в цепочке бинов
     * ({@code LazyConnectionDataSourceProxy} → {@code AbstractRoutingDataSource} → пул) обёрнут
     * каждый, и без гарда один запрос попал бы в отчёт столько раз, сколько слоёв.
     * <p>
     * Это тот же приём, что у ассертов AssertJ и Spring (грабли §2 код-стандарта). Он берёт
     * то, чего не берёт структурная проверка {@link #wrapsOurProxy}: чужой декоратор вправе
     * назвать свой акцессор {@code getDelegate}, и перечислить все такие имена нельзя.
     * <p>
     * ⚠️ <b>Полным его считать нельзя</b>, хотя вопрос «этот запрос уже посчитан» и звучит
     * исчерпывающе: счётчик живёт на время ОДНОГО вызова, а {@code LazyConnectionDataSourceProxy}
     * спрашивает цель не в {@code getConnection}, а позже, при первом запросе. Замерено: без
     * структурной проверки такая цепочка даёт два шага на запрос. Потому механизма два.
     * <p>
     * ⚠️ Снимать ТОЛЬКО в {@code finally}: незакрытый флаг выключил бы SQL этого потока
     * до конца прогона — в том числе при броске из чужого пула.
     * <p>
     * ⚠️ <b>Цена механизма.</b> Флаг один на поток, а не на пул, поэтому глушится ЛЮБАЯ
     * вложенная выдача — даже когда пулы друг другу чужие. Мультиарендный резолвер, который
     * внутри своего {@code getConnection} спрашивает тенанта у отдельного метаданного пула,
     * потеряет в отчёте шаг этого запроса. Замерено. Ключ по самому пулу тут не помогает:
     * в цепочке из чужих декораторов объекты как раз РАЗНЫЕ, и по ключу гард бы не сработал
     * там, где он и нужен. Последовательные вызовы двух пулов дают два шага, как и ожидается.
     */
    private static final ThreadLocal<Boolean> HANDING_OUT = new ThreadLocal<>();

    /**
     * Акцессоры чужих обёрток, по которым видно наш прокси внутри цепочки: {@code getTargetDataSource}
     * у наследников {@code DelegatingDataSource}, цели у {@code AbstractRoutingDataSource}.
     * <p>
     * Рефлексия здесь не из-за отсутствия классов — spring-jdbc в classpath библиотеки есть
     * (`provided`). Причина в том, что у ПОТРЕБИТЕЛЯ его может не быть: ссылка на тип в этом
     * коде загружалась бы на каждом бине `DataSource` и валила бы обёртку
     * {@code NoClassDefFoundError} там, где сегодня она просто ничего не находит. Имена
     * стережёт канарейка {@code canary/InstrumentationApiCanaryTest.dataSourceChainAccessors}:
     * компилятор строки не проверяет, а переименование в Spring дало бы не падение,
     * а задвоенный SQL в отчёте.
     */
    private static final List<String> DELEGATE_ACCESSORS =
            List.of("getTargetDataSource", "getResolvedDefaultDataSource", "getResolvedDataSources");

    /**
     * Сколько бинов цепочки осматриваем. Три звена — уже мультиарендная схема из документации
     * Spring, плюс запас на несколько целей у роутера. Предел держит и циклы: чужой бин вправе
     * вернуть из акцессора самого себя, а зациклиться на старте контекста потребителя нельзя.
     * <p>
     * Публичная, потому что предел читает страж {@code autoconfig/AllureDataAutoConfigurationTest}:
     * копия числом сторожила бы своё представление о пределе, а не сам предел. Обход идёт
     * на КАЖДОМ бине {@code DataSource} у потребителя, поэтому вырасти незаметно он не должен.
     */
    public static final int CHAIN_LIMIT = 8;

    private AllureDataSourceProxies() {
    }

    /**
     * Бин с логированием SQL, остающийся объектом своего класса. Никогда не бросает: в худшем
     * случае отдаёт исходный бин нетронутым. Класс сохраняется у всякого бина, который вообще
     * имеет свой класс; у бина-JDK-прокси возвращается снова JDK-прокси с теми же интерфейсами —
     * своего класса у такого бина нет и по конкретному типу его никто не инжектит.
     * <p>
     * Ступени: не {@link DataSource} → как есть; уже обёрнут нами → как есть; класс
     * не подклассуется ({@code final}-метод, в том числе у чужого CGLIB-прокси) → исходный бин
     * и строка в лог; сбой построения → исходный бин и предупреждение. Обеднённый отчёт —
     * приемлемая цена, упавший контекст потребителя — нет.
     *
     * @param beanName имя бина: единственное, чем пулы различимы в логе и в предупреждении
     */
    public static Object wrap(Object bean, String beanName) {
        try {
            if (!(bean instanceof DataSource ds)
                    || bean instanceof AllureProxiedDataSource
                    || bean instanceof ProxyDataSource
                    || wrapsOurProxy(ds)) {
                return bean;
            }
            // JDK-прокси (встроенная база, декоратор по интерфейсу) проксируем ПО ИНТЕРФЕЙСАМ:
            // подклассовать нечего, пустых полей не будет, final-методы не мешают.
            boolean byInterfaces = Proxy.isProxyClass(ds.getClass());
            Optional<String> blocked = byInterfaces ? Optional.empty() : cannotSubclass(ds);
            if (blocked.isPresent()) {
                // Пользовательский класс, а не сгенерированный: имя вида Пул$$SpringCGLIB$$0
                // читателю ничего не говорит, а в чужом логе выглядит поломкой.
                AllureInstrumentationLogger.note("DbDataSource", "пул " + beanName
                        + " (" + ClassUtils.getUserClass(ds.getClass()).getName() + ") не проксируем: "
                        + blocked.get() + " — вложенных шагов SQL этого пула в отчёте не будет");
                return bean;
            }
            return proxy(ds, beanName, byInterfaces);
        } catch (Throwable t) {
            AllureInstrumentationLogger.warn("DbDataSource", t);
            return bean;
        }
    }

    /**
     * Почему подкласс завести нельзя, или пусто, если можно. Все три причины —
     * спроектированные исходы, поэтому о них говорим строкой без стека, а не как о сбое.
     * <p>
     * Причину называем настоящую: у чужого AOP-прокси {@code final} все сгенерированные
     * методы, и указание на случайный из них отправило бы читателя искать несуществующую
     * проблему в JDBC API. По той же причине порядок веток именно такой.
     */
    private static Optional<String> cannotSubclass(DataSource ds) {
        if (ds instanceof SpringProxy) {
            return Optional.of("на нём уже висит чужой Spring AOP (у прокси все методы final)");
        }
        if (Modifier.isFinal(ds.getClass().getModifiers())) {
            return Optional.of("класс объявлен final, подкласс завести нельзя");
        }
        return finalMethod(ds.getClass())
                .map(blocker -> "метод " + blocker.getName() + " объявлен final и не перехватывается");
    }

    private static Object proxy(DataSource ds, String beanName, boolean byInterfaces) {
        DataSource logged = ProxyDataSourceBuilder.create(ds)
                .name(beanName)
                .listener(new AllureDataSourceListener())
                .build();

        NameMatchMethodPointcut byName = new NameMatchMethodPointcut();
        byName.addMethodName("getConnection");

        ProxyFactory factory = new ProxyFactory(ds);
        factory.setProxyTargetClass(!byInterfaces);
        factory.addInterface(AllureProxiedDataSource.class);
        factory.addAdvisor(new DefaultPointcutAdvisor(byName,
                (MethodInterceptor) call -> connection(logged, call)));

        // Загрузчик БИНА, а не библиотеки: у потребителя с DevTools классы приложения живут в
        // дочернем загрузчике, который видит нашу библиотеку, а она его классы — нет.
        ClassLoader loader = ds.getClass().getClassLoader();
        return factory.getProxy(loader != null ? loader : AllureDataSourceProxies.class.getClassLoader());
    }

    /**
     * Соединение через datasource-proxy — но только для двух сигнатур из {@link DataSource}.
     * Pointcut отбирает методы по ИМЕНИ, поэтому сюда доезжают и чужие перегрузки; их отдаём
     * настоящему пулу через {@code proceed()}, теряя лишь логирование SQL этого вызова.
     */
    private static Object connection(DataSource logged, MethodInvocation call) throws Throwable {
        Class<?>[] parameters = call.getMethod().getParameterTypes();
        boolean ours = parameters.length == 0
                || (parameters.length == 2 && parameters[0] == String.class && parameters[1] == String.class);
        if (!ours || Boolean.TRUE.equals(HANDING_OUT.get())) {
            return call.proceed();
        }
        HANDING_OUT.set(Boolean.TRUE);
        try {
            return parameters.length == 0
                    ? logged.getConnection()
                    : logged.getConnection((String) call.getArguments()[0], (String) call.getArguments()[1]);
        } finally {
            HANDING_OUT.remove();
        }
    }

    /**
     * Лежит ли наш прокси где-то ВНУТРИ этого бина. Тогда обёртка не нужна: запрос уже считает
     * внутренний слой, а второй писал бы его в отчёт дважды.
     * <p>
     * Обходим вглубь, а не на один шаг: в раскладке {@code LazyConnectionDataSourceProxy →
     * AbstractRoutingDataSource → пул} наш прокси лежит двумя уровнями ниже. У роутера целей
     * несколько, поэтому это обход дерева, а не спуск по ссылке.
     */
    private static boolean wrapsOurProxy(DataSource ds) {
        Deque<Object> queue = new ArrayDeque<>();
        queue.add(ds);
        for (int visited = 0; !queue.isEmpty() && visited < CHAIN_LIMIT; visited++) {
            Object node = queue.poll();
            for (String accessor : DELEGATE_ACCESSORS) {
                for (Object target : targets(read(node, accessor))) {
                    if (target instanceof AllureProxiedDataSource) {
                        return true;
                    }
                    if (target instanceof DataSource) {
                        queue.add(target);
                    }
                }
            }
        }
        return false;
    }

    /** Значение акцессора как список целей: у роутера это карта, у прочих — один бин. */
    private static List<Object> targets(Object value) {
        if (value instanceof Map<?, ?> map) {
            // Карта чужая: null в значениях уронил бы List.copyOf, и наш собственный
            // NullPointerException уехал бы потребителю как «сбой инструментирования».
            return map.values().stream().filter(Objects::nonNull).map(Object.class::cast).toList();
        }
        return value == null ? List.of() : List.of(value);
    }

    /**
     * Значение акцессора или {@code null}, если его нет, он не про цели или бросил.
     * <p>
     * Метод ищем на первом ПУБЛИЧНОМ классе иерархии, а не на классе объекта: у приватной
     * обёртки потребителя ({@code private static class TenantRouter extends
     * AbstractRoutingDataSource}) вызов метода, объявленного в непубличном классе, бросает
     * {@code IllegalAccessException}, и защита молча выключалась бы — замерено.
     * Виртуальная диспетчеризация всё равно приводит к переопределению.
     * {@code setAccessible} не годится: он потянул бы {@code --add-opens} у потребителя.
     * <p>
     * Тип возврата сверяем ДО вызова: у чужого класса метод с таким именем может значить что
     * угодно, и звать его ради «вдруг подойдёт» мы не вправе.
     */
    private static Object read(Object target, String accessor) {
        for (Class<?> declaring : declarationCandidates(target.getClass())) {
            if (!Modifier.isPublic(declaring.getModifiers())) {
                continue;
            }
            try {
                Method method = declaring.getMethod(accessor);
                Class<?> returns = method.getReturnType();
                if (!DataSource.class.isAssignableFrom(returns) && !Map.class.isAssignableFrom(returns)) {
                    return null;
                }
                return method.invoke(target);
            } catch (Throwable lookFurther) {
                // И «метода тут нет», и «вызов не удался» значат одно: доступного объявления
                // здесь не нашлось — ищем дальше. Обрыв поиска на первом же сбое выключал бы
                // защиту у бина, чей акцессор объявлен ниже по списку.
                continue;
            }
        }
        return null;
    }

    /**
     * Где искать объявление акцессора: сперва классы иерархии, потом публичные интерфейсы.
     * <p>
     * Интерфейсы нужны не для полноты: непубличный класс потребителя, реализующий публичный
     * интерфейс с этим методом, иначе прячет объявление целиком — вызов падает
     * {@code IllegalAccessException}, защита молча выключается, и запрос попадает в отчёт
     * дважды. Замерено. Тот же приём Spring применяет в {@code ClassUtils.getInterfaceMethodIfPossible}.
     */
    private static List<Class<?>> declarationCandidates(Class<?> type) {
        List<Class<?>> candidates = new ArrayList<>();
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            candidates.add(current);
        }
        candidates.addAll(Arrays.asList(ClassUtils.getAllInterfacesForClass(type)));
        return candidates;
    }

    /**
     * Метод, мешающий завести подкласс, или пусто. Считаем так же, как считает CGLIB: обходим
     * иерархию и берём {@code final} не статические и не приватные — {@code getMethods()} здесь
     * мало, потому что {@code protected final} и пакетный {@code final} тоже не переопределяются
     * и тоже выполнились бы на пустом экземпляре. Методы {@link Object} не в счёт: они final
     * у всех и настоящему объекту ничего не должны.
     * <p>
     * Берём наименьший по имени, а не первый попавшийся: порядок {@code getDeclaredMethods()}
     * не определён, и без сортировки предупреждение называло бы разные методы на разных
     * прогонах и разных JDK.
     */
    private static Optional<Method> finalMethod(Class<?> type) {
        Method found = null;
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            for (Method method : current.getDeclaredMethods()) {
                int modifiers = method.getModifiers();
                if (Modifier.isFinal(modifiers) && !Modifier.isStatic(modifiers) && !Modifier.isPrivate(modifiers)
                        && (found == null || method.getName().compareTo(found.getName()) < 0)) {
                    found = method;
                }
            }
        }
        return Optional.ofNullable(found);
    }
}
