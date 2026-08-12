package io.github.kolomyychenkoai.allure.spring.data;

import io.github.kolomyychenkoai.allure.spring.data.internal.AllureDataSourceListener;
import io.github.kolomyychenkoai.allure.spring.data.internal.AllureDataSourceProxies;
import net.ttddyy.dsproxy.support.ProxyDataSourceBuilder;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;

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
 */
@AutoConfiguration
@ConditionalOnClass({DataSource.class, ProxyDataSourceBuilder.class, ProxyFactory.class})
public class AllureDataSourceAutoConfiguration {

    @Bean
    public static BeanPostProcessor allureDataSourceProxyPostProcessor() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                return AllureDataSourceProxies.wrap(bean);
            }
        };
    }
}
