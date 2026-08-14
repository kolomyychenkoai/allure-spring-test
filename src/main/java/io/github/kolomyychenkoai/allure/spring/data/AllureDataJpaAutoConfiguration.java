package io.github.kolomyychenkoai.allure.spring.data;

import io.github.kolomyychenkoai.allure.spring.data.internal.AllureRepositoryAspect;
import io.github.kolomyychenkoai.allure.spring.internal.ActivationDiagnostics;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;

/**
 * Авто-активация логирования вызовов Spring Data репозиториев. Регистрирует
 * {@link AllureRepositoryAspect} и включает AspectJ-автопрокси. Активируется сама,
 * если на classpath есть AspectJ, Spring Data Repository и spring-tx — потребителю код не нужен.
 * Регистрируется через {@code META-INF/spring/...AutoConfiguration.imports}.
 * <p>
 * <b>Авто-проксирование — решение потребителя, а не наше.</b> Гейт по {@code spring.aop.auto} —
 * тот же, которым гейтится Boot-овская {@code AopAutoConfiguration}. Первая редакция его не
 * читала, и на {@code spring.aop.auto=false} мы возвращали выключенное потребителем
 * проксирование. Замерено на живом приложении: бин {@code internalAutoProxyCreator} менялся с
 * {@code InfrastructureAdvisorAutoProxyCreator} (его ставит {@code @EnableTransactionManagement})
 * на {@code AnnotationAwareAspectJAutoProxyCreator} — проксировалось больше бинов, чем потребитель
 * разрешал, оживали его спящие аспекты, а инъекция по конкретному классу переставала собираться
 * ({@code BeanNotOfRequiredTypeException}). Issue #70.
 * <p>
 * <b>Цена честного гейта названа вслух.</b> При {@code spring.aop.auto=false} шагов
 * «DB Repo.method» в отчёте НЕ БУДЕТ. Реальный SQL остаётся — его пишет отдельный канал
 * ({@link AllureDataSourceAutoConfiguration}, свой {@code ProxyFactory} без автопроксирования), —
 * но окажется на верхнем уровне теста, а не внутри шага репозитория. Об этом говорится ОДИН раз
 * на прогон: {@link ActivationDiagnostics#noteOnce}.
 * <p>
 * {@code @EnableAspectJAutoProxy} оставлена БЕЗ {@code proxyTargetClass}: {@code AspectJAutoProxyRegistrar}
 * форсирует class-proxying только при {@code proxyTargetClass=true}, а {@code AopConfigUtils} этот
 * флаг лишь взводит и никогда не снимает. Значит режим прокси чужих бинов остаётся тем, который
 * выставил Boot по {@code spring.aop.proxy-target-class}, независимо от порядка автоконфигураций.
 * <p>
 * ⚠️ {@code @ConditionalOnProperty}, а не {@code @ConditionalOnBooleanProperty}: вторая появилась
 * только в Boot 3.4, а нижнюю границу поддержки в этом проекте уже двигали. Первая лежит в том же
 * пакете во всех версиях и не помечена deprecated.
 */
@AutoConfiguration
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
     * Что теряет потребитель, выключивший авто-проксирование. Константа, а не литерал в вызове:
     * этот же текст читает тест, закрепляющий однократность.
     */
    static final String AOP_DISABLED_NOTICE =
            "spring.aop.auto=false — авто-проксирование выключено вами, и мы его НЕ включаем: "
                    + "подмена internalAutoProxyCreator меняла бы проксирование ваших бинов (issue #70). "
                    + "Следствие: шагов «DB Repo.method» в отчёте не будет — их пишет Spring-аспект. "
                    + "Реальный SQL остаётся, но окажется на верхнем уровне теста, а не внутри шага "
                    + "репозитория. Нужны шаги репозиториев — уберите spring.aop.auto=false. "
                    + "Заглушить эту строку: -Dallure.spring.diagnostics=off";

    /**
     * Аспект и автопрокси — только там, где потребитель авто-проксирование не выключал.
     * <p>
     * Вложенный {@code @Configuration}, а не условие на внешнем классе: так же устроена
     * {@code AopAutoConfiguration} у Boot, и так рядом помещается вторая ветка с новостью.
     */
    @Configuration(proxyBeanMethods = false)
    @EnableAspectJAutoProxy
    @ConditionalOnProperty(prefix = "spring.aop", name = "auto", havingValue = "true", matchIfMissing = true)
    public static class RepositoryAspectConfiguration {

        @Bean
        public AllureRepositoryAspect allureRepositoryAspect() {
            return new AllureRepositoryAspect();
        }
    }

    /**
     * Вторая ветка того же свойства: раздел БД не соберётся — сказать об этом вслух.
     * <p>
     * Отдельный вложенный класс, а не второй автоконфиг-файл: файл потребовал бы строки в
     * {@code AutoConfiguration.imports}, места в карте модулей документации и правки числа
     * автоконфигураций. Здесь — ноль изменений в инвентаре точек входа.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(prefix = "spring.aop", name = "auto", havingValue = "false")
    public static class AopDisabledNotice {

        public AopDisabledNotice() {
            ActivationDiagnostics.noteOnce("DbRepository", AOP_DISABLED_NOTICE);
        }
    }
}
