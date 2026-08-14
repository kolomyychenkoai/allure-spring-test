package io.github.kolomyychenkoai.allure.spring.data;

import io.github.kolomyychenkoai.allure.spring.data.internal.AllureRepositoryAspect;
import io.github.kolomyychenkoai.allure.spring.internal.ActivationDiagnostics;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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
 * Гейт стоит на ФАКТЕ, а не на намерении: {@code @ConditionalOnBean} по AspectJ-совместимому
 * создателю прокси. Свойство {@code spring.aop.auto} читать было бы недостаточно — отказаться
 * от авто-проксирования можно ещё как минимум двумя способами (исключить
 * {@link AopAutoConfiguration}, написать значение свойства не словом {@code false}), и каждый
 * из них дал бы ту же подмену. Спрашивая контекст, мы видим факт вместо догадки.
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
        // Этот тип НАЗЫВАЕТ поинткат аспекта. AspectJ резолвит имя при разборе выражения, и без
        // класса контекст потребителя падал бы IllegalArgumentException прямо в refresh — вместо
        // тихой деградации, которую даёт условие. Потребитель без spring-tx теоретически возможен
        // (в spring-data-commons зависимость optional), но репозиториев у него нет: их фабрика
        // ставит TransactionalProxy на каждый прокси безусловно.
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

    private static final String NOTICE =
            "AspectJ-создателя прокси в контексте нет (авто-проксирование выключено или заменено "
                    + "инфраструктурным), и мы его НЕ включаем: подмена internalAutoProxyCreator "
                    + "меняла бы проксирование ваших бинов (issue #70). Следствие: шагов "
                    + "«DB Repo.method» в отчёте не будет — их пишет Spring-аспект. Реальный SQL "
                    + "остаётся, но окажется на верхнем уровне теста, а не внутри шага репозитория. "
                    + "Нужны шаги репозиториев — включите авто-проксирование (spring.aop.auto по "
                    + "умолчанию включено) либо заведите @EnableAspectJAutoProxy у себя. "
                    + "Заглушить эту строку: -Dallure.spring.diagnostics=off";

    /**
     * Аспект — только там, где создатель прокси для него уже есть. Своего не заводим.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnBean(type = ASPECTJ_PROXY_CREATOR)
    public static class RepositoryAspectConfiguration {

        @Bean
        public AllureRepositoryAspect allureRepositoryAspect() {
            return new AllureRepositoryAspect();
        }
    }

    /**
     * Есть ли у потребителя хоть один репозиторий. Тип резолвим ПО ИМЕНИ: spring-data нет в
     * compile-classpath библиотеки (по той же причине поинткат аспекта задан строкой), а сюда
     * мы попадаем только после {@code @ConditionalOnClass}, то есть класс на месте.
     * При любой неожиданности отвечаем «нет» — молчание дешевле ложного предупреждения.
     */
    private static boolean hasRepositoryBeans(ListableBeanFactory beanFactory) {
        try {
            Class<?> repository = ClassUtils.forName(
                    "org.springframework.data.repository.Repository",
                    AllureDataJpaAutoConfiguration.class.getClassLoader());
            return beanFactory.getBeanNamesForType(repository, false, false).length > 0;
        } catch (Throwable notResolvable) {
            return false;
        }
    }

    /**
     * Сказать вслух, что раздела БД не будет.
     * <p>
     * {@link BeanFactoryPostProcessor}, а не побочный эффект в конструкторе конфигурации, по трём
     * причинам, и каждая — замеренный промах предыдущей редакции:
     * <ul>
     *   <li>BFPP выполняются всегда, а конфигурация без {@code @Bean}-методов под
     *       {@code spring.main.lazy-initialization=true} не создаётся вовсе — новость молчала
     *       ровно там, где потеря и так тихая;</li>
     *   <li>здесь видно ФАКТ (зарегистрирован ли аспект), а условие на значении свойства
     *       промахивалось мимо написаний {@code off}/{@code no}/{@code 0};</li>
     *   <li>спрашиваем и про репозитории: без них терять нечего, а предупреждение в сборке,
     *       где оно ни на что не влияет, — ровно тот шум, который перестают читать.</li>
     * </ul>
     * Определения читаем без создания бинов ({@code false, false}) — диагностика не имеет права
     * поднимать чужие бины раньше времени.
     */
    @Bean
    static BeanFactoryPostProcessor allureRepositoryStepsNotice() {
        return beanFactory -> {
            boolean aspectRegistered = beanFactory.getBeanNamesForType(
                    AllureRepositoryAspect.class, false, false).length > 0;
            boolean hasRepositories = hasRepositoryBeans(beanFactory);
            if (!aspectRegistered && hasRepositories) {
                ActivationDiagnostics.noteOnce("DbRepository", NOTICE);
            }
        };
    }
}
