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
 * {@code @EnableAspectJAutoProxy} на нашей стороне подменяет бин {@code internalAutoProxyCreator}
 * у потребителя, отказавшегося от авто-проксирования, и меняет состав проксируемых бинов
 * (issue #70). Держит {@code withoutAspectJProxyCreatorWeTouchNothing}.
 * <p>
 * Гейт по ФАКТУ в реестре, а не по {@code spring.aop.auto}: отказаться от авто-проксирования
 * можно ещё двумя способами (исключить {@link AopAutoConfiguration}, написать значение не
 * словом {@code false}), и каждый даёт ту же подмену.
 * <p>
 * Потребитель, поднявший проксирование сам, раздел БД получает — гейт по свойству отнял бы
 * у него шаги ни за что ({@code ownAspectJAutoProxyKeepsTheDbSection}).
 * <p>
 * Решение и новость принимает {@code allureRepositoryAspectRegistrar} — там же почему не
 * {@code @ConditionalOnBean}. Что теряет потребитель и что ему делать — дословно в
 * {@code NOTICE} ниже.
 */
// ⚠️ НЕ добавляй сюда after/before: порядок автоконфигураций на исход не влияет (решение
// принимает пост-процессор реестра), а читателю подсказывает, будто он что-то решает.
@AutoConfiguration
@ConditionalOnClass(name = {
        "org.aspectj.lang.ProceedingJoinPoint",
        "org.springframework.data.repository.Repository",
        // Тип назван в поинткате аспекта: на нерезолвимом типе AspectJ молча не даёт ни одного
        // шага, контекст при этом стоит. Условие превращает мёртвый бин в честное отсутствие.
        // Держит repositoryAspectAbsentWithoutTransactionalProxy.
        "org.springframework.transaction.interceptor.TransactionalProxy"
})
public class AllureDataJpaAutoConfiguration {

    /**
     * Создатель прокси, который умеет в {@code @Aspect}-бины. Именно ТИП, а не имя
     * {@code internalAutoProxyCreator}: под тем же именем стоит {@code InfrastructureAdvisorAutoProxyCreator}
     * (его ставит {@code @EnableTransactionManagement}), а он аспекты не смотрит — по имени
     * получился бы мёртвый бин без предупреждения.
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
     * Один взгляд на реестр решает и что регистрировать, и что сказать.
     * <p>
     * {@link BeanDefinitionRegistryPostProcessor} идёт после {@code ConfigurationClassPostProcessor},
     * поэтому видит определения и Boot, и поздние чужие. Ни {@code @ConditionalOnBean} (срез
     * реестра на момент разбора), ни побочный эффект в конструкторе конфигурации (не выполняется
     * под {@code spring.main.lazy-initialization=true}) этого не дают. Держат
     * {@code lateAspectJProxyCreatorStillGetsTheAspect} и {@code noticeSurvivesLazyInitialization}.
     * <p>
     * Граница в одну фазу: определения, которые зарегистрирует ДРУГОЙ
     * {@code BeanDefinitionRegistryPostProcessor} после нас, мы не увидим — issue #86.
     */
    @Bean
    static BeanDefinitionRegistryPostProcessor allureRepositoryAspectRegistrar() {
        return registry -> {
            try {
                // Регистрировать аспект — вопрос про создателя прокси. Говорить новость —
                // вопрос про потерю: без репозиториев терять нечего.
                Answer creator = hasAspectJProxyCreator(registry);
                if (creator == Answer.YES) {
                    // Имя занято — НЕ трогаем: конфигурация потребителя обязана побеждать нашу.
                    // Без гарда при allow-bean-definition-overriding=true наше определение молча
                    // заменяет бин потребителя, при умолчании — WARN со стеком в каждой сборке
                    // (issue #74). Держит consumerDefinitionOfTheAspectNameWins.
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
                // Почему warnQuietly, а не логгер напрямую — см. её javadoc.
                ActivationDiagnostics.warnQuietly("DbRepository", diagnosticIsNotWorthATest);
            }
        };
    }

    /**
     * Стоит ли под штатным именем создатель прокси, который смотрит {@code @Aspect}-бины.
     * <p>
     * Сравнение по ПРИСВАИВАЕМОСТИ, а не по равенству строк: чужой стартер вправе поставить
     * свой подкласс {@code AnnotationAwareAspectJAutoProxyCreator}, аспекты он смотрит так же.
     * Мутация {@code isAssignableFrom} → {@code equals} краснит
     * {@code subclassOfAspectJCreatorCountsAsOne}.
     * <p>
     * Имя не резолвится (свой загрузчик, класс недоступен) — отвечаем {@code UNKNOWN}: судить
     * не о чем, а новость на «нет» утверждала бы про чужой контекст непроверенное.
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
            // Загрузчик берём У ПОТРЕБИТЕЛЯ: Spring резолвит его бины именно им. Свой не видит
            // классов дочернего (devtools, WAR в контейнере, OSGi) — и мы отвечали бы
            // «создателя нет» про контекст, где он есть. Гейта нет: тест на дочерний загрузчик
            // ещё не заведён.
            ClassLoader loader = classLoaderOf(registry);
            return ClassUtils.forName(ASPECTJ_PROXY_CREATOR, loader)
                    .isAssignableFrom(ClassUtils.forName(type, loader))
                    ? Answer.YES : Answer.NO;
        } catch (Throwable notResolvable) {
            // ⚠️ НЕ отвечай отсюда «нет»: на «нет» висит новость, которая УТВЕРЖДАЕТ про чужой
            // контекст то, чего мы не проверяли.
            AllureInstrumentationLogger.trace("DbRepository", () ->
                    "тип создателя прокси «" + type + "» не резолвится загрузчиком библиотеки: "
                            + "судить о нём не можем, шагов «DB Repo.method» не будет");
            return Answer.UNKNOWN;
        }
    }

    /** Что мы смогли узнать про создатель прокси. «Не смогли посмотреть» — не то же, что «нет». */
    private enum Answer { YES, NO, UNKNOWN }

    /**
     * Есть ли у потребителя хоть один репозиторий. Типы резолвим ПО ИМЕНИ — см.
     * {@code AllureRepositoryAspect#SPRING_DATA_REPOSITORY_CALL}.
     * <p>
     * Спрашиваем ФАБРИКУ, а не маркер {@code Repository}: по маркеру нашёлся бы и самописный
     * DAO, которому поинткат шагов не даёт никогда. Держат
     * {@code withoutAspectJProxyCreatorWeTouchNothing} и {@code staysQuietUnderLazyInitialization}.
     * Пустой ответ даёт КОМБИНАЦИЯ «маркер + {@code includeNonSingletons=false}», а не флаг сам
     * по себе — замер всех четырёх комбинаций в {@code docs/review-log.md}.
     * <p>
     * {@code includeNonSingletons=true} гейта не имеет: мутация флага не краснит ничего.
     * <p>
     * {@code allowEagerInit} остаётся {@code false}: с {@code true} мы звали бы
     * {@code getObjectType()} на фабриках бинов потребителя до регистрации
     * {@code BeanPostProcessor}'ов — поднимали бы чужую инфраструктуру ради диагностики.
     */
    private static boolean hasRepositoryBeans(ListableBeanFactory beanFactory) {
        return hasBeansOfType(beanFactory,
                "org.springframework.data.repository.core.support.RepositoryFactoryBeanSupport");
    }

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
