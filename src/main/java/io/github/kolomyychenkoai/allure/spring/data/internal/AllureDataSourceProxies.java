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

import javax.sql.DataSource;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

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
 * тихо вернёт {@code null} вместо значения. К бину, который сам JDK-прокси (встроенная база,
 * декоратор по интерфейсу), это не относится: подкласс там не нужен, хватает прокси по
 * интерфейсам. ⚠️ При {@code -Dspring.objenesis.ignore=true} Spring уходит в штатный фолбэк и
 * конструктор пула всё-таки вызывается — один раз, на создании прокси.
 * <p>
 * ⚠️ <b>Чужие перегрузки {@code getConnection} уходят в пул нетронутыми.</b> У Oracle UCP это
 * {@code getConnection(Properties)} и {@code getConnection(String, String, Properties)} — метки
 * соединения, по которым пул выбирает, что отдать. В datasource-proxy заводим ТОЛЬКО две
 * сигнатуры из {@link DataSource}, остальное идёт в настоящий пул без логирования SQL.
 * Разбирать чужие перегрузки «как умеем» нельзя: приведение аргумента к {@code String} падает
 * прямо в коде потребителя, а выброшенный лишний аргумент меняет смысл вызова молча.
 * <p>
 * ⚠️ <b>Цепочку бинов оборачиваем один раз.</b> {@code LazyConnectionDataSourceProxy} или
 * {@code AbstractRoutingDataSource} поверх пула — это ДВА бина, и обёртка на каждом дала бы
 * каждый запрос в отчёте дважды. Бин, чья цель — уже наш прокси, пропускаем.
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
     * внутри {@code Advised}. По нему же узнаём свою обёртку внутри чужой цепочки бинов.
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
     * Методы-акцессоры чужих обёрток, по которым видно нашу обёртку внутри цепочки. Только
     * чтение уже вычисленного: {@code getTargetDataSource} у наследников
     * {@code DelegatingDataSource}, карта целей у {@code AbstractRoutingDataSource}. Ищем
     * рефлексией, потому что spring-jdbc в compile-classpath библиотеки нет (тот же приём,
     * что в {@code JpaLaziness}).
     */
    private static final List<String> DELEGATE_ACCESSORS =
            List.of("getTargetDataSource", "getResolvedDefaultDataSource", "getResolvedDataSources");

    private AllureDataSourceProxies() {
    }

    /**
     * Бин с логированием SQL, остающийся объектом своего класса. Никогда не бросает и никогда
     * не возвращает объект чужого класса: в худшем случае отдаёт исходный бин нетронутым.
     * <p>
     * Ступени: не {@link DataSource} → как есть; уже обёрнут нами, сам является нашей обёрткой
     * или обёрткой над ней → как есть; класс не подклассуется ({@code final}-метод, в том числе
     * у чужого CGLIB-прокси) → исходный бин и строка в лог; сбой построения → исходный бин
     * и предупреждение. Обеднённый отчёт — приемлемая цена, упавший контекст потребителя — нет.
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
                AllureInstrumentationLogger.note("DbDataSource", "пул " + beanName
                        + " (" + ds.getClass().getName() + ") не проксируем: " + blocked.get()
                        + " — вложенных шагов SQL этого пула в отчёте не будет");
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
        if (parameters.length == 0) {
            return logged.getConnection();
        }
        if (parameters.length == 2 && parameters[0] == String.class && parameters[1] == String.class) {
            return logged.getConnection((String) call.getArguments()[0], (String) call.getArguments()[1]);
        }
        return call.proceed();
    }

    /**
     * Бин-обёртка, внутри которой уже лежит наш прокси. Второй слой писал бы каждый запрос
     * в отчёт дважды: ручник увидел бы задвоенные шаги SQL.
     * <p>
     * Порядок создания бинов на нашей стороне: обёртка инициализируется ПОСЛЕ своей цели,
     * поэтому к моменту проверки цель уже наша. Обратный порядок (цель приходит лениво)
     * защиту обходит — тогда снаружи останется два слоя, и это видно по задвоению в отчёте.
     */
    private static boolean wrapsOurProxy(DataSource ds) {
        for (String accessor : DELEGATE_ACCESSORS) {
            Object target = read(ds, accessor);
            if (target instanceof AllureProxiedDataSource) {
                return true;
            }
            if (target instanceof Map<?, ?> targets
                    && targets.values().stream().anyMatch(AllureProxiedDataSource.class::isInstance)) {
                return true;
            }
        }
        return false;
    }

    /** Прочитать значение акцессора или {@code null}, если его нет и если он бросил. */
    private static Object read(Object target, String accessor) {
        try {
            return target.getClass().getMethod(accessor).invoke(target);
        } catch (Throwable notThere) {
            return null;
        }
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
        Optional<Method> found = Optional.empty();
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            Optional<Method> here = Arrays.stream(current.getDeclaredMethods())
                    .filter(method -> Modifier.isFinal(method.getModifiers())
                            && !Modifier.isStatic(method.getModifiers())
                            && !Modifier.isPrivate(method.getModifiers()))
                    .min(Comparator.comparing(Method::getName));
            found = Stream.concat(found.stream(), here.stream())
                    .min(Comparator.comparing(Method::getName));
        }
        return found;
    }
}
