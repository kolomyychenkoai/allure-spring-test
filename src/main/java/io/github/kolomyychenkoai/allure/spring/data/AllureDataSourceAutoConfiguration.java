package io.github.kolomyychenkoai.allure.spring.data;

import net.ttddyy.dsproxy.support.ProxyDataSource;
import net.ttddyy.dsproxy.support.ProxyDataSourceBuilder;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

import javax.sql.DataSource;

/**
 * Авто-активация логирования реального SQL: оборачивает любой бин {@link DataSource}
 * в datasource-proxy с {@link AllureDataSourceListener}. Включается сама, если на
 * classpath есть datasource-proxy — потребителю код не нужен.
 * <p>
 * Обёртка постоянная (не только на время теста), поэтому есть тумблер выключения
 * {@code allure.spring.datasource.enabled=false} — на случай, если у потребителя свои
 * обёртки DataSource или метрики пула, которым мешает прокси.
 * Регистрируется через {@code META-INF/spring/...AutoConfiguration.imports}.
 */
@AutoConfiguration
@ConditionalOnClass({DataSource.class, ProxyDataSourceBuilder.class})
@ConditionalOnProperty(name = "allure.spring.datasource.enabled", matchIfMissing = true)
public class AllureDataSourceAutoConfiguration {

    @Bean
    public static BeanPostProcessor allureDataSourceProxyPostProcessor() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof DataSource ds && !(bean instanceof ProxyDataSource)) {
                    return ProxyDataSourceBuilder.create(ds)
                            .name("allure")
                            .listener(new AllureDataSourceListener())
                            .build();
                }
                return bean;
            }
        };
    }
}
