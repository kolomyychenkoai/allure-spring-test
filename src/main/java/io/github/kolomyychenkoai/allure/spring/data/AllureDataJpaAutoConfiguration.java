package io.github.kolomyychenkoai.allure.spring.data;

import io.github.kolomyychenkoai.allure.spring.data.internal.AllureRepositoryAspect;
import io.github.kolomyychenkoai.allure.spring.internal.ActivationDiagnostics;
import io.github.kolomyychenkoai.allure.spring.internal.AllureInstrumentationLogger;
import org.springframework.aop.config.AopConfigUtils;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
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
 * Первая редакция вешала {@code @EnableAspectJAutoProxy}, и у потребителя, отказавшегося от
 * авто-проксирования, бин {@code internalAutoProxyCreator} менялся с
 * {@code InfrastructureAdvisorAutoProxyCreator} (его ставит {@code @EnableTransactionManagement})
 * на {@code AnnotationAwareAspectJAutoProxyCreator}: проксировалось больше бинов, чем он
 * разрешал, оживали его спящие аспекты, а инъекция по конкретному классу переставала
 * собираться. Issue #70.
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
 * но окажется на верхнем уровне теста, а не внутри шага репозитория. Об этом говорит
 * {@link #allureRepositoryStepsNotice()}.
 */
@AutoConfiguration(after = AopAutoConfiguration.class)
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
     * {@code @ConditionalOnBean} (срез на момент разбора), ни побочный эффект в конструкторе
     * конфигурации (не выполняется под {@code spring.main.lazy-initialization=true}) этого не дают;
     * обе формы были замерены и обе промахивались.
     */
    @Bean
    static BeanDefinitionRegistryPostProcessor allureRepositoryAspectRegistrar() {
        return new BeanDefinitionRegistryPostProcessor() {

            @Override
            public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) {
                try {
                    // Два РАЗНЫХ вопроса, и путать их нельзя. Регистрировать аспект — вопрос
                    // про создателя прокси: без репозиториев он просто ничего не поймает, вреда
                    // от него нет. Говорить новость — вопрос про потерю: без репозиториев терять
                    // нечего, и предупреждение было бы шумом, который перестают читать.
                    if (hasAspectJProxyCreator(registry)) {
                        registry.registerBeanDefinition(ASPECT_BEAN_NAME,
                                new RootBeanDefinition(AllureRepositoryAspect.class));
                    } else if (registry instanceof ListableBeanFactory beans && hasRepositoryBeans(beans)) {
                        ActivationDiagnostics.noteOnce("DbRepository", NOTICE);
                    }
                } catch (Throwable diagnosticIsNotWorthATest) {
                    // Мы внутри refresh чужого контекста: уронить его из-за раздела отчёта нельзя.
                    AllureInstrumentationLogger.warn("DbRepository", diagnosticIsNotWorthATest);
                }
            }

            @Override
            public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
                // всё сделано на этапе реестра
            }
        };
    }

    /** Стоит ли под штатным именем создатель прокси, который смотрит {@code @Aspect}-бины. */
    private static boolean hasAspectJProxyCreator(BeanDefinitionRegistry registry) {
        if (!registry.containsBeanDefinition(AopConfigUtils.AUTO_PROXY_CREATOR_BEAN_NAME)) {
            return false;
        }
        String type = registry.getBeanDefinition(AopConfigUtils.AUTO_PROXY_CREATOR_BEAN_NAME)
                .getBeanClassName();
        return ASPECTJ_PROXY_CREATOR.equals(type);
    }

    /**
     * Есть ли у потребителя хоть один репозиторий. Тип резолвим ПО ИМЕНИ: spring-data нет в
     * compile-classpath библиотеки (по той же причине поинткат аспекта задан строкой), а сюда
     * мы попадаем только после {@code @ConditionalOnClass}, то есть класс на месте.
     * При любой неожиданности отвечаем «нет» — молчание дешевле ложного предупреждения.
     */
    /**
     * Есть ли у потребителя хоть один репозиторий. Типы резолвим ПО ИМЕНИ: spring-data нет в
     * compile-classpath библиотеки (по той же причине поинткат аспекта задан строкой).
     * <p>
     * ⚠️ Спрашиваем ФАБРИКУ ({@code RepositoryFactoryBeanSupport}), а не сам {@code Repository}.
     * В фазе пост-процессора репозиторий Spring Data — это ещё определение
     * {@code JpaRepositoryFactoryBean} с пустым {@code factoryBeanObjectType}: тип продукта без
     * создания фабрики не определяется, и по {@code Repository} ответ пустой. Замерено на
     * настоящем приложении — из-за этого предупреждение молчало у ВСЕХ потребителей Spring Data,
     * а тесты были зелёные, потому что фикстура заводила репозиторий обычным бином.
     * <p>
     * Второй запрос — по {@code Repository} — нужен для самописных DAO с маркером: они обычные
     * бины, фабрики у них нет, но раздел они бы дали.
     * <p>
     * {@code allowEagerInit} везде {@code false}: диагностика не поднимает чужие бины.
     */
    private static boolean hasRepositoryBeans(ListableBeanFactory beanFactory) {
        return hasBeansOfType(beanFactory, "org.springframework.data.repository.core.support.RepositoryFactoryBeanSupport")
                || hasBeansOfType(beanFactory, "org.springframework.data.repository.Repository");
    }

    private static boolean hasBeansOfType(ListableBeanFactory beanFactory, String typeName) {
        try {
            Class<?> type = ClassUtils.forName(typeName, AllureDataJpaAutoConfiguration.class.getClassLoader());
            return beanFactory.getBeanNamesForType(type, true, false).length > 0;
        } catch (Throwable notResolvable) {
            return false; // молчание дешевле ложного предупреждения
        }
    }
}
