package io.github.kolomyychenkoai.allure.spring.data.internal;

import io.github.kolomyychenkoai.allure.spring.data.internal.AllureDataSourceProxies.AllureProxiedDataSource;
import org.aopalliance.intercept.MethodInterceptor;
import org.springframework.aop.SpringProxy;
import org.springframework.aop.framework.AopProxyUtils;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.aop.support.DefaultPointcutAdvisor;
import org.springframework.aop.support.NameMatchMethodPointcut;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.jdbc.metadata.DataSourcePoolMetadataProvider;

import javax.sql.DataSource;
import java.lang.reflect.Modifier;

/**
 * Счётчики соединений пула считаются по НАСТОЯЩЕМУ пулу, а не по нашей обёртке.
 * <p>
 * Что ломалось. {@code HikariDataSourcePoolMetadata} берёт пул не методом, а ПОЛЕМ:
 * {@code new DirectFieldAccessor(getDataSource()).getPropertyValue("pool")}. Поле {@code pool}
 * заполняется внутри {@code getConnection()} — то есть на объекте, который реально выдаёт
 * соединения. У нас это ЦЕЛЬ, а не прокси: {@code getConnection} мы перехватываем и уводим
 * в цель, поэтому у прокси поле так и остаётся пустым, и {@code getActive()},
 * {@code getIdle()} и производный от них {@code DataSourcePoolMetadata.getUsage()} возвращают {@code null}. Замерено на
 * живом пуле H2: цель active=0 / idle=1, обёртка — null / null (issue #87).
 * <p>
 * Область ровно эта. {@code getMax()} и {@code getMin()} читаются методами конфигурации
 * и работали всегда. Health-индикатор пула спрашивает {@code getValidationQuery()} — тоже
 * метод, он делегируется сквозь прокси и без нас. JMX-ветка Boot к провайдерам метаданных
 * не ходит вовсе. Полем читаются ТОЛЬКО счётчики соединений.
 * <p>
 * Почему постпроцессор, а не свой провайдер с {@code @Order}. Метрическая ветка собирает
 * провайдеров через {@code ObjectProvider.stream()}, а он отдаёт бины в порядке РЕГИСТРАЦИИ
 * и по {@code @Order} не сортирует — победа своего провайдера зависела бы от того, чья
 * автоконфигурация зарегистрировалась раньше. (Health-ветка, наоборот, берёт
 * {@code ObjectProvider.orderedStream()} и порядок уважает — тем важнее его не сломать.)
 * Здесь порядок не важен вовсе: оборачивается каждый провайдер, чей бы он ни был.
 * <p>
 * ⚠️ Обёртка обязана сохранять КЛАСС и интерфейсы чужого бина. Провайдер потребителя может
 * быть объявлен своим классом и запрошен по нему, а порядок на health-ветке держится на
 * {@code Ordered}. Подменить бин объектом своего класса значило бы завести здесь тот самый
 * дефект, от которого лечились в issue #54: контекст потребителя падает с
 * {@code BeanNotOfRequiredTypeException}. Поэтому приём тот же, что у {@link AllureDataSourceProxies}:
 * подкласс самого бина, а где подкласс завести нельзя (лямбда — а провайдеры Boot это лямбды) —
 * прокси по ВСЕМ его интерфейсам, что сохраняет и {@code Ordered}.
 * <p>
 * Спрашиваем подменённым аргументом, а не подменяем ответ: провайдеры чужие, логика выбора
 * пула остаётся за ними, мы лишь показываем им тот объект, про который они умеют отвечать.
 * <p>
 * Держит {@code poolMetricsSeeTheRealPoolThroughTheProxy}, {@code poolGaugesReachTheMeterRegistry},
 * {@code foreignProviderKeepsItsClassAndOrder}, {@code foreignDataSourceProxyIsLeftAlone}.
 */
public final class AllurePoolMetadataUnwrapper implements BeanPostProcessor {

    /** Маркер своей обёртки: второй раз не заворачиваем. */
    public interface AllureUnwrappingProvider {
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (!(bean instanceof DataSourcePoolMetadataProvider provider)
                || bean instanceof AllureUnwrappingProvider) {
            return bean;
        }

        NameMatchMethodPointcut byName = new NameMatchMethodPointcut();
        byName.addMethodName("getDataSourcePoolMetadata");

        ProxyFactory factory = new ProxyFactory(bean);
        factory.setProxyTargetClass(canSubclass(bean));
        factory.addInterface(AllureUnwrappingProvider.class);
        factory.addAdvisor(new DefaultPointcutAdvisor(byName, (MethodInterceptor) call ->
                provider.getDataSourcePoolMetadata(realPool((DataSource) call.getArguments()[0]))));

        ClassLoader loader = bean.getClass().getClassLoader();
        return factory.getProxy(loader != null ? loader : AllurePoolMetadataUnwrapper.class.getClassLoader());
    }

    /**
     * Можно ли завести подкласс. Провайдеры Boot — лямбды, их классы {@code final}, и для них
     * ответ «нет»: пойдём по интерфейсам. Класс потребителя обычно обычный — тогда подкласс,
     * и инъекция по конкретному типу у него продолжает собираться.
     */
    private static boolean canSubclass(Object bean) {
        return !(bean instanceof SpringProxy)
                && !Modifier.isFinal(bean.getClass().getModifiers())
                && !bean.getClass().isSynthetic();
    }

    /** Наша обёртка → её цель. Всё остальное, включая чужие прокси, отдаём как есть. */
    private static DataSource realPool(DataSource dataSource) {
        if (dataSource instanceof AllureProxiedDataSource
                && AopProxyUtils.getSingletonTarget(dataSource) instanceof DataSource target) {
            return target;
        }
        return dataSource;
    }
}
