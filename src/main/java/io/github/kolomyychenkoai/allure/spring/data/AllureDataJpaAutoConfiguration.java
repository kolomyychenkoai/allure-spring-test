package io.github.kolomyychenkoai.allure.spring.data;

import io.github.kolomyychenkoai.allure.spring.data.internal.AllureRepositoryAspect;
import io.github.kolomyychenkoai.allure.spring.internal.ActivationDiagnostics;
import io.github.kolomyychenkoai.allure.spring.internal.AllureInstrumentationLogger;
import org.springframework.aop.config.AopConfigUtils;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.util.ClassUtils;

/**
 * Авто-активация логирования вызовов Spring Data репозиториев: регистрирует
 * {@link AllureRepositoryAspect}. Активируется сама, если на classpath есть AspectJ,
 * Spring Data Repository и spring-tx. Регистрируется через
 * {@code META-INF/spring/...AutoConfiguration.imports}.
 * <p>
 * <b>Авто-проксирование мы НЕ включаем — только пользуемся тем, что включил потребитель.</b>
 * С {@code @EnableAspectJAutoProxy} на нашей стороне бин {@code internalAutoProxyCreator}
 * у потребителя, отказавшегося от авто-проксирования, сменился бы с
 * {@code InfrastructureAdvisorAutoProxyCreator} (его ставит {@code @EnableTransactionManagement})
 * на {@code AnnotationAwareAspectJAutoProxyCreator}: проксировалось бы больше бинов, чем он
 * разрешал, оживали бы его спящие аспекты, а инъекция по конкретному классу перестала бы
 * собираться (issue #70). Держит {@code withoutAspectJProxyCreatorWeTouchNothing}.
 * <p>
 * Гейт стоит на ФАКТЕ, а не на намерении: смотрим, есть ли в реестре AspectJ-совместимый
 * создатель прокси. Свойство {@code spring.aop.auto} читать было бы недостаточно — отказаться
 * от авто-проксирования можно ещё как минимум двумя способами (исключить
 * {@link AopAutoConfiguration}, написать значение свойства не словом {@code false}), и каждый
 * из них дал бы ту же подмену. Спрашивая реестр, мы видим факт вместо догадки.
 * <p>
 * ⚠️ И решение, и новость живут в ОДНОМ {@link BeanDefinitionRegistryPostProcessor}, а не в
 * {@code @ConditionalOnBean}. Условие видит лишь срез реестра на момент разбора НАШЕЙ
 * автоконфигурации, и создатель, зарегистрированный позже (чужой стартер со своим
 * {@code @EnableAspectJAutoProxy}), оставлял нас без аспекта — а новость при этом сообщала
 * причину «создателя нет», которая уже была неправдой. Замерено. Пост-процессор реестра идёт
 * после {@code ConfigurationClassPostProcessor}, то есть видит ВСЕ определения, включая поздние:
 * один и тот же взгляд решает и что регистрировать, и что сказать.
 * <p>
 * Обратная сторона того же выбора: потребитель, включивший проксирование САМ (например
 * {@code spring.aop.auto=false} плюс собственный {@code @EnableAspectJAutoProxy} — так живёт
 * приложение `audit` из охоты), раздел БД получает, потому что создатель у него есть. Гейт по
 * свойству отнял бы у него шаги ни за что.
 * <p>
 * <b>Цена названа вслух.</b> Там, где AspectJ-создателя нет, шагов «DB Repo.method» не будет:
 * их пишет Spring-аспект. Реальный SQL остаётся — его пишет отдельный канал
 * ({@link AllureDataSourceAutoConfiguration}, свой {@code ProxyFactory} без авто-проксирования), —
 * но окажется на верхнем уровне теста, а не внутри шага репозитория. Об этом говорит одна строка в логе — см. регистратор ниже.
 */
