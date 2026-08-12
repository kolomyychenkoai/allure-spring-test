package io.github.kolomyychenkoai.allure.spring.data.internal;

import io.github.kolomyychenkoai.allure.spring.internal.AllureInstrumentationLogger;
import net.ttddyy.dsproxy.support.ProxyDataSource;
import net.ttddyy.dsproxy.support.ProxyDataSourceBuilder;
import org.aopalliance.intercept.MethodInterceptor;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.aop.support.DefaultPointcutAdvisor;
import org.springframework.aop.support.NameMatchMethodPointcut;

import javax.sql.DataSource;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * Оборачивает бин {@link DataSource} так, что он ОСТАЁТСЯ объектом своего класса.
 * <p>
 * <b>Зачем.</b> Раньше бин подменялся объектом чужого класса {@link ProxyDataSource}, и у
 * потребителя переставала собираться инъекция по конкретному типу — например
 * {@code lockProvider(@Qualifier("lockedDataSource") HikariDataSource ds)} у ShedLock.
 * Контекст не поднимался вовсе, то есть библиотека, добавленная ради отчёта, роняла все
 * тесты до первого шага (issue #54). Так же ломались {@code getBean(HikariDataSource.class)},
 * {@code @ConditionalOnBean} по типу пула и проверки {@code isInstanceOf} в чужих тестах.
 * <p>
 * <b>Что нужно на самом деле.</b> Каналу реального SQL требуется ровно одно: чтобы
 * {@link DataSource#getConnection()} вернул соединение, обёрнутое datasource-proxy — из него
 * и стреляет {@link AllureDataSourceListener}. Все прочие методы {@link ProxyDataSource}
 * просто делегируют. Поэтому подменяем не объект, а поведение двух методов: отдаём подкласс
 * исходного класса (Spring AOP, CGLIB), у которого {@code getConnection} уходит в
 * datasource-proxy, а остальное попадает в настоящий бин.
 * <p>
 * <b>Побочно чинится то, о чём не сообщали.</b> Spring Boot умеет смотреть сквозь AOP-прокси
 * ({@code DataSourceUnwrapper} зовёт {@code AopProxyUtils.getSingletonTarget}), а сквозь
 * {@link ProxyDataSource} не умеет. Пока бин подменялся, у потребителя молча выключались
 * метрики пула Hikari, его health-контрибьютор и {@code DataSourcePoolMetadataProvider}.
 * <p>
 * <b>Экземпляр подкласса создаётся без конструктора</b> (Objenesis внутри Spring AOP), то есть
 * все его поля пусты. Это безопасно ровно до тех пор, пока каждый вызов уходит в настоящий
 * объект — и потому класс с {@code final}-методом мы не проксируем совсем (ступень 3 в
 * {@link #wrap}): такой метод CGLIB не перехватит, и он выполнится на пустом экземпляре,
 * тихо вернув {@code null} вместо значения.
 * <p>
 * ⚠️ <b>Бин, который УЖЕ обёрнут чужим Spring AOP</b> (свой аспект потребителя на
 * {@code DataSource}), проксирован не будет: у сгенерированного класса прокси методы
 * объявлены {@code final}, и мы уходим в деградацию с предупреждением. Такой пул останется
 * без реального SQL; шаги вызовов {@code DB JdbcTemplate.*} и {@code DB Repo.method} при этом
 * пишутся как обычно — их даёт байткод, а не обёртка.
 * <p>
 * ⚠️ <b>Идентичность бина всё равно меняется</b> (issue #59): {@code ds != исходный},
 * {@code getClass()} даёт CGLIB-подкласс, рефлексия по полям видит пустой экземпляр.
 * Достать настоящий объект можно через {@code AopProxyUtils.getSingletonTarget}.
 * <p>
 * ⚠️ <b>{@code ProxyDataSource} строится поверх СЫРОГО бина, а не поверх нашего прокси</b> —
 * иначе {@code getConnection} звал бы сам себя до переполнения стека.
 * <p>
 * ⚠️ <b>Не ставить {@code factory.setOpaque(true)}</b>: непрозрачный прокси не отдаёт
 * {@code Advised}, и Spring Boot снова перестанет находить настоящий пул.
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
     * Без него второй заход тоже вернул бы тот же объект, но окольным путём: сгенерированный
     * класс прокси объявляет свои методы {@code final}, и сработала бы предпроверка из
     * {@link #wrap} — с предупреждением в логе на каждый бин. Замерено мутацией, а не выведено:
     * по одному только «тот же объект» два пути неразличимы, и стережёт их разницу
     * {@code autoconfig/AllureDataAutoConfigurationTest.doesNotDoubleWrapOwnProxy}.
     */
    public interface AllureProxiedDataSource {
    }

    private AllureDataSourceProxies() {
    }

    /**
     * Бин с логированием SQL, остающийся объектом своего класса. Никогда не бросает и никогда
     * не возвращает объект чужого класса: в худшем случае отдаёт исходный бин нетронутым.
     * <p>
     * Ступени: не {@link DataSource} → как есть; уже обёрнут → как есть; есть
     * {@code final}-метод → исходный бин и предупреждение; сбой построения прокси → исходный
     * бин и предупреждение. Обеднённый отчёт — приемлемая цена, упавший контекст потребителя —
     * нет.
     */
    public static Object wrap(Object bean) {
        try {
            if (!(bean instanceof DataSource ds)
                    || bean instanceof AllureProxiedDataSource
                    || bean instanceof ProxyDataSource) {
                return bean;
            }
            Method blocker = finalMethod(ds.getClass());
            if (blocker != null) {
                AllureInstrumentationLogger.warn("DbDataSource", new IllegalStateException(
                        "не проксируем " + ds.getClass().getName() + ": метод " + blocker.getName()
                                + " объявлен final, перехватить его нельзя — SQL этого пула в отчёт не попадёт"));
                return bean;
            }
            return proxy(ds);
        } catch (Throwable t) {
            AllureInstrumentationLogger.warn("DbDataSource", t);
            return bean;
        }
    }

    private static Object proxy(DataSource ds) {
        DataSource logged = ProxyDataSourceBuilder.create(ds)
                .name("allure")
                .listener(new AllureDataSourceListener())
                .build();

        NameMatchMethodPointcut onlyGetConnection = new NameMatchMethodPointcut();
        onlyGetConnection.addMethodName("getConnection");

        ProxyFactory factory = new ProxyFactory(ds);
        factory.setProxyTargetClass(true);
        factory.addInterface(AllureProxiedDataSource.class);
        factory.addAdvisor(new DefaultPointcutAdvisor(onlyGetConnection, (MethodInterceptor) call ->
                call.getArguments().length == 0
                        ? logged.getConnection()
                        : logged.getConnection((String) call.getArguments()[0], (String) call.getArguments()[1])));

        // Загрузчик БИНА, а не библиотеки: у потребителя с DevTools классы приложения живут в
        // дочернем загрузчике, который видит нашу библиотеку, а она его классы — нет.
        ClassLoader loader = ds.getClass().getClassLoader();
        return factory.getProxy(loader != null ? loader : AllureDataSourceProxies.class.getClassLoader());
    }

    /**
     * Первый {@code public final} метод класса или {@code null}. Методы {@link Object}
     * ({@code wait}, {@code notify}, {@code getClass}) не считаются: они final у всех и
     * настоящему объекту ничего не должны.
     */
    private static Method finalMethod(Class<?> type) {
        for (Method method : type.getMethods()) {
            if (method.getDeclaringClass() != Object.class
                    && Modifier.isFinal(method.getModifiers())
                    && !Modifier.isStatic(method.getModifiers())) {
                return method;
            }
        }
        return null;
    }
}
