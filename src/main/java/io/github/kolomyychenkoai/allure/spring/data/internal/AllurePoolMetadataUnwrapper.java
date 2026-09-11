package io.github.kolomyychenkoai.allure.spring.data.internal;

import io.github.kolomyychenkoai.allure.spring.data.internal.AllureDataSourceProxies.AllureProxiedDataSource;
import org.springframework.aop.framework.AopProxyUtils;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.jdbc.metadata.DataSourcePoolMetadata;
import org.springframework.boot.jdbc.metadata.DataSourcePoolMetadataProvider;

import javax.sql.DataSource;

/**
 * Метрики и health пула читаются с НАСТОЯЩЕГО пула, а не с нашей обёртки.
 * <p>
 * Зачем. {@code HikariDataSourcePoolMetadata} достаёт пул не методом, а ПОЛЕМ:
 * {@code new DirectFieldAccessor(getDataSource()).getPropertyValue("pool")}. Наш прокси —
 * CGLIB-подкласс самого пула, и создаёт его Objenesis, минуя конструктор, поэтому
 * унаследованное поле {@code pool} пустое. Методы делегируются, поле — нет. Без этого
 * постпроцессора {@code getActive()} и {@code getIdle()} у обёрнутого пула возвращают
 * {@code null}, и метрики {@code jdbc.connections.active} и {@code jdbc.connections.idle}
 * у потребителя ПРОПАДАЮТ молча. Замерено: настоящий пул active=0/idle=1, обёрнутый —
 * null/null, при том что {@code getMax()} и {@code getMin()} совпадают (issue #87).
 * <p>
 * Почему постпроцессор, а не свой {@code DataSourcePoolMetadataProvider} с {@code @Order}.
 * Boot собирает провайдеры через {@code ObjectProvider.stream()}, а он отдаёт бины в порядке
 * РЕГИСТРАЦИИ и по {@code @Order} не сортирует — победа своего провайдера зависела бы от
 * того, чья автоконфигурация зарегистрировалась раньше. Здесь порядок не важен вовсе:
 * оборачивается каждый провайдер, чей бы он ни был.
 * <p>
 * Спрашиваем подменённым аргументом, а не подменяем ответ: провайдеры чужие, их логика
 * выбора пула остаётся за ними, мы лишь показываем им тот объект, про который они умеют
 * отвечать. Не-нашу обёртку и не-прокси пропускаем нетронутыми.
 * <p>
 * Держит {@code poolMetricsSeeTheRealPoolThroughTheProxy}. Мутация: вернуть {@code bean}
 * без обёртки → active и idle снова {@code null} → RED.
 */
public final class AllurePoolMetadataUnwrapper implements BeanPostProcessor {

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (!(bean instanceof DataSourcePoolMetadataProvider delegate)) {
            return bean;
        }
        return new UnwrappingProvider(delegate);
    }

    /** Именованный тип, а не лямбда: в дереве бинов потребителя должно быть видно, чьё это. */
    record UnwrappingProvider(DataSourcePoolMetadataProvider delegate)
            implements DataSourcePoolMetadataProvider {

        @Override
        public DataSourcePoolMetadata getDataSourcePoolMetadata(DataSource dataSource) {
            return delegate.getDataSourcePoolMetadata(realPool(dataSource));
        }
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