// Порядок автоконфигураций здесь НАМЕРЕННО не задан: решение принимает пост-процессор
// реестра, он идёт после разбора всех конфигураций и видит их одинаково — и раньше нас,
// и позже.
// ⚠️ НЕ добавляй сюда after/before: на исход они не влияют (замерено), а читателю
// подсказывают, будто порядок здесь что-то решает.
@AutoConfiguration
@ConditionalOnClass(name = {
        "org.aspectj.lang.ProceedingJoinPoint",
        "org.springframework.data.repository.Repository",
        // Этот тип НАЗЫВАЕТ поинткат аспекта. Без него аспект-бин был бы мёртвым: AspectJ не
        // матчит нерезолвимый тип и молча не даёт ни одного шага (замерено — контекст при этом
        // НЕ падает, поэтому «иначе упадёт refresh» тут не пишем). Условие превращает мёртвый
        // бин в честное отсутствие, о котором говорит ActivationDiagnostics.problems.
        // Потребитель без spring-tx теоретически возможен (в spring-data-commons зависимость
        // optional), но репозиториев у него нет: их фабрика ставит TransactionalProxy безусловно.
        "org.springframework.transaction.interceptor.TransactionalProxy"
})
public class AllureDataJpaAutoConfiguration {

    /**
     * Создатель прокси, который умеет в {@code @Aspect}-бины. Именно ТИП, а не имя
     * {@code internalAutoProxyCreator}: под этим именем может стоять и
     * {@code InfrastructureAdvisorAutoProxyCreator} (его ставит {@code @EnableTransactionManagement}),
     * а он аспекты не смотрит — по имени мы завели бы мёртвый бин и потеряли предупреждение.
     */
    private static final String ASPECTJ_PROXY_CREATOR =
            "org.springframework.aop.aspectj.annotation.AnnotationAwareAspectJAutoProxyCreator";

    static final String ASPECT_BEAN_NAME = "allureRepositoryAspect";

    private static final String NOTICE =
            "AspectJ-создателя прокси в контексте нет (авто-проксирование выключено или заменено "
                    + "инфраструктурным), и мы его НЕ включаем: подмена internalAutoProxyCreator "
                    + "меняла бы проксирование ваших бинов (issue #70). Следствие: шагов "
                    + "«DB Repo.method» в отчёте не будет — их пишет Spring-аспект. Нужны шаги "
                    + "репозиториев — включите авто-проксирование (spring.aop.auto по умолчанию "
                    + "включено) либо заведите @EnableAspectJAutoProxy у себя. "
                    + "Заглушить эту строку: -Dallure.spring.diagnostics=off";

