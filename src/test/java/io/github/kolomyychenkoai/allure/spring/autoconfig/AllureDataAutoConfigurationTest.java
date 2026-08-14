package io.github.kolomyychenkoai.allure.spring.autoconfig;

import com.zaxxer.hikari.HikariDataSource;
import io.qameta.allure.Epic;
import io.github.kolomyychenkoai.allure.spring.data.AllureDataSourceAutoConfiguration;
import io.github.kolomyychenkoai.allure.spring.data.AllureDataJpaAutoConfiguration;

import io.github.kolomyychenkoai.allure.spring.data.internal.AllureDataSourceProxies.AllureProxiedDataSource;
import io.github.kolomyychenkoai.allure.spring.data.internal.AllureRepositoryAspect;
import io.github.kolomyychenkoai.allure.spring.internal.AllureInstrumentationLogger;
import io.github.kolomyychenkoai.allure.spring.support.InMemoryAllure;
import io.github.kolomyychenkoai.allure.spring.support.jdbc.AwkwardAccessorDataSource;
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
import org.springframework.aop.aspectj.annotation.AnnotationAwareAspectJAutoProxyCreator;
import org.springframework.aop.config.AopConfigUtils;
import org.springframework.aop.framework.AopProxyUtils;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.aop.framework.autoproxy.InfrastructureAdvisorAutoProxyCreator;
import org.springframework.aop.support.AopUtils;
import io.github.kolomyychenkoai.allure.spring.internal.ActivationDiagnostics;
import org.springframework.boot.LazyInitializationBeanFactoryPostProcessor;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.interceptor.TransactionalProxy;

import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.jdbc.datasource.LazyConnectionDataSourceProxy;
import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;

