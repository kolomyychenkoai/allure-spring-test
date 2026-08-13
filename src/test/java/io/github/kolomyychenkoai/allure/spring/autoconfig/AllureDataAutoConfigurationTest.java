package io.github.kolomyychenkoai.allure.spring.autoconfig;

import com.zaxxer.hikari.HikariDataSource;
import io.qameta.allure.Epic;
import io.github.kolomyychenkoai.allure.spring.data.AllureDataSourceAutoConfiguration;
import io.github.kolomyychenkoai.allure.spring.data.AllureDataJpaAutoConfiguration;

import io.github.kolomyychenkoai.allure.spring.data.internal.AllureDataSourceProxies.AllureProxiedDataSource;
import io.github.kolomyychenkoai.allure.spring.data.internal.AllureRepositoryAspect;
import io.github.kolomyychenkoai.allure.spring.internal.AllureInstrumentationLogger;
import io.github.kolomyychenkoai.allure.spring.support.InMemoryAllure;
import io.github.kolomyychenkoai.allure.spring.support.jdbc.ExtraOverloadDataSource;
import io.github.kolomyychenkoai.allure.spring.support.jdbc.FakeDataSource;
import io.github.kolomyychenkoai.allure.spring.support.jdbc.FinalDataSource;
import io.github.kolomyychenkoai.allure.spring.support.jdbc.FinalMethodDataSource;
import io.github.kolomyychenkoai.allure.spring.support.jdbc.HiddenFinalMethodDataSource;
import io.github.kolomyychenkoai.allure.spring.support.jdbc.OpaqueDelegatingDataSource;
import io.github.kolomyychenkoai.allure.spring.support.jdbc.SealedDataSource;
import io.qameta.allure.model.StepResult;
import io.qameta.allure.model.TestResult;
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
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.jdbc.datasource.LazyConnectionDataSourceProxy;
import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;

import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Уровень A: авто-конфиги БД и обёртка бина {@code DataSource}.
 * <p>
 * Главное, что здесь стережётся, — обёртка не имеет права ни менять класс бина (issue #54:
 * инъекция по конкретному типу переставала собираться, и контекст потребителя не поднимался
 * вовсе), ни ронять чужой вызов, ни задваивать SQL в отчёте. Когда прокси построить нельзя,
 * бин возвращается нетронутым, а причина уходит в лог одной строкой.
 */
@Epic("Внутренние проверки библиотеки")
class AllureDataAutoConfigurationTest {

    private static Object wrap(Object bean) {
        BeanPostProcessor bpp = AllureDataSourceAutoConfiguration.allureDataSourceProxyPostProcessor();
        return bpp.postProcessAfterInitialization(bean, "ds");
    }

