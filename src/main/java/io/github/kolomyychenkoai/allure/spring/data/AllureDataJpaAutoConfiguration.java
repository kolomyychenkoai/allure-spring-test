package io.github.kolomyychenkoai.allure.spring.data;

import io.github.kolomyychenkoai.allure.spring.internal.AllureSpringSettings;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.EnableAspectJAutoProxy;

/**
 * Авто-активация логирования вызовов Spring Data репозиториев. Регистрирует
 * {@link AllureRepositoryAspect} и включает AspectJ-автопрокси. Активируется сама,
 * если на classpath есть AspectJ и Spring Data Repository — потребителю код не нужен.
 * Выключить — {@code allure.spring.data.enabled=false}.
 * Регистрируется через {@code META-INF/spring/...AutoConfiguration.imports}.
 * <p>
 * {@code @EnableAspectJAutoProxy} без {@code proxyTargetClass} — НЕ навязываем потребителю
 * CGLIB: репозитории и так интерфейсные прокси, а Spring Boot сам ставит нужный режим
 * (по умолчанию class-proxy). Так мы не меняем режим прокси чужих бинов.
 */
@AutoConfiguration
@EnableAspectJAutoProxy
@ConditionalOnProperty(name = AllureSpringSettings.DATA_ENABLED, matchIfMissing = true)
@ConditionalOnClass(name = {
        "org.aspectj.lang.ProceedingJoinPoint",
        "org.springframework.data.repository.Repository"
})
public class AllureDataJpaAutoConfiguration {

    @Bean
    public AllureRepositoryAspect allureRepositoryAspect() {
        return new AllureRepositoryAspect();
    }
}