    /**
     * Один взгляд на реестр решает всё: регистрировать ли аспект и что сказать, если нет.
     * <p>
     * {@link BeanDefinitionRegistryPostProcessor} идёт после {@code ConfigurationClassPostProcessor},
     * поэтому видит ВСЕ определения — и наши, и автоконфигурации Boot, и поздние чужие. Ни
     * {@code @ConditionalOnBean} (срез реестра на момент разбора), ни побочный эффект в
     * конструкторе конфигурации (не выполняется под {@code spring.main.lazy-initialization=true})
     * этого не дают. Держат {@code lateAspectJProxyCreatorStillGetsTheAspect} и
     * {@code noticeSurvivesLazyInitialization}.
     * <p>
     * ⚠️ Граница у «видит все» есть, и она в одну фазу: определения, которые зарегистрирует
     * ДРУГОЙ {@code BeanDefinitionRegistryPostProcessor} после нас, мы не увидим. Замерено —
     * чужой BDRPP, поднимающий создатель прокси второй волной, оставляет нас без аспекта.
     */
    @Bean
    static BeanDefinitionRegistryPostProcessor allureRepositoryAspectRegistrar() {
        // Лямбда, а не анонимный класс: postProcessBeanFactory у интерфейса — default-метод
        // во всех поддерживаемых версиях (проверено по байткоду spring-beans 6.1 / 6.2 / 7.0),
        // то есть интерфейс функциональный и пустому override взяться неоткуда.
        return registry -> {
            try {
                // Два РАЗНЫХ вопроса, и путать их нельзя. Регистрировать аспект — вопрос
                // про создателя прокси: без репозиториев он просто ничего не поймает, вреда
                // от него нет. Говорить новость — вопрос про потерю: без репозиториев терять
                // нечего, и предупреждение было бы шумом, который перестают читать.
                Answer creator = hasAspectJProxyCreator(registry);
                if (creator == Answer.YES) {
                    // Имя занято — НЕ трогаем: конфигурация потребителя обязана побеждать нашу.
                    // Замерено на обеих настройках переопределения. При
                    // allow-bean-definition-overriding=true без гарда наше определение молча
                    // заменяет бин потребителя — ради этого гард и стоит. При умолчании Boot
                    // (false) registerBeanDefinition бросает BeanDefinitionOverrideException,
                    // но наружу она не выходит: её глотает catch ниже, оставляя WARN со стеком
                    // в каждой сборке такого потребителя (issue #74). Снаружи исход тот же,
                    // изнутри — шум на ровном месте.
                    if (registry.containsBeanDefinition(ASPECT_BEAN_NAME)) {
                        return;
                    }
                    RootBeanDefinition definition = new RootBeanDefinition(AllureRepositoryAspect.class);
                    // Роль и происхождение: иначе наш бин выглядит прикладным бином потребителя
                    // (виден в /actuator/beans, кандидат на автовайринг), а в тексте ошибки
                    // стоит «defined in null» — решение библиотеки нечем аудировать.
                    definition.setRole(BeanDefinition.ROLE_INFRASTRUCTURE);
                    definition.setResourceDescription(AllureDataJpaAutoConfiguration.class.getName());
                    registry.registerBeanDefinition(ASPECT_BEAN_NAME, definition);
                } else if (creator == Answer.NO
                        && registry instanceof ListableBeanFactory beans && hasRepositoryBeans(beans)) {
                    ActivationDiagnostics.noteOnce("DbRepository", NOTICE);
                }
            } catch (Throwable diagnosticIsNotWorthATest) {
                // Мы внутри refresh чужого контекста: уронить его из-за раздела отчёта нельзя.
                // Жалоба идёт через warnQuietly, а не напрямую в логгер: запасной канал пишет
                // в ТОТ ЖЕ логгер, на котором мы могли только что упасть, и хендлер
                // потребителя, бросающий на publish, вынес бы исключение прямо в refresh.
                ActivationDiagnostics.warnQuietly("DbRepository", diagnosticIsNotWorthATest);
            }
        };
    }

    /**
     * Стоит ли под штатным именем создатель прокси, который смотрит {@code @Aspect}-бины.
     * <p>
     * Сравнение по ПРИСВАИВАЕМОСТИ, а не по равенству строк. Чужой стартер вправе поставить
     * СВОЙ подкласс {@code AnnotationAwareAspectJAutoProxyCreator} — аспекты он смотрит
     * ровно так же. Замерено: на точном {@code equals} такой потребитель молча терял раздел БД
     * И получал новость «AspectJ-создателя прокси в контексте нет», то есть прямую ложь про
     * собственный контекст. Держит {@code subclassOfAspectJCreatorCountsAsOne}.
     * <p>
     * Имя не резолвится (свой загрузчик, класс недоступен) — отвечаем «нет»: молчание дешевле
     * ложного вывода, а хуже прежнего поведения не станет.
     */
    private static Answer hasAspectJProxyCreator(BeanDefinitionRegistry registry) {
        if (!registry.containsBeanDefinition(AopConfigUtils.AUTO_PROXY_CREATOR_BEAN_NAME)) {
            return Answer.NO;
        }
        String type = registry.getBeanDefinition(AopConfigUtils.AUTO_PROXY_CREATOR_BEAN_NAME)
                .getBeanClassName();
        if (type == null) {
            return Answer.UNKNOWN; // определение без класса (instance supplier) — судить не по чему
        }
        if (ASPECTJ_PROXY_CREATOR.equals(type)) {
            return Answer.YES; // штатный случай, без загрузки классов
        }
        try {
            // Загрузчик берём У ПОТРЕБИТЕЛЯ: Spring резолвит его бины именно им. Свой
            // загрузчик не видит классов дочернего (devtools, WAR в контейнере, OSGi),
            // и мы отвечали бы «создателя нет» про контекст, где он есть. Замерено на
            // настоящей Spring Data: подкласс, видимый только загрузчику потребителя,
            // отнимал раздел БД и добавлял к этому ложную строку в логе.
            ClassLoader loader = classLoaderOf(registry);
            return ClassUtils.forName(ASPECTJ_PROXY_CREATOR, loader)
                    .isAssignableFrom(ClassUtils.forName(type, loader))
                    ? Answer.YES : Answer.NO;
        } catch (Throwable notResolvable) {
            // ⚠️ НЕ «нет». Мы не смогли посмотреть — это третий исход, и путать его с ответом
            // «создателя нет» нельзя: на «нет» висит новость, которая УТВЕРЖДАЕТ про чужой
            // контекст то, чего мы не проверяли. Так уже было с точным equals: аспекта нет
            // плюс строка «AspectJ-создателя в контексте нет» при живом создателе.
            AllureInstrumentationLogger.trace("DbRepository", () ->
                    "тип создателя прокси «" + type + "» не резолвится загрузчиком библиотеки: "
                            + "судить о нём не можем, шагов «DB Repo.method» не будет");
            return Answer.UNKNOWN;
        }
    }

