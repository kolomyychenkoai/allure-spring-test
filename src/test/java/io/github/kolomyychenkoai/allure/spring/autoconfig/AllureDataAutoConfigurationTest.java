package io.github.kolomyychenkoai.allure.spring.autoconfig;

import com.zaxxer.hikari.HikariDataSource;
import io.qameta.allure.Epic;
import io.github.kolomyychenkoai.allure.spring.data.AllureDataSourceAutoConfiguration;
import io.github.kolomyychenkoai.allure.spring.data.AllureDataJpaAutoConfiguration;

import io.github.kolomyychenkoai.allure.spring.data.internal.AllureRepositoryAspect;
import io.github.kolomyychenkoai.allure.spring.internal.AllureInstrumentationLogger;
import io.github.kolomyychenkoai.allure.spring.support.jdbc.FakeDataSource;
import io.github.kolomyychenkoai.allure.spring.support.jdbc.FinalDataSource;
import io.github.kolomyychenkoai.allure.spring.support.jdbc.FinalMethodDataSource;
import net.ttddyy.dsproxy.support.ProxyDataSource;
import net.ttddyy.dsproxy.support.ProxyDataSourceBuilder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.AopProxyUtils;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Уровень A: авто-конфиги БД и обёртка бина {@code DataSource}.
 * <p>
 * Главное, что здесь стережётся, — обёртка не имеет права ни менять класс бина (issue #54:
 * инъекция по конкретному типу переставала собираться, и контекст потребителя не поднимался
 * вовсе), ни ронять контекст, когда прокси построить нельзя.
 */
@Epic("Внутренние проверки библиотеки")
class AllureDataAutoConfigurationTest {

