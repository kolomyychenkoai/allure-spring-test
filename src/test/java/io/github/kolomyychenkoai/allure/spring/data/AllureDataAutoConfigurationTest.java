package io.github.kolomyychenkoai.allure.spring.data;

import net.ttddyy.dsproxy.support.ProxyDataSource;
import net.ttddyy.dsproxy.support.ProxyDataSourceBuilder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Уровень A: авто-конфиги и защита от двойной обёртки DataSource.
 * Тесты падают, если фича перестанет включаться или начнёт оборачивать уже обёрнутый DataSource второй раз.
 */
class AllureDataAutoConfigurationTest {

    @Test
    @DisplayName("JPA-аспект: бин есть по умолчанию")
    void repositoryAspectPresentByDefault() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(AllureDataJpaAutoConfiguration.class))
                .run(ctx -> assertThat(ctx).hasSingleBean(AllureRepositoryAspect.class));
    }

    @Test
    @DisplayName("DataSource-прокси: BeanPostProcessor есть по умолчанию")
    void dataSourceProcessorPresentByDefault() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(AllureDataSourceAutoConfiguration.class))
                .run(ctx -> assertThat(ctx).hasBean("allureDataSourceProxyPostProcessor"));
    }

    @Test
    @DisplayName("обычный DataSource оборачивается в ProxyDataSource")
    void wrapsPlainDataSource() {
        BeanPostProcessor bpp = AllureDataSourceAutoConfiguration.allureDataSourceProxyPostProcessor();

        Object wrapped = bpp.postProcessAfterInitialization(mock(DataSource.class), "ds");

        assertThat(wrapped).isInstanceOf(ProxyDataSource.class);
    }

    @Test
    @DisplayName("уже обёрнутый DataSource не оборачивается второй раз (тот же объект)")
    void doesNotDoubleWrap() {
        BeanPostProcessor bpp = AllureDataSourceAutoConfiguration.allureDataSourceProxyPostProcessor();
        ProxyDataSource already = ProxyDataSourceBuilder.create(mock(DataSource.class)).build();

        Object result = bpp.postProcessAfterInitialization(already, "ds");

        assertThat(result).isSameAs(already);
    }

    @Test
    @DisplayName("не-DataSource бин возвращается как есть")
    void leavesNonDataSourceUntouched() {
        BeanPostProcessor bpp = AllureDataSourceAutoConfiguration.allureDataSourceProxyPostProcessor();

        Object bean = "не датасорс";
        assertThat(bpp.postProcessAfterInitialization(bean, "x")).isSameAs(bean);
    }
}