    /** Что мы смогли узнать про создатель прокси. «Не смогли посмотреть» — не то же, что «нет». */
    private enum Answer { YES, NO, UNKNOWN }

    /**
     * Есть ли у потребителя хоть один репозиторий. Типы резолвим ПО ИМЕНИ: spring-data нет в
     * compile-classpath библиотеки (по той же причине поинткат аспекта задан строкой).
     * <p>
     * ⚠️ Замер в фазе пост-процессора на настоящей Spring Data (два репозитория,
     * {@code JpaRepositoryFactoryBean}) — все четыре комбинации:
     * <pre>
     * Repository,                    includeNonSingletons=true  → 2
     * Repository,                    includeNonSingletons=false → 0
     * RepositoryFactoryBeanSupport,  includeNonSingletons=true  → 2
     * RepositoryFactoryBeanSupport,  includeNonSingletons=false → 2
     * </pre>
     * Пустой ответ даёт КОМБИНАЦИЯ «маркер + {@code false}», а не один флаг сам по себе.
     * <p>
     * Спрашиваем ФАБРИКУ, а не маркер, и это несущий выбор: по маркеру нашёлся бы и самописный
     * DAO, а он шагов не даёт никогда — поинткат требует {@code TransactionalProxy}.
     * Предупреждать такого потребителя значило бы советовать ему включить проксирование и не
     * дать ничего взамен. Держат выбор {@code withoutAspectJProxyCreatorWeTouchNothing}
     * и {@code staysQuietUnderLazyInitialization}: под мутацией «маркер вместо фабрики» оба краснеют.
     * <p>
     * {@code includeNonSingletons=true} при фабрике, наоборот, НЕ несущий: по таблице выше ответ
     * тот же и с {@code false}, мутация флага не краснит ни одного теста — замерено, гейта на нём
     * сегодня нет. Оставлен как более широкий из двух равных запросов: если тип придётся
     * вернуть к маркеру, флаг уже правильный.
     * <p>
     * {@code allowEagerInit} остаётся {@code false}: с {@code true} мы звали бы
     * {@code getObjectType()} на фабриках бинов потребителя ещё до регистрации
     * {@code BeanPostProcessor}'ов, то есть поднимали бы чужую инфраструктуру ради диагностики.
     * Замерено, что гейта на этом флаге сегодня нет — как и на соседнем.
     */
    private static boolean hasRepositoryBeans(ListableBeanFactory beanFactory) {
        return hasBeansOfType(beanFactory,
                "org.springframework.data.repository.core.support.RepositoryFactoryBeanSupport");
    }

    /** Загрузчик, которым Spring резолвит бины ПОТРЕБИТЕЛЯ; наш — только запасной. */
    private static ClassLoader classLoaderOf(Object registryOrFactory) {
        if (registryOrFactory instanceof ConfigurableBeanFactory factory
                && factory.getBeanClassLoader() != null) {
            return factory.getBeanClassLoader();
        }
        return AllureDataJpaAutoConfiguration.class.getClassLoader();
    }

    private static boolean hasBeansOfType(ListableBeanFactory beanFactory, String typeName) {
        try {
            Class<?> type = ClassUtils.forName(typeName, classLoaderOf(beanFactory));
            return beanFactory.getBeanNamesForType(type, true, false).length > 0;
        } catch (Throwable notResolvable) {
            return false; // молчание дешевле ложного предупреждения
        }
    }
}
