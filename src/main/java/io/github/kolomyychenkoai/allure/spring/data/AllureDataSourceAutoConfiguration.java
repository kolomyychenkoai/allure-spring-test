package io.github.kolomyychenkoai.allure.spring.data;

import io.github.kolomyychenkoai.allure.spring.data.internal.AllureDataSourceListener;
import io.github.kolomyychenkoai.allure.spring.data.internal.AllureDataSourceProxies;
import io.github.kolomyychenkoai.allure.spring.data.internal.AllurePoolMetadataUnwrapper;
import net.ttddyy.dsproxy.support.ProxyDataSourceBuilder;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.jdbc.metadata.DataSourcePoolMetadataProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * Авто-активация логирования реального SQL: бин {@link DataSource} отдаёт соединения через
 * datasource-proxy с {@link AllureDataSourceListener}. Включается сама, если на classpath есть
 * datasource-proxy — потребителю код не нужен.
 * Регистрируется через {@code META-INF/spring/...AutoConfiguration.imports}.
 * <p>
 * Бин при этом ОСТАЁТСЯ объектом своего класса: инъекция по конкретному типу
 * ({@code HikariDataSource} у ShedLock и подобных) продолжает собираться. Как это устроено и
 * что делать, когда прокси построить нельзя, — {@link AllureDataSourceProxies}.
 * <p>
 * Обёртка ломала бы метрики и health пула: Boot читает их ПОЛЕМ, а не методом, и у прокси
 * без конструктора поле пустое. Чинит это {@link AllurePoolMetadataUnwrapper} (issue #87).
 */
@AutoConfiguration
@ConditionalOnClass({DataSource.class, ProxyDataSourceBuilder.class, ProxyFactory.class})
public class AllureDataSourceAutoConfiguration {

    @Bean
    public static BeanPostProcessor allureDataSourceProxyPostProcessor() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                return AllureDataSourceProxies.wrap(bean, beanName);
            }
        };
    }

    /**
     * Отдельный гард: метрики и health пула — не наша обязанность, и модуль SQL обязан
     * работать без них. Тип берётся у Boot и в 3.x, и в 4.x лежит по одному имени
     * {@code org.springframework.boot.jdbc.metadata.DataSourcePoolMetadataProvider} — в 3.x
     * внутри {@code spring-boot}, в 4.x внутри {@code spring-boot-jdbc}. Нет его на classpath —
     * постпроцессора нет, и остальное работает.
     * <p>
     * Держит {@code poolMetadataUnwrapperAbsentWithoutBootJdbc}.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(DataSourcePoolMetadataProvider.class)
    public static class PoolMetadataConfiguration {

        @Bean
        public static BeanPostProcessor allurePoolMetadataUnwrapper() {
            return new AllurePoolMetadataUnwrapper();
        }
    }
}