    /**
     * Что библиотека сказала в лог, пока шло действие. Деградация видна снаружи ТОЛЬКО этой
     * строкой, поэтому её проверяем наравне с возвращённым объектом.
     */
    private static List<LogRecord> logWhile(Runnable action) {
        List<LogRecord> records = new ArrayList<>();
        Handler collector = new Handler() {
            @Override
            public void publish(LogRecord record) {
                records.add(record);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        Logger logger = AllureInstrumentationLogger.logger();
        logger.addHandler(collector);
        try {
            action.run();
        } finally {
            logger.removeHandler(collector);
        }
        return records;
    }

    /** Шаги отчёта, которые дал SQL-листенер: по ним и видно задвоение. */
    private static List<String> sqlSteps(TestResult recorded) {
        return recorded.getSteps().stream()
                .map(StepResult::getName)
                .filter(name -> name.startsWith("SQL "))
                .toList();
    }

    /** Один запрос через переданный пул. Проверяем ЧИСЛО шагов, содержимое не важно. */
    private static void select(DataSource dataSource) {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("select 1");
        } catch (Exception broken) {
            throw new IllegalStateException(broken);
        }
    }

    private static HikariDataSource h2Pool(String database) {
        HikariDataSource pool = new HikariDataSource();
        pool.setJdbcUrl("jdbc:h2:mem:" + database);
        return pool;
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
    @DisplayName("через обёртку идёт настоящий SQL: в отчёте появляется шаг SQL")
    void realSqlReachesTheReport() throws Exception {
        // Связывает обёртку с листенером. Без него «соединение другое» ещё не значит, что
        // канал SQL жив. Мутация: убрать .listener(new AllureDataSourceListener()) → RED.
        HikariDataSource pool = h2Pool("wrapguard");
        DataSource wrapped = (DataSource) wrap(pool);
        InMemoryAllure allure = new InMemoryAllure().install();
        try {
            TestResult recorded = allure.run("выборка", () -> select(wrapped));

            assertThat(sqlSteps(recorded))
                    .as("реальный SQL не доехал до отчёта — значит обёртка есть, а канала нет")
                    .isNotEmpty();
        } finally {
            allure.uninstall();
            pool.close();
        }
    }

    @Test
    @DisplayName("чужая перегрузка getConnection уходит в пул, а не падает")
    void alienOverloadReachesThePool() throws Exception {
        // Pointcut отбирает методы ПО ИМЕНИ, поэтому сюда доезжают перегрузки формы Oracle UCP.
        // Мутация: разбирать аргументы позиционно (args[0]/args[1] как String) →
        // ClassCastException прямо в коде потребителя → RED.
        ExtraOverloadDataSource original = new ExtraOverloadDataSource("основной");

        ExtraOverloadDataSource wrapped = (ExtraOverloadDataSource) wrap(original);

        assertThat(wrapped.getConnection(new Properties()))
                .as("перегрузка с метками соединения обязана дойти до пула нетронутой")
                .isSameAs(original.rawConnection());
        assertThat(wrapped.getConnection("u", "p", new Properties()))
                .isSameAs(original.rawConnection());
    }

    @Test
    @DisplayName("getConnection() уходит в datasource-proxy — иначе SQL в отчёт не попадёт")
    void routesGetConnectionThroughDataSourceProxy() throws Exception {
        // Мутация: убрать advisor → вернётся то же соединение, что у пула → RED.
        // ⚠️ Сравниваем ссылки булевым выражением, а не isNotSameAs(соединение): ассерт по
        // объекту тащит в ИМЯ ШАГА toString обёртки datasource-proxy — «$Proxy243», где номер
        // плавает от прогона к прогону. Своё же правило гигиены имён это запрещает.
        FakeDataSource original = new FakeDataSource("основной");

        DataSource wrapped = (DataSource) wrap(original);

        assertThat(wrapped.getConnection() == original.rawConnection())
                .as("соединение отдано мимо datasource-proxy — SQL этого пула в отчёт не попадёт")
                .isFalse();
    }

    @Test
    @DisplayName("getConnection(логин, пароль) тоже уходит в datasource-proxy")
    void routesCredentialGetConnection() throws Exception {
        // Мутация: сузить отбор до безаргументной перегрузки → RED.
        FakeDataSource original = new FakeDataSource("основной");

        DataSource wrapped = (DataSource) wrap(original);

        assertThat(wrapped.getConnection("u", "p") == original.rawConnection())
                .as("перегрузка с логином отдана мимо datasource-proxy")
                .isFalse();
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
        // проверяя не тот механизм. Различает пути только строка в логе.
        // Мутация: убрать маркер AllureProxiedDataSource → появится строка → RED.
        Object once = wrap(new FakeDataSource("основной"));
        List<Object> twice = new ArrayList<>();

        List<LogRecord> said = logWhile(() -> twice.add(wrap(once)));

        assertThat(twice).containsExactly(once);
        assertThat(said)
                .as("обёртка пошла деградацией вместо короткого пути по маркеру: тот же объект, "
                        + "но лишний шум в логе на каждый бин")
                .isEmpty();
    }

    @Test
    @DisplayName("цепочка из трёх бинов Spring пишет запрос ОДИН раз")
    void chainOfSpringBeansLogsQueryOnce() throws Exception {
        // Мультиарендная раскладка из документации Spring: lazy → routing → пул. Обёрнуты все
        // три, дубли гасит счётчик глубины. Классы берём НАСТОЯЩИЕ: самодельная фикстура
        // подтверждала бы сама себя, а тут проверяется наша совместимость со Spring.
        // Мутация: убрать гард HANDING_OUT → шагов станет больше одного → RED.
        HikariDataSource pool = h2Pool("chain");
        DataSource wrappedPool = (DataSource) wrap(pool);
        TenantRouting routing = new TenantRouting();
        routing.setDefaultTargetDataSource(wrappedPool);
        routing.setTargetDataSources(Map.of("тенант", wrappedPool));
        routing.afterPropertiesSet();
        DataSource wrappedRouting = (DataSource) wrap(routing);
        DataSource wrappedLazy = (DataSource) wrap(new LazyConnectionDataSourceProxy(wrappedRouting));

        // Считает самый внутренний слой, внешние пропускаются: у них внутри уже наш прокси.
        assertThat(wrappedPool).isInstanceOf(AllureProxiedDataSource.class);
        assertThat(wrappedRouting).isNotInstanceOf(AllureProxiedDataSource.class);
        assertThat(wrappedLazy).isNotInstanceOf(AllureProxiedDataSource.class);

        InMemoryAllure allure = new InMemoryAllure().install();
        try {
            TestResult recorded = allure.run("выборка через цепочку", () -> select(wrappedLazy));

            assertThat(sqlSteps(recorded))
                    .as("запрос попал в отчёт столько раз, сколько слоёв обёртки — ручник "
                            + "прочитает это как лишние обращения к базе")
                    .hasSize(1);
        } finally {
            allure.uninstall();
            pool.close();
        }
    }

    @Test
    @DisplayName("два разных пула в одном тесте пишут по своему шагу — гард не глушит лишнего")
    void independentPoolsBothLog() throws Exception {
        // Обратная сторона гарда. Мутация: не снимать HANDING_OUT в finally → второй пул
        // промолчит → RED.
        HikariDataSource first = h2Pool("guard-one");
        HikariDataSource second = h2Pool("guard-two");
        DataSource wrappedFirst = (DataSource) wrap(first);
        DataSource wrappedSecond = (DataSource) wrap(second);

        InMemoryAllure allure = new InMemoryAllure().install();
        try {
            TestResult recorded = allure.run("две выборки", () -> {
                select(wrappedFirst);
                select(wrappedSecond);
            });

            assertThat(sqlSteps(recorded)).hasSize(2);
        } finally {
            allure.uninstall();
            first.close();
            second.close();
        }
    }

    @Test
    @DisplayName("приватный класс обёртки не выключает защиту от задвоения")
    void privateWrapperClassIsStillRecognised() {
        // getMethod у НЕпубличного класса даёт метод, а invoke на нём бросает
        // IllegalAccessException — защита молча выключалась бы, и SQL задвоился. Форма живая:
        // private static class TenantRouter extends AbstractRoutingDataSource.
        // Мутация: искать акцессор на target.getClass(), а не на публичном предке → RED.
        DataSource inner = (DataSource) wrap(new FakeDataSource("внутренний"));

        assertThat(wrap(new PrivateDelegating(inner))).isInstanceOf(PrivateDelegating.class)
                .isNotInstanceOf(AllureProxiedDataSource.class);
    }

    /** Обёртка, объявленная приватным классом: акцессор виден только через публичного предка. */
    private static class PrivateDelegating extends DelegatingDataSource {
        PrivateDelegating(DataSource target) {
            super(target);
        }

        @Override
        public DataSource getTargetDataSource() {
            return super.getTargetDataSource();
        }
    }

    @Test
    @DisplayName("декоратор с неизвестным акцессором тоже пишет запрос один раз")
    void opaqueDecoratorChainLogsQueryOnce() throws Exception {
        // Структурная проверка тут слепа: цель зовётся getDelegate. Работает счётчик глубины.
        // Мутация: убрать проверку HANDING_OUT в connection(...) → два шага на запрос → RED.
        HikariDataSource pool = h2Pool("opaque");
        DataSource wrappedPool = (DataSource) wrap(pool);
        DataSource wrappedDecorator = (DataSource) wrap(new OpaqueDelegatingDataSource(wrappedPool));

        assertThat(wrappedDecorator)
                .as("декоратор с чужим акцессором обёрнут — иначе тест проверял бы не тот механизм")
                .isInstanceOf(AllureProxiedDataSource.class);

        InMemoryAllure allure = new InMemoryAllure().install();
        try {
            TestResult recorded = allure.run("выборка через декоратор", () -> select(wrappedDecorator));

            assertThat(sqlSteps(recorded)).hasSize(1);
        } finally {
            allure.uninstall();
            pool.close();
        }
    }

    @Test
    @DisplayName("сбой построения прокси гасится: отдаём бин и пишем предупреждение со стеком")
    void proxyBuildFailureDegradesToOriginal() {
        // Единственная фикстура, доезжающая до ProxyFactory: класс запечатан, и подкласс
        // отбрасывает JVM. Все прочие негодные пулы отсекает предпроверка раньше, поэтому
        // без этого теста сеть безопасности снималась при зелёной сборке (проверено мутацией).
        // Мутация: в catch (Throwable) бросить вместо возврата бина → RED.
        SealedDataSource original = new SealedDataSource();
        List<Object> result = new ArrayList<>();

        List<LogRecord> said = logWhile(() -> result.add(wrap(original)));

        assertThat(result).containsExactly(original);
        assertThat(said).singleElement()
                .satisfies(record -> assertThat(record.getThrown())
                        .as("сбой построения — не штатная ступень: он обязан прийти со стеком")
                        .isNotNull());
    }

    /** Роутер тенантов: именованный класс, потому что анонимный неявно final и не проксируется. */
    static class TenantRouting extends AbstractRoutingDataSource {
        @Override
        protected Object determineCurrentLookupKey() {
            return "тенант";
        }
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
    @DisplayName("бин-JDK-прокси оборачивается по интерфейсам, а не отбрасывается")
    void jdkProxyIsWrappedByInterfaces() throws Exception {
        // Так устроен DataSource встроенной базы (EmbeddedDatabaseBuilder) и чужие декораторы
        // по интерфейсу. У JDK-прокси ВСЕ методы final, поэтому предпроверка отбросила бы его
        // и SQL пропал бы молча. Мутация: убрать ветку byInterfaces → RED.
        FakeDataSource real = new FakeDataSource("встроенная");
        DataSource jdkProxy = (DataSource) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{DataSource.class},
                (proxy, method, args) -> method.invoke(real, args));

        Object wrapped = wrap(jdkProxy);

        assertThat(wrapped).isInstanceOf(AllureProxiedDataSource.class);
        assertThat(((DataSource) wrapped).getConnection() == real.rawConnection())
                .as("по ветке для JDK-прокси соединение отдаётся мимо datasource-proxy — "
                        + "маркер есть, а SQL такого пула в отчёт не попадает")
                .isFalse();
    }

    @Test
    @DisplayName("final-класс: отдаём исходный бин и говорим почему")
    void finalClassDegradesToOriginal() {
        // Подкласс завести нельзя. Мутация: убрать ветку final-класса в cannotSubclass → уйдём
        // в catch (Throwable), и вместо строки причины появится стек «сбой» → RED.
        FinalDataSource original = new FinalDataSource();
        List<Object> result = new ArrayList<>();

        List<LogRecord> said = logWhile(() -> result.add(wrap(original)));

        assertThat(result).containsExactly(original);
        assertThat(said).as("молчаливая деградация: SQL пропал, и никто не знает почему")
                .singleElement()
                .satisfies(record -> assertThat(record.getMessage()).contains("FinalDataSource"));
    }

    @Test
    @DisplayName("класс с final-методом: отдаём исходный бин и называем метод")
    void finalMethodDegradesToOriginal() {
        // Перехватить такой метод нельзя, а на пустом подклассе он вернул бы null.
        // Мутация: убрать предпроверку finalMethod → вернётся прокси → RED.
        FinalMethodDataSource original = new FinalMethodDataSource();
        List<Object> result = new ArrayList<>();

        List<LogRecord> said = logWhile(() -> result.add(wrap(original)));

        assertThat(result).containsExactly(original);
        assertThat(said).singleElement()
                .satisfies(record -> assertThat(record.getMessage())
                        .as("по строке в логе должно быть понятно, какой пул ослеп и из-за чего")
                        .contains("FinalMethodDataSource")
                        .contains("stamp"));
    }

    @Test
    @DisplayName("непубличный final-метод виден предпроверке так же, как публичный")
    void hiddenFinalMethodDegradesToOriginal() {
        // getMethods() показывает только публичные, а CGLIB не переопределит и пакетный final.
        // Мутация: считать предпроверку через getMethods() вместо обхода иерархии
        // getDeclaredMethods() → пул заведут в подкласс-ловушку → RED.
        HiddenFinalMethodDataSource original = new HiddenFinalMethodDataSource();
        List<Object> result = new ArrayList<>();

        List<LogRecord> said = logWhile(() -> result.add(wrap(original)));

        assertThat(result).containsExactly(original);
        assertThat(said).singleElement()
                .satisfies(record -> assertThat(record.getMessage())
                        .as("причина названа не та: ветка могла сработать по классу, а не по методу")
                        .contains("stamp"));
    }

    @Test
    @DisplayName("бин за чужим Spring AOP: причина названа своей, а не случайным методом")
    void alreadyAopProxiedNamesTheRealReason() {
        // У чужого CGLIB-прокси final ВСЕ сгенерированные методы, и указание на любой из них
        // отправляет читателя искать несуществующую проблему в JDBC API.
        // Мутация: убрать ветку SpringProxy в cannotSubclass → в тексте окажется имя метода → RED.
        ProxyFactory foreign = new ProxyFactory(new FakeDataSource("чужой"));
        foreign.setProxyTargetClass(true);
        Object aopProxied = foreign.getProxy();
        List<Object> result = new ArrayList<>();

        List<LogRecord> said = logWhile(() -> result.add(wrap(aopProxied)));

        assertThat(result).containsExactly(aopProxied);
        assertThat(said).singleElement()
                .satisfies(record -> assertThat(record.getMessage()).contains("чужой Spring AOP"));
    }

    @Test
    @DisplayName("контекст в форме потребителя из #54 поднимается, и пул реально обёрнут")
    void consumerContextWithConcreteTypeInjectionStarts() {
        // Точная форма из issue #54 (ShedLock просит пул по КОНКРЕТНОМУ типу). Мутация: вернуть
        // подмену на ProxyDataSource → BeanNotOfRequiredTypeException, контекст не поднялся → RED.
        // Проверка на наш маркер обязательна: без неё тест зелёный и когда пул ушёл в деградацию.
        // Пул не стартует: соединения никто не спрашивает.
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(AllureDataSourceAutoConfiguration.class))
                .withUserConfiguration(ShedLockShapedConfig.class)
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx).getBean("lockedDataSource", HikariDataSource.class).isNotNull();
                    assertThat(ctx.getBean("lockedDataSource")).isInstanceOf(AllureProxiedDataSource.class);
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