import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.logging.Handler;
import java.util.logging.Level;
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
        query(dataSource, "select 1");
    }

    /** Произвольный запрос: нужен там, где шаги должны РАЗЛИЧАТЬСЯ по имени. */
    private static void query(DataSource dataSource, String sql) {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
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
    @DisplayName("JPA-аспект: бин есть, когда авто-проксирование включено (умолчание Boot)")
    void repositoryAspectPresentByDefault() {
        // AopAutoConfiguration подаём явно: свой @EnableAspectJAutoProxy мы больше не вешаем,
        // и без создателя прокси регистрировать аспект незачем — он был бы мёртвым бином.
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        AopAutoConfiguration.class, AllureDataJpaAutoConfiguration.class))
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
        // (условие на внешнем классе — гасим один из трёх названных типов)
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

        Properties labels = new Properties();
        labels.setProperty("tenant", "первый");

        assertThat(wrapped.getConnection(labels))
                .as("перегрузка с метками соединения обязана дойти до пула нетронутой")
                .isSameAs(original.rawConnection());
        assertThat(original.lastLabels())
                .as("метки соединения потерялись по дороге — пул отдаст не то соединение")
                .isSameAs(labels);

        Properties other = new Properties();
        other.setProperty("tenant", "второй");
        assertThat(wrapped.getConnection("u", "p", other)).isSameAs(original.rawConnection());
        assertThat(original.lastLabels())
                .as("третий аргумент выброшен молча — ровно то, чем опасен позиционный разбор")
                .isSameAs(other);
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
        assertThat(original.lastUsername())
                .as("аргументы дошли до пула переставленными — соединение возьмут не под тем пользователем")
                .isEqualTo("u");
        assertThat(original.lastPassword()).isEqualTo("p");
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
        // Мультиарендная раскладка из документации Spring: lazy → routing → пул. Обёрнут
        // только пул, внешние два пропускает структурная проверка — она и стережётся здесь.
        // Классы берём НАСТОЯЩИЕ: самодельная фикстура подтверждала бы сама себя, а тут
        // проверяется наша совместимость со Spring.
        // Мутация: убрать структурную проверку wrapsOurProxy → внешние бины тоже обернутся,
        // и шагов станет больше одного → RED. (Гард HANDING_OUT здесь ни при чём: нашим
        // прокси является только пул, вложенного перехвата в цепочке нет — стережёт гард
        // соседний тест про непрозрачный декоратор.)
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
        PrivateDelegating outer = new PrivateDelegating(inner);
        List<Object> result = new ArrayList<>();

        List<LogRecord> said = logWhile(() -> result.add(wrap(outer)));

        assertThat(result).containsExactly(outer);
        assertThat(said).as("это короткий путь по нашему маркеру, а не деградация").isEmpty();
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

        @Override
        public String toString() {
            return "приватная обёртка";
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
                .satisfies(record -> {
                    assertThat(record.getThrown())
                            .as("сбой построения — не штатная ступень: он обязан прийти со стеком")
                            .isNotNull();
                    assertThat(record.getMessage())
                            .as("без слова про инструментирование строка неотличима от чужого шума")
                            .contains("DbDataSource");
                });
    }

    /** Роутер тенантов: именованный класс, потому что анонимный неявно final и не проксируется. */
    static class TenantRouting extends AbstractRoutingDataSource {
        @Override
        protected Object determineCurrentLookupKey() {
            return "тенант";
        }

        @Override
        public String toString() {
            return "роутер тенантов";
        }
    }

    @Test
    @DisplayName("цепочка над СЫРЫМ пулом оборачивается — иначе SQL пропадёт целиком")
    void chainOverRawPoolIsWrapped() {
        // Обратная сторона структурной проверки, и без неё она бесконтрольна: пул, созданный
        // не бином (`@Bean DataSource ds() { return new LazyConnectionDataSourceProxy(new
        // HikariDataSource(...)); }`), через постпроцессор не проходит, и обернуть внешний бин —
        // единственный шанс на SQL. Мутация: гасить ЛЮБУЮ известную цепочку, а не только свою
        // (`target instanceof DataSource && target != node`) → RED.
        HikariDataSource raw = h2Pool("raw-chain");
        DataSource wrapped = (DataSource) wrap(new LazyConnectionDataSourceProxy(raw));

        assertThat(wrapped).isInstanceOf(AllureProxiedDataSource.class);

        // Маркера мало: ленивый прокси спрашивает цель ОТЛОЖЕННО, и именно через эту
        // отложенность в обёртке живут два механизма. Считаем шаги, а не признак.
        InMemoryAllure allure = new InMemoryAllure().install();
        try {
            TestResult recorded = allure.run("выборка через ленивый прокси", () -> select(wrapped));

            assertThat(sqlSteps(recorded)).hasSize(1);
        } finally {
            allure.uninstall();
            raw.close();
        }
    }

    @Test
    @DisplayName("акцессор из публичного интерфейса находится у непубличной обёртки")
    void interfaceDeclaredAccessorIsFound() {
        // Непубличный класс потребителя, реализующий публичный интерфейс: объявления нет
        // ни в одном классе иерархии, и без обхода интерфейсов вызов падает
        // IllegalAccessException, защита молча выключается, запрос идёт в отчёт дважды.
        // Мутация: убрать интерфейсы из declarationCandidates → RED.
        // Якорь: сама обёртка обязана быть проксируемой, иначе «вернули как есть» ничего
        // не доказывает — непроксируемый класс возвращается как есть по другой причине.
        assertThat(wrap(AwkwardAccessorDataSource.hiddenWrapperOver(new FakeDataSource("сырой"))))
                .as("фикстура не проксируется — тест проверял бы не тот механизм")
                .isInstanceOf(AllureProxiedDataSource.class);

        DataSource inner = (DataSource) wrap(new FakeDataSource("внутренний"));
        DataSource outer = AwkwardAccessorDataSource.hiddenWrapperOver(inner);

        assertThat(wrap(outer)).isSameAs(outer);
    }

    @Test
    @DisplayName("null в карте целей не роняет обход")
    void nullInTargetsMapIsSkipped() {
        // Карта чужая: у самописного роутера в ней бывает null («тенант ещё не резолвился»).
        // Наш же NullPointerException уехал бы потребителю как «сбой инструментирования»,
        // а пул остался бы без обёртки. Мутация: убрать filter(Objects::nonNull) → RED.
        DataSource inner = (DataSource) wrap(new FakeDataSource("внутренний"));
        AwkwardAccessorDataSource.NullInTargetsMap routing =
                new AwkwardAccessorDataSource.NullInTargetsMap("роутер с дырой", inner);
        List<Object> result = new ArrayList<>();

        List<LogRecord> said = logWhile(() -> result.add(wrap(routing)));

        assertThat(result).containsExactly(routing);
        assertThat(said).as("свой NPE предъявлен потребителю как сбой инструментирования").isEmpty();
    }

    @Test
    @DisplayName("бросок из пула не оставляет счётчик глубины взведённым")
    void guardIsClearedAfterFailedIssue() {
        // Протечка ThreadLocal выключила бы SQL этого потока до конца прогона, и это самый
        // вероятный путь протечки — исключение, а не успешный вызов.
        // Мутация: не снимать HANDING_OUT в finally → следующий пул промолчит → RED.
        HikariDataSource broken = h2Pool("guard-broken");
        broken.setJdbcUrl("jdbc:h2:mem:guard-broken;INIT=RUNSCRIPT FROM 'нет такого файла'");
        DataSource wrappedBroken = (DataSource) wrap(broken);
        HikariDataSource healthy = h2Pool("guard-healthy");
        DataSource wrappedHealthy = (DataSource) wrap(healthy);

        List<Boolean> threw = new ArrayList<>();
        InMemoryAllure allure = new InMemoryAllure().install();
        try {
            TestResult recorded = allure.run("сбой, затем обычный запрос", () -> {
                try {
                    wrappedBroken.getConnection();
                    threw.add(false);
                } catch (Exception expected) {
                    threw.add(true);
                }
                select(wrappedHealthy);
            });

            // ⚠️ Без этой строки тест зелен по слабому основанию: не бросив, первый пул просто
            // не создаёт шагов, и «ровно один шаг» сошлось бы само по себе.
            assertThat(threw).as("сломанный пул не бросил — проверять нечего").containsExactly(true);
            assertThat(sqlSteps(recorded))
                    .as("после броска счётчик остался взведённым — SQL этого потока пропал")
                    .hasSize(1);
        } finally {
            allure.uninstall();
            broken.close();
            healthy.close();
        }
    }

    @Test
    @DisplayName("вложенная выдача соседнего пула глушится — известная цена счётчика")
    void nestedIssueOfAnotherPoolIsSuppressed() {
        // ⚠️ Тест прибивает ИЗВЕСТНОЕ ОГРАНИЧЕНИЕ, а не желаемое поведение. Флаг один на поток,
        // поэтому мультиарендный резолвер, спрашивающий тенанта у отдельного пула внутри своего
        // getConnection, теряет в отчёте шаг этого запроса. Ключ по самому пулу не помогает:
        // в цепочке чужих декораторов объекты как раз РАЗНЫЕ. Уедет поведение — узнаем здесь,
        // а не от потребителя.
        HikariDataSource metadata = h2Pool("nested-meta");
        DataSource wrappedMetadata = (DataSource) wrap(metadata);
        HikariDataSource main = h2Pool("nested-main");
        DataSource wrappedMain = (DataSource) wrap(new ResolvingDataSource(main, wrappedMetadata));

        InMemoryAllure allure = new InMemoryAllure().install();
        try {
            TestResult recorded = allure.run("выдача с резолвом тенанта", () -> select(wrappedMain));

            assertThat(sqlSteps(recorded))
                    .as("уцелеть обязан шаг ГЛАВНОГО пула: вложенный резолв глушится счётчиком, "
                            + "и различить два исхода можно только по имени шага")
                    .containsExactly("SQL SELECT");
        } finally {
            allure.uninstall();
            main.close();
            metadata.close();
        }
    }

    /** Резолвер тенанта: внутри своей выдачи спрашивает ОТДЕЛЬНЫЙ метаданный пул. */
    static class ResolvingDataSource extends org.springframework.jdbc.datasource.DelegatingDataSource {

        private final DataSource metadata;

        ResolvingDataSource(DataSource target, DataSource metadata) {
            super(target);
            this.metadata = metadata;
        }

        @Override
        public Connection getConnection() throws java.sql.SQLException {
            // Запрос С ТАБЛИЦЕЙ: имя шага станет «SQL SELECT information_schema.tables»
            // и будет отличаться от голого «SQL SELECT» главного пула. Иначе тест не отличит
            // «пропал вложенный» от «пропал главный» — оба шага звались бы одинаково.
            query(metadata, "select count(*) from information_schema.tables");
            return super.getConnection();
        }
    }

    @Test
    @DisplayName("цель роутера видна и через карту, а не только через default-цель")
    void routingTargetsMapIsWalked() {
        // У AbstractRoutingDataSource цели лежат в КАРТЕ, и без её обхода мы бы увидели только
        // default-цель. Роутер без default-цели — единственный способ дойти до этой ветки.
        // Мутация: убрать разворот Map в targets(...) → роутер обернётся вторым слоем → RED.
        DataSource inner = (DataSource) wrap(new FakeDataSource("внутренний"));
        TenantRouting routing = new TenantRouting();
        routing.setTargetDataSources(Map.of("тенант", inner));
        routing.afterPropertiesSet();
        List<Object> result = new ArrayList<>();

        List<LogRecord> said = logWhile(() -> result.add(wrap(routing)));

        assertThat(result).containsExactly(routing);
        assertThat(said).as("это короткий путь по нашему маркеру, а не деградация").isEmpty();
    }

    @Test
    @DisplayName("акцессор с чужим типом возврата не зовётся вовсе")
    void accessorOfForeignReturnTypeIsNotCalled() {
        // Одноимённый метод у чужого класса вправе значить что угодно, и звать его «вдруг
        // подойдёт» мы не вправе: у потребителя за таким именем может стоять ленивый резолв.
        // ⚠️ Проверяем ФАКТ ВЫЗОВА, а не результат обёртки: строку, которую метод вернул бы,
        // обход дальше игнорирует, и по результату лишний вызов неотличим — выяснено мутацией.
        // Мутация: убрать сверку типа возврата в read(...) → акцессор будет позван → RED.
        AwkwardAccessorDataSource.ForeignReturnType pool =
                new AwkwardAccessorDataSource.ForeignReturnType("чужой акцессор");

        Logger logger = AllureInstrumentationLogger.logger();
        Level previous = logger.getLevel();
        List<Object> wrapped = new ArrayList<>();
        List<LogRecord> said;
        logger.setLevel(Level.FINE);
        try {
            said = logWhile(() -> wrapped.add(wrap(pool)));
        } finally {
            logger.setLevel(previous);
        }

        assertThat(pool.wasCalled())
                .as("позвали чужой метод только потому, что имя совпало")
                .isFalse();
        assertThat(wrapped.get(0)).isInstanceOf(AllureProxiedDataSource.class);
        // Обе ветки «цель не прочитана» обязаны оставлять след, а не только та, что с броском.
        // Мутация: убрать trace у ветки чужого типа возврата → RED.
        assertThat(said).singleElement()
                .satisfies(record -> {
                    assertThat(record.getMessage()).contains("а не пул");
                    // ⚠️ Уровень проверяем ЗДЕСЬ, а не соседним тестом: singleElement уже требует,
                    // чтобы запись была, поэтому обе половины — «след есть» и «он не громче FINE» —
                    // держит один ассерт, без вырожденности пустого списка.
                    // Мутация: заменить trace на note → след станет видимым у каждого потребителя → RED.
                    assertThat(record.getLevel()).isEqualTo(Level.FINE);
                });
    }

    @Test
    @DisplayName("бросок из чужого акцессора не роняет обёртку")
    void throwingAccessorDoesNotBreakWrapping() {
        // Резолв цели у самописного роутера вправе упасть вне контекста тенанта. Мутация:
        // убрать catch (Throwable) в read(...) → исключение уйдёт наружу и обёртка отдаст
        // исходный бин через общий catch → RED.
        AwkwardAccessorDataSource.ThrowingAccessor pool =
                new AwkwardAccessorDataSource.ThrowingAccessor("бросающий акцессор");
        List<Object> result = new ArrayList<>();
        Logger logger = AllureInstrumentationLogger.logger();
        Level previous = logger.getLevel();

        List<LogRecord> said;
        logger.setLevel(Level.FINE);
        try {
            said = logWhile(() -> result.add(wrap(pool)));
        } finally {
            logger.setLevel(previous);
        }

        assertThat(result.get(0)).isInstanceOf(AllureProxiedDataSource.class);
        // ⚠️ Уровень поднимаем САМИ и требуем singleElement: иначе проверка зависит от настроек
        // логирования вокруг. Сперва тест проверял молчание — был зелёным в одиночку и красным
        // в полном прогоне; потом allSatisfy — стал зелёным в полном и пустым в одиночку.
        // Инвариант же наш и простой: запись есть, и она не громче FINE.
        assertThat(said).singleElement()
                .satisfies(record -> assertThat(record.getLevel())
                        .as("след про непрочитанную цель поднялся выше FINE: он для разбора "
                                + "жалобы, а не для каждого старта контекста у потребителя")
                        .isEqualTo(Level.FINE));
        assertThat(pool.accessorCalls())
                .as("бросающий акцессор позван повторно: у чужого ленивого резолва это соединение, "
                        + "метрика отказа или счётчик размыкателя — по разу на каждого кандидата")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("рецепт включения следа из README действительно работает")
    void documentedTraceRecipeWorks() throws Exception {
        // ⚠️ Обещание без теста: рецепт в README уже дважды был неверным. Первый раз он звал
        // поднять уровень только логгеру — на голом JUL запись умирает на обработчике, у него
        // свой уровень INFO. Тест выполняет ровно то, что написано в README, и требует, чтобы
        // след дошёл; заодно проверяет, что обе строки рецепта в тексте остались.
        String readme = Files.readString(Path.of("README.md"), StandardCharsets.UTF_8);
        assertThat(readme)
                .as("из README пропала половина рецепта — та, без которой он молчит")
                .contains("lib.setLevel(Level.FINE)")
                .contains("console.setLevel(Level.FINE)");

        AwkwardAccessorDataSource.ThrowingAccessor pool =
                new AwkwardAccessorDataSource.ThrowingAccessor("бросающий акцессор");
        Logger logger = AllureInstrumentationLogger.logger();
        Level previous = logger.getLevel();
        List<LogRecord> reachedHandler = new ArrayList<>();
        List<LogRecord> filteredOut = new ArrayList<>();

        logger.setLevel(Level.FINE);
        try {
            // Обработчик с уровнем рецепта — след обязан дойти.
            withHandler(Level.FINE, reachedHandler, () -> wrap(pool));
            // Обработчик с уровнем по умолчанию (как у корневого ConsoleHandler) — не дойдёт.
            // Это и есть причина, по которой в рецепте ДВЕ строки, а не одна.
            withHandler(Level.INFO, filteredOut, () -> wrap(pool));
        } finally {
            logger.setLevel(previous);
        }

        assertThat(reachedHandler).as("рецепт из README следа не даёт").hasSize(1);
        assertThat(filteredOut).as("одного уровня логгера хватило — тогда вторая строка "
                + "рецепта лишняя, и README вводит в заблуждение").isEmpty();
    }

    /** Собрать записи обработчиком с заданным уровнем: у обработчика он свой, и это ловушка. */
    private static void withHandler(Level level, List<LogRecord> into, Runnable action) {
        Handler collector = new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (isLoggable(record)) {
                    into.add(record);
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        collector.setLevel(level);
        Logger logger = AllureInstrumentationLogger.logger();
        logger.addHandler(collector);
        try {
            action.run();
        } finally {
            logger.removeHandler(collector);
        }
    }

    @Test
    @DisplayName("под FINE след называет акцессор и класс потребителя")
    void traceNamesAccessorAndConsumerClass() {
        // Обратная сторона теста выше: молчание не должно означать «не пишем вовсе».
        // Мутация: убрать вызов trace(...) → RED; поменять FINE на WARNING → покраснеет соседний.
        AwkwardAccessorDataSource.ThrowingAccessor pool =
                new AwkwardAccessorDataSource.ThrowingAccessor("бросающий акцессор");
        Logger logger = AllureInstrumentationLogger.logger();
        Level previous = logger.getLevel();

        List<LogRecord> said;
        logger.setLevel(Level.FINE);
        try {
            said = logWhile(() -> wrap(pool));
        } finally {
            logger.setLevel(previous);
        }

        assertThat(said).singleElement()
                .satisfies(record -> assertThat(record.getMessage())
                        .as("по следу нельзя понять, какой акцессор у какого пула не прочитался")
                        .contains("getTargetDataSource")
                        .contains("ThrowingAccessor")
                        .doesNotContain("$$"));
    }

    @Test
    @DisplayName("обёртка, ссылающаяся сама на себя, не зацикливает обход")
    void selfReferencingWrapperTerminates() {
        // Предел CHAIN_LIMIT держит и глубину, и цикл. Считаем ВЫЗОВЫ, а не время: таймер
        // сторожил бы только «не бесконечно», и предел мог бы вырасти в тысячи раз, оставаясь
        // в бюджете, — а обход идёт на каждом бине DataSource у потребителя.
        // Мутация: убрать условие visited < CHAIN_LIMIT → обход не завершится → RED.
        AwkwardAccessorDataSource.SelfReferencing pool =
                new AwkwardAccessorDataSource.SelfReferencing("сам на себя");

        assertThat(wrap(pool)).isInstanceOf(AllureProxiedDataSource.class);
        // ⚠️ Число ЛИТЕРАЛОМ, а не из константы: сравнение предела с самим собой — тавтология,
        // обе стороны уехали бы вместе, и рост со восьми до миллиона остался бы зелёным.
        // Нижняя граница нужна тоже: ноль вызовов означает мёртвый обход.
        // ⚠️ @Timeout сюда не ставим: недекларативный прерывает поток (из-за чего и убрали
        // assertTimeoutPreemptively), а декларативный бесконечный цикл не рвёт вовсе. Зависание
        // ловит таймаут мутационного харнесса — там ему и место.
        // ⚠️ Верхняя граница равна пределу только потому, что у фикстуры ОДИН акцессор:
        // добавишь второй — счёт вырастет кратно при том же пределе.
        assertThat(pool.accessorCalls())
                .as("обход спросил цель больше раз, чем разрешает предел (или не спросил вовсе)")
                .isBetween(1, 8);
    }

    @Test
    @DisplayName("чужой ProxyDataSource не оборачивается второй раз (тот же объект)")
    void doesNotWrapProxyDataSource() {
        ProxyDataSource already = ProxyDataSourceBuilder.create(new FakeDataSource("основной")).build();
        List<Object> result = new ArrayList<>();

        List<LogRecord> said = logWhile(() -> result.add(wrap(already)));

        assertThat(result).containsExactly(already);
        assertThat(said).as("чужой ProxyDataSource — не деградация, а короткий путь: лог молчит").isEmpty();
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
        // Так устроены чужие декораторы по интерфейсу и Spring AOP в режиме
        // proxyTargetClass=false. ⚠️ Встроенная база сюда НЕ относится, хоть и напрашивается:
        // EmbeddedDatabaseFactory$EmbeddedDataSourceProxy — обычный класс, проверено javap.
        // У JDK-прокси ВСЕ методы final, поэтому предпроверка отбросила бы такой бин
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
                        .as("по строке в логе должно быть понятно, какой пул ослеп и из-за чего; "
                                + "метод обязан быть наименьшим по имени, иначе он плавает между прогонами")
                        .contains("FinalMethodDataSource")
                        .contains("stamp")
                        .doesNotContain("zzzAnotherFinal"));
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
                        .as("по строке должно быть понятно, какой бин ослеп, какой это класс и из-за чего")
                        .contains("ds")
                        .contains("HiddenFinalMethodDataSource")
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
                .satisfies(record -> assertThat(record.getMessage())
                        .as("в строке должен стоять класс ПОТРЕБИТЕЛЯ: имя вида Пул$$SpringCGLIB$$0 "
                                + "читатель принимает за поломку библиотеки")
                        .contains("чужой Spring AOP")
                        .contains("FakeDataSource")
                        .doesNotContain("$$"));
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

    // ─────────────────────────── AOP потребителя: issues #70 и #71 ───────────────────────────

    /**
     * Имя бина аспекта ЛИТЕРАЛОМ, а не ссылкой на константу продукта: константа в паре с самой
     * собой доказывала бы тождество, а не имя. Переименуют её не подумав — тест покраснеет.
     * Имя публичное по факту: под ним потребитель переопределяет наш бин (issue #73).
     */
    private static final String ASPECT_BEAN_NAME = "allureRepositoryAspect";

    @Test
    @DisplayName("нет AspectJ-создателя прокси: аспекта нет, чужой создатель не подменён, сказано ОДИН раз")
    void withoutAspectJProxyCreatorWeTouchNothingAndSayIt() {
        // Сердцевина issue #70, форма живого приложения `ledger`: команда выключила
        // авто-проксирование после инцидента, а мы возвращали его обратно.
        // Мутация: вернуть @EnableAspectJAutoProxy на AllureDataJpaAutoConfiguration →
        // появится аспект И подменится internalAutoProxyCreator → RED (оба ассерта).
        // Этот же тест видит ещё одну мутацию, и она замерена: спрашивать создатель прокси
        // по ИМЕНИ вместо типа (`type != null` в hasAspectJProxyCreator) → инфраструктурный
        // создатель от @EnableTransactionManagement сойдёт за AspectJ-овский → RED.
        // ⚠️ Мутацию «гейт по свойству spring.aop.auto» сюда НЕ пиши: здесь свойство как раз
        // false, гейт по нему дал бы тот же исход, и тест остаётся зелёным — замерено. Её ловят
        // ownAspectJAutoProxyKeepsTheDbSection и lateAspectJProxyCreatorStillGetsTheAspect.
        ActivationDiagnostics.forgetForTests();

        List<LogRecord> said = logWhile(() ->
                new ApplicationContextRunner()
                        .withUserConfiguration(TransactionShapedConfig.class, LedgerShapedConfig.class)
                        .withConfiguration(AutoConfigurations.of(
                                AllureDataSourceAutoConfiguration.class, AllureDataJpaAutoConfiguration.class))
                        .withPropertyValues("spring.aop.auto=false")
                        .run(ctx -> {
                            assertThat(ctx)
                                    .as("аспект вернул проксирование, которое потребитель выключил")
                                    .doesNotHaveBean(AllureRepositoryAspect.class);
                            assertThat(ctx).as("канал SQL живёт отдельно от AOP — на этом стоит "
                                            + "обещание из текста новости «реальный SQL остаётся»")
                                    .hasBean("allureDataSourceProxyPostProcessor");
                            assertThat(ctx.getBean(AopConfigUtils.AUTO_PROXY_CREATOR_BEAN_NAME).getClass().getName())
                                    .as("мы подменили создатель прокси потребителя: проксируется больше "
                                            + "бинов, чем он разрешал, и оживают его спящие аспекты")
                                    .isEqualTo(InfrastructureAdvisorAutoProxyCreator.class.getName());
                        }));

        // Само предупреждение проверяет RepositoryNoticeOnRealSpringDataTest: после сужения
        // предиката оно требует НАСТОЯЩЕЙ фабрики Spring Data, а здесь её нет и быть не может —
        // фикстура собрана руками. Здесь проверяем то, что фикстура честно показывает: чужой
        // создатель прокси не подменён и аспект не навязан.
        assertThat(said).as("лишних строк библиотека говорить не должна").isEmpty();
    }

    @Test
    @DisplayName("потребитель включил проксирование САМ: раздел БД остаётся, новости нет")
    void ownAspectJAutoProxyKeepsTheDbSection() {
        // Форма живого приложения `audit`: spring.aop.auto=false ПЛЮС свой @EnableAspectJAutoProxy.
        // Подменять нечего — создатель у него уже нужного типа, — и отнимать шаги не за что.
        // Мутация: гейт по свойству spring.aop.auto вместо взгляда в реестр → у `audit`
        // аспекта не будет, хотя проксирование он поднял сам → RED (замерено).
        // Соседняя ось — то же самое, но когда проксирование поднимают ПОЗЖЕ нашей
        // автоконфигурации: lateAspectJProxyCreatorStillGetsTheAspect.
        ActivationDiagnostics.forgetForTests();

        List<LogRecord> said = logWhile(() ->
                new ApplicationContextRunner()
                        .withUserConfiguration(OwnAspectJProxyConfig.class, LedgerShapedConfig.class)
                        .withConfiguration(AutoConfigurations.of(AllureDataJpaAutoConfiguration.class))
                        .withPropertyValues("spring.aop.auto=false")
                        .run(ctx -> assertThat(ctx)
                                .as("потребитель поднял проксирование сам — раздел БД он терять не должен")
                                .hasSingleBean(AllureRepositoryAspect.class)));

        assertThat(said).as("новость про потерянный раздел там, где раздел на месте, — ложь и шум")
                .isEmpty();
    }

    @Test
    @DisplayName("чужой стартер поднимает проксирование ПОЗЖЕ нас: аспект всё равно есть, новости нет")
    void lateAspectJProxyCreatorStillGetsTheAspect() {
        // Ось, ради которой решение переехало из @ConditionalOnBean в пост-процессор реестра:
        // создатель прокси приходит ПОСЛЕ разбора нашей автоконфигурации. Условие видит срез
        // реестра на своём месте в очереди, и на этом срезе создателя ещё нет — аспекта не будет,
        // а новость соврёт про «создателя нет», хотя он появится через один конфиг.
        // Мутация: вернуть @ConditionalOnBean(name = AUTO_PROXY_CREATOR_BEAN_NAME) вместо
        // регистрации в BeanDefinitionRegistryPostProcessor → RED.
        ActivationDiagnostics.forgetForTests();

        List<LogRecord> said = logWhile(() ->
                new ApplicationContextRunner()
                        .withConfiguration(AutoConfigurations.of(
                                AopAutoConfiguration.class,
                                AllureDataJpaAutoConfiguration.class,
                                ConditionalOnBeanProbe.class,
                                LateForeignAspectJStarter.class))
                        .withUserConfiguration(LedgerShapedConfig.class)
                        .withPropertyValues("spring.aop.auto=false")
                        .run(ctx -> {
                            // ЯКОРЬ ПЕРВЫЙ: ось воспроизведена, а не подделана порядком конфигов.
                            // Зонд — точная копия отвергнутой конструкции, стоящий на нашем месте
                            // в очереди. Его бина НЕТ ⇒ на срезе @ConditionalOnBean создателя не
                            // видно ⇒ «позже нас» — правда. Уедет сортировка автоконфигураций,
                            // и зонд увидит создателя — тест покраснеет здесь, а не соврёт зелёным.
                            assertThat(ctx)
                                    .as("создатель прокси виден уже на срезе @ConditionalOnBean — "
                                            + "чужой стартер пришёл НЕ позже нас, ось не воспроизведена")
                                    .doesNotHaveBean("sawProxyCreatorAtOurPosition");
                            // ЯКОРЬ ВТОРОЙ: создатель в итоге всё-таки есть и он AspectJ-овский.
                            // Без него «аспект есть» можно было бы получить, вообще сняв гейт.
                            assertThat(ctx.getBean(AopConfigUtils.AUTO_PROXY_CREATOR_BEAN_NAME)
                                    .getClass().getName())
                                    .as("чужой стартер не поднял AspectJ-создателя — фикстура сломана")
                                    .isEqualTo(AnnotationAwareAspectJAutoProxyCreator.class.getName());
                            assertThat(ctx)
                                    .as("создатель прокси в контексте есть, а аспекта нет: раздел БД "
                                            + "потерян у потребителя, который ничего не выключал")
                                    .hasSingleBean(AllureRepositoryAspect.class);
                        }));

        assertThat(said).as("новость про потерянный раздел там, где раздел на месте, — ложь и шум")
                .isEmpty();
    }

    @Test
    @DisplayName("по умолчанию аспект регистрируется и помечен инфраструктурным")
    void defaultsRegisterTheAspectAsInfrastructure() {
        // Мутации, которые видит ИМЕННО этот тест (замерено):
        //   • убрать definition.setRole(ROLE_INFRASTRUCTURE) → RED;
        //   • убрать definition.setResourceDescription(...) → RED.
        // Роль и происхождение — не косметика: без роли наш бин виден в /actuator/beans как
        // прикладной и лезет в автовайринг по типу, без происхождения в тексте ошибки Spring
        // стоит «defined in null», и решение библиотеки нечем аудировать.
        //
        // ⚠️ Молчание новости этот тест НЕ сторожит, хотя раньше комментарий это обещал.
        // Замерено: мутация «говорить новость безусловно» его не краснит — в фикстуре уровня A
        // нет настоящей фабрики Spring Data, поэтому hasRepositoryBeans здесь ложно при любой
        // правке, и новость не может прозвучать в принципе. Ось молчания держит
        // RepositoryNoticeOnRealSpringDataTest.staysSilentWhenProxyCreatorIsThere.
        ActivationDiagnostics.forgetForTests();

        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        AopAutoConfiguration.class, AllureDataJpaAutoConfiguration.class))
                .withUserConfiguration(LedgerShapedConfig.class)
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(AllureRepositoryAspect.class);

                    BeanDefinition definition = ctx.getBeanFactory()
                            .getBeanDefinition(ASPECT_BEAN_NAME);
                    assertThat(definition.getRole())
                            .as("аспект выглядит прикладным бином потребителя: попадёт "
                                    + "в /actuator/beans и в кандидаты на автовайринг по типу")
                            .isEqualTo(BeanDefinition.ROLE_INFRASTRUCTURE);
                    assertThat(definition.getResourceDescription())
                            .as("у определения нет происхождения — в тексте ошибки Spring встанет "
                                    + "«defined in null», и решение библиотеки нечем аудировать")
                            .isEqualTo(AllureDataJpaAutoConfiguration.class.getName());
                });
    }

    @Test
    @DisplayName("имя аспекта занято потребителем: побеждает его бин, контекст встаёт")
    void consumerDefinitionOfTheAspectNameWins() {
        // Issue #73: коллизия имени бина. Гард в регистраторе (containsBeanDefinition → выходим)
        // до сих пор не был прибит ничем.
        // Мутация: убрать гард → наше определение перезапишет бин потребителя → RED.
        //
        // ⚠️ Переопределение бинов здесь РАЗРЕШЕНО намеренно, и это единственная форма, на
        // которой мутация видна. Замерено на обеих:
        //   • overriding=false (умолчание Boot и ApplicationContextRunner) — без гарда
        //     registerBeanDefinition бросает BeanDefinitionOverrideException, её ГЛОТАЕТ
        //     catch-all самого регистратора, контекст встаёт, бин потребителя цел. Исход
        //     снаружи тот же, что с гардом, — сторожить тут нечего;
        //   • overriding=true — без гарда наше определение молча заменяет бин потребителя.
        //     Вот эту тихую потерю гард и держит.
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        AopAutoConfiguration.class, AllureDataJpaAutoConfiguration.class))
                .withUserConfiguration(OwnAspectBeanNameConfig.class, LedgerShapedConfig.class)
                .withAllowBeanDefinitionOverriding(true)
                .run(ctx -> {
                    assertThat(ctx)
                            .as("библиотека уронила старт из-за собственного имени бина (#73)")
                            .hasNotFailed();
                    // ЯКОРЬ: бин потребителя не просто уцелел — он ТОТ САМЫЙ. Без сверки
                    // значения ассерт прошёл бы и на нашем аспекте, молча съевшем чужой бин.
                    assertThat(ctx.getBean(ASPECT_BEAN_NAME))
                            .as("наш аспект перезаписал бин потребителя под тем же именем")
                            .isSameAs(OwnAspectBeanNameConfig.CONSUMER_BEAN);
                });
    }

    @Test
    @DisplayName("репозиториев нет — молчим, даже если аспекта нет")
    void silentWhenConsumerHasNoRepositories() {
        // Правило из javadoc ActivationDiagnostics.reportOnce: «фичи нет вовсе — молчим».
        // Мутация: убрать проверку hasRepositoryBeans у новости → RED.
        ActivationDiagnostics.forgetForTests();

        List<LogRecord> said = logWhile(() ->
                new ApplicationContextRunner()
                        .withUserConfiguration(TransactionShapedConfig.class)
                        .withConfiguration(AutoConfigurations.of(AllureDataJpaAutoConfiguration.class))
                        .withPropertyValues("spring.aop.auto=false")
                        .run(ctx -> assertThat(ctx).hasNotFailed()));

        assertThat(said).as("терять нечего, а WARNING в каждой сборке обесценивает канал")
                .isEmpty();
    }

    @Test
    @DisplayName("ленивая инициализация не съедает новость")
    void noticeSurvivesLazyInitialization() {
        // Замерено на живом `ledger`: с spring.main.lazy-initialization=true предыдущая редакция
        // (побочный эффект в конструкторе @Configuration) молчала — тихая потеря возвращалась.
        // Мутация: перенести новость обратно в конструктор бина-конфигурации → RED.
        ActivationDiagnostics.forgetForTests();

        List<LogRecord> said = logWhile(() ->
                new ApplicationContextRunner()
                        .withInitializer(ctx -> ctx.addBeanFactoryPostProcessor(
                                new LazyInitializationBeanFactoryPostProcessor()))
                        .withUserConfiguration(TransactionShapedConfig.class, LedgerShapedConfig.class)
                        .withConfiguration(AutoConfigurations.of(AllureDataJpaAutoConfiguration.class))
                        .withPropertyValues("spring.aop.auto=false")
                        .run(ctx -> assertThat(ctx).hasNotFailed()));

        // Ленивая инициализация не должна ломать сам механизм: пост-процессор реестра
        // выполняется всегда. Что он при этом СКАЗАЛ — дело теста на настоящей Spring Data.
        assertThat(said).as("под ленивой инициализацией библиотека не должна шуметь").isEmpty();
    }

    @Test
    @DisplayName("самописный DAO с маркером Repository не проксируется; контекст встаёт при proxy-target-class=false")
    void plainDaoWithRepositoryMarkerIsNotProxied() {
        // Сердцевина issue #71. Repository — ПУСТОЙ маркер, его реализует и самописный DAO.
        // Мутация: убрать «&& target(TransactionalProxy)» из поинтката → оба DAO станут прокси,
        // а DAO с содержательным интерфейсом при proxy-target-class=false станет JDK-прокси,
        // и инъекция по конкретному классу не соберётся → RED (контекст упал).
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        AopAutoConfiguration.class, AllureDataJpaAutoConfiguration.class))
                .withUserConfiguration(LedgerShapedConfig.class)
                .withPropertyValues("spring.aop.proxy-target-class=false")
                .run(ctx -> {
                    assertThat(ctx)
                            .as("контекст потребителя не поднялся — ровно то падение, с которым пришёл ledger")
                            .hasNotFailed();
                    // ЯКОРЬ. Без него негатив пустой: выключи аспект целиком, и «DAO не прокси»
                    // станет правдой по той причине, что не проксируется НИЧЕГО.
                    // Ссылки сравниваем булевым выражением, а не isNotSameAs: у AssertJ значение
                    // уезжает в имя шага, и $Proxy оттуда роняет гигиену имён.
                    assertThat(ctx.getBean(FakeSpringDataRepo.class) != LedgerShapedConfig.RAW_REPOSITORY)
                            .as("настоящий репозиторий перестал проксироваться — сузили слишком сильно, "
                                    + "раздел БД исчезнет у всех")
                            .isTrue();
                    assertThat(AopUtils.isAopProxy(ctx.getBean(PlainDao.class)))
                            .as("самописный DAO стал прокси: пустой маркер Repository — не согласие "
                                    + "потребителя на проксирование")
                            .isFalse();
                    assertThat(AopUtils.isAopProxy(ctx.getBean(InterfacedDao.class)))
                            .as("DAO с содержательным интерфейсом стал прокси — при "
                                    + "proxy-target-class=false это JDK-прокси, и бин перестаёт быть "
                                    + "объектом своего класса")
                            .isFalse();
                });
    }

    @Test
    @DisplayName("прокси в форме Spring Data по-прежнему даёт шаг «DB …»")
    void springDataShapedProxyStillProducesDbStep() {
        // Обратная сторона #71: сузить можно и слишком сильно — тогда раздел БД исчезает МОЛЧА,
        // и предыдущий тест этого не увидит (там «не прокси» — ожидаемый исход).
        // Мутация: сузить до типа, которого у прокси Spring Data нет (напр. RandomAccess) →
        // шага не будет → RED. Мутацию target→this сюда НЕ пиши: она форвардная, ничего не
        // краснит, см. docs/review-log.md.
        // Заодно стережёт repositoryName: имя обязано быть FakeSpringDataRepo, а не $ProxyNN.
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        AopAutoConfiguration.class, AllureDataJpaAutoConfiguration.class))
                .withUserConfiguration(LedgerShapedConfig.class)
                .run(ctx -> {
                    FakeSpringDataRepo repo = ctx.getBean(FakeSpringDataRepo.class);
                    InMemoryAllure allure = new InMemoryAllure().install();
                    try {
                        TestResult recorded = allure.run("вызов репозитория", () -> repo.findById(1L));

                        assertThat(allure.hasStep(recorded, "DB FakeSpringDataRepo.findById"))
                                .as("шаг репозитория пропал: отчёт обеднел молча, тесты при этом зелёные")
                                .isTrue();
                    } finally {
                        allure.uninstall();
                    }
                });
    }

    @Test
    @DisplayName("без spring-tx на classpath: JPA-аспект НЕ регистрируется")
    void repositoryAspectAbsentWithoutTransactionalProxy() {
        // Поинткат НАЗЫВАЕТ TransactionalProxy, и AspectJ резолвит это имя при разборе выражения.
        // Мутация: убрать TransactionalProxy из @ConditionalOnClass → появится бин, который
        // не даст ни одного шага (AspectJ не матчит нерезолвимый тип) → RED. Контекст при этом
        // не падает — замерено; «упадёт refresh» сюда не писать.
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(AllureDataJpaAutoConfiguration.class))
                .withClassLoader(new FilteredClassLoader(TransactionalProxy.class))
                .run(ctx -> assertThat(ctx).doesNotHaveBean(AllureRepositoryAspect.class));
    }

    /** Репозиторий потребителя: то, что Spring Data создаёт из интерфейса. */
    interface FakeSpringDataRepo extends org.springframework.data.repository.Repository<Object, Long> {
        Object findById(Long id);
    }

    /** DAO потребителя: маркер есть, {@code TransactionalProxy} — нет. Так выглядит DAO из `ledger`. */
    static class PlainDao implements org.springframework.data.repository.Repository<Object, Long> {
        Object findByLogin(String login) {
            return login;
        }
    }

    /** Содержательный интерфейс — тот случай, когда proxy-target-class=false даёт JDK-прокси. */
    interface AccountLookup {
        Object lookup(String code);
    }

    static class InterfacedDao implements AccountLookup,
            org.springframework.data.repository.Repository<Object, Long> {
        @Override
        public Object lookup(String code) {
            return code;
        }
    }

    /** Прод-код потребителя: просит DAO по КОНКРЕТНОМУ классу — на этом и падал контекст. */
    record DaoClient(InterfacedDao dao) {
    }

    /** Форма приложения `ledger`: настоящий репозиторий рядом с двумя самописными DAO. */
    @Configuration(proxyBeanMethods = false)
    static class LedgerShapedConfig {

        /**
         * Прокси в форме {@code RepositoryFactorySupport.getRepository}: те же три интерфейса,
         * которые Spring Data ставит безусловно.
         */
        static final FakeSpringDataRepo RAW_REPOSITORY = springDataShapedProxy();

        private static FakeSpringDataRepo springDataShapedProxy() {
            ProxyFactory factory = new ProxyFactory();
            factory.setTarget((FakeSpringDataRepo) id -> "widget#" + id);
            factory.setInterfaces(FakeSpringDataRepo.class,
                    org.springframework.data.repository.Repository.class,
                    TransactionalProxy.class);
            return (FakeSpringDataRepo) factory.getProxy();
        }

        /**
         * ⚠️ Именно {@link FactoryBean}, а не готовый бин: так репозиторий заводит Spring Data
         * ({@code JpaRepositoryFactoryBean}), и только на этой форме видно, что
         * {@code getBeanNamesForType} в фазе BFPP её не матчит без {@code includeNonSingletons}.
         * Пока фикстура была обычным {@code @Bean}, блокер прошёл мимо пяти тестов сразу.
         * <p>
         * ⚠️ Тип возврата — СЫРОЙ {@code FactoryBean}, без дженерика. С дженериком Spring выводит
         * тип объекта статически и матчит фабрику даже без {@code includeNonSingletons} — а у
         * настоящего {@code JpaRepositoryFactoryBean} тип известен только в рантайме, потому что
         * зависит от интерфейса репозитория. Замерено: с дженериком блокер НЕ воспроизводится.
         */
        @Bean
        @SuppressWarnings("rawtypes")
        FactoryBean widgetRepository() {
            return new FactoryBean<FakeSpringDataRepo>() {
                @Override
                public FakeSpringDataRepo getObject() {
                    return RAW_REPOSITORY;
                }

                @Override
                public Class<?> getObjectType() {
                    return FakeSpringDataRepo.class;
                }
            };
        }

        @Bean
        PlainDao turnoverDao() {
            return new PlainDao();
        }

        @Bean
        InterfacedDao accountDao() {
            return new InterfacedDao();
        }

        @Bean
        DaoClient daoClient(InterfacedDao dao) {
            return new DaoClient(dao);
        }
    }

    /** Потребитель с транзакциями: их создатель прокси и обязан пережить подключение библиотеки. */
    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    static class TransactionShapedConfig {
    }

    /** Форма приложения `audit`: автоматику Boot выключил, но проксирование поднял сам. */
    @Configuration(proxyBeanMethods = false)
    @EnableAspectJAutoProxy(proxyTargetClass = true)
    static class OwnAspectJProxyConfig {
    }

    /** Потребитель занял наше имя бина — законное право его конфигурации (issue #73). */
    @Configuration(proxyBeanMethods = false)
    static class OwnAspectBeanNameConfig {

        static final Object CONSUMER_BEAN = new Object();

        @Bean(name = ASPECT_BEAN_NAME)
        Object allureRepositoryAspect() {
            return CONSUMER_BEAN;
        }
    }

    /**
     * Чужой стартер, который поднимает AspectJ-проксирование ПОСЛЕ нашей автоконфигурации.
     * {@code after} на НАС — то самое расположение, из-за которого {@code @ConditionalOnBean}
     * промахивался: к моменту разбора нашего условия этот конфиг ещё не тронут.
     */
    @AutoConfiguration(after = AllureDataJpaAutoConfiguration.class)
    @EnableAspectJAutoProxy
    static class LateForeignAspectJStarter {
    }

    /**
     * Зонд в форме ОТВЕРГНУТОЙ конструкции, стоящий там же, где стоим мы: после
     * {@code AopAutoConfiguration} и до чужого стартера. Он существует ровно затем, чтобы
     * тест выше не был зелёным по неверной причине — его бин появится, если чужой стартер
     * на самом деле придёт раньше нас, и тогда проверяемая ось просто не воспроизведена.
     */
    @AutoConfiguration(after = AopAutoConfiguration.class, before = LateForeignAspectJStarter.class)
    static class ConditionalOnBeanProbe {

        @Bean
        @ConditionalOnBean(name = AopConfigUtils.AUTO_PROXY_CREATOR_BEAN_NAME)
        String sawProxyCreatorAtOurPosition() {
            return "создатель прокси был виден уже на срезе условия";
        }
    }
}