    private static Object wrap(Object bean) {
        BeanPostProcessor bpp = AllureDataSourceAutoConfiguration.allureDataSourceProxyPostProcessor();
        return bpp.postProcessAfterInitialization(bean, "ds");
    }

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
    @DisplayName("без datasource-proxy на classpath: BeanPostProcessor НЕ регистрируется")
    void dataSourceProcessorAbsentWithoutProxyDataSourceBuilder() {
        // потребитель без datasource-proxy не должен получить бин (иначе NoClassDefFoundError).
        // Мутация: убери @ConditionalOnClass(ProxyDataSourceBuilder) → бин появится даже без класса → RED.
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(AllureDataSourceAutoConfiguration.class))
                .withClassLoader(new FilteredClassLoader(ProxyDataSourceBuilder.class))
                .run(ctx -> assertThat(ctx).doesNotHaveBean("allureDataSourceProxyPostProcessor"));
    }

    @Test
    @DisplayName("без spring-aop на classpath: BeanPostProcessor НЕ регистрируется")
    void dataSourceProcessorAbsentWithoutSpringAop() {
        // Прокси строится ProxyFactory: без spring-aop бин упал бы на NoClassDefFoundError.
        // Мутация: убери ProxyFactory.class из @ConditionalOnClass → бин появится → RED.
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(AllureDataSourceAutoConfiguration.class))
                .withClassLoader(new FilteredClassLoader(ProxyFactory.class))
                .run(ctx -> assertThat(ctx).doesNotHaveBean("allureDataSourceProxyPostProcessor"));
    }

    @Test
    @DisplayName("без Spring Data Repository на classpath: JPA-аспект НЕ регистрируется")
    void repositoryAspectAbsentWithoutRepositoryClass() {
        // @ConditionalOnClass(name = {ProceedingJoinPoint, Repository}) — гасим один из имён.
        // Мутация: убери условие → AllureRepositoryAspect появится без Repository на classpath → RED.
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(AllureDataJpaAutoConfiguration.class))
                .withClassLoader(new FilteredClassLoader(org.springframework.data.repository.Repository.class))
                .run(ctx -> assertThat(ctx).doesNotHaveBean(AllureRepositoryAspect.class));
    }

    @Test
    @DisplayName("обёрнутый DataSource остаётся объектом своего класса")
    void keepsConcreteType() {
        // Сердцевина issue #54. Мутация: вернуть ProxyDataSourceBuilder.create(ds).build() → RED.
        FakeDataSource original = new FakeDataSource("основной");

        Object wrapped = wrap(original);

        assertThat(wrapped).isInstanceOf(FakeDataSource.class).isNotSameAs(original);
    }

    @Test
    @DisplayName("getConnection() уходит в datasource-proxy — иначе SQL в отчёт не попадёт")
    void routesGetConnectionThroughDataSourceProxy() throws Exception {
        // Мутация: убрать advisor → вернётся то же соединение, что у пула → RED.
        FakeDataSource original = new FakeDataSource("основной");

        DataSource wrapped = (DataSource) wrap(original);

        assertThat(wrapped.getConnection()).isNotSameAs(original.rawConnection());
    }

    @Test
    @DisplayName("getConnection(логин, пароль) тоже уходит в datasource-proxy")
    void routesCredentialGetConnection() throws Exception {
        // Мутация: сузить pointcut до безаргументной перегрузки → RED.
        FakeDataSource original = new FakeDataSource("основной");

        DataSource wrapped = (DataSource) wrap(original);

        assertThat(wrapped.getConnection("u", "p")).isNotSameAs(original.rawConnection());
    }

    @Test
    @DisplayName("остальные вызовы попадают в настоящий пул, а не в пустой подкласс")
    void otherCallsReachTheRealObject() {
        // Подкласс создаётся без конструктора, его поля пусты. Если вызов останется на нём,
        // name() вернёт null. Мутация: направить все методы в прокси-обёртку → RED.
        FakeDataSource original = new FakeDataSource("основной");

        FakeDataSource wrapped = (FakeDataSource) wrap(original);

        assertThat(wrapped.name()).isEqualTo("основной");
    }

    @Test
    @DisplayName("настоящий пул достаётся из прокси — Spring Boot ищет его так же")
    void realDataSourceStaysReachable() {
        // Так работает DataSourceUnwrapper: метрики и health пула Hikari смотрят сквозь
        // AOP-прокси через getSingletonTarget. Мутация: factory.setOpaque(true) → RED.
        FakeDataSource original = new FakeDataSource("основной");

        Object wrapped = wrap(original);

        assertThat(AopProxyUtils.getSingletonTarget(wrapped)).isSameAs(original);
    }

    @Test
    @DisplayName("свой прокси не оборачивается второй раз — и молча, без предупреждения")
    void doesNotDoubleWrapOwnProxy() {
        // ⚠️ Одного isSameAs тут МАЛО, и это выяснилось мутацией. Сгенерированный Spring класс
        // прокси объявляет свои методы final, поэтому без маркера второй заход упёрся бы
        // в предпроверку final-метода и вернул бы ТОТ ЖЕ объект — тест остался бы зелёным,
        // проверяя не тот механизм. Различает пути только предупреждение в логе.
        // Мутация: убрать маркер AllureProxiedDataSource → появится предупреждение → RED.
        Object once = wrap(new FakeDataSource("основной"));
        List<LogRecord> warnings = new ArrayList<>();
        Handler collector = new Handler() {
            @Override
            public void publish(LogRecord record) {
                warnings.add(record);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        Logger logger = AllureInstrumentationLogger.logger();

        Object twice;
        logger.addHandler(collector);
        try {
            twice = wrap(once);
        } finally {
            logger.removeHandler(collector);
        }

        assertThat(twice).isSameAs(once);
        assertThat(warnings)
                .as("обёртка пошла деградацией вместо короткого пути по маркеру: тот же объект, "
                        + "но лишний шум в логе на каждый бин")
                .isEmpty();
    }

    @Test
    @DisplayName("чужой ProxyDataSource не оборачивается второй раз (тот же объект)")
    void doesNotWrapProxyDataSource() {
        ProxyDataSource already = ProxyDataSourceBuilder.create(new FakeDataSource("основной")).build();

        assertThat(wrap(already)).isSameAs(already);
    }

    @Test
    @DisplayName("не-DataSource бин возвращается как есть")
    void leavesNonDataSourceUntouched() {
        Object bean = "не датасорс";

        assertThat(wrap(bean)).isSameAs(bean);
    }

    @Test
    @DisplayName("final-класс: отдаём исходный бин, а не падаем")
    void finalClassDegradesToOriginal() {
        // Подкласс завести нельзя. Мутация: убрать catch (Throwable) → AopConfigException → RED.
        FinalDataSource original = new FinalDataSource();

        assertThat(wrap(original)).isSameAs(original);
    }

    @Test
    @DisplayName("класс с final-методом: отдаём исходный бин, чтобы метод не соврал")
    void finalMethodDegradesToOriginal() {
        // Перехватить такой метод нельзя, а на пустом подклассе он вернул бы null.
        // Мутация: убрать предпроверку finalMethod → вернётся прокси → RED.
        FinalMethodDataSource original = new FinalMethodDataSource();

        assertThat(wrap(original)).isSameAs(original);
    }

    @Test
    @DisplayName("контекст в форме потребителя из #54 поднимается: инъекция по HikariDataSource")
    void consumerContextWithConcreteTypeInjectionStarts() {
        // Точная форма из issue #54 (ShedLock просит пул по КОНКРЕТНОМУ типу). Мутация: вернуть
        // подмену на ProxyDataSource → BeanNotOfRequiredTypeException, контекст не поднялся → RED.
        // Пул не стартует: соединения никто не спрашивает.
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(AllureDataSourceAutoConfiguration.class))
                .withUserConfiguration(ShedLockShapedConfig.class)
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx).getBean("lockedDataSource", HikariDataSource.class).isNotNull();
                });
    }

    /** Конфигурация в форме потребителя: бин пула объявлен и запрошен по классу, не по интерфейсу. */
    @Configuration(proxyBeanMethods = false)
    static class ShedLockShapedConfig {

        @Bean
        HikariDataSource lockedDataSource() {
            HikariDataSource ds = new HikariDataSource();
            ds.setJdbcUrl("jdbc:h2:mem:issue54");
            return ds;
        }

        @Bean
        String lockProvider(@Qualifier("lockedDataSource") HikariDataSource dataSource) {
            return dataSource.getJdbcUrl();
        }
    }
}
