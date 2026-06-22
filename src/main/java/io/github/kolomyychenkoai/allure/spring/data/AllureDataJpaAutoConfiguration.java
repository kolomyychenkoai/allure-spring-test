package io.github.kolomyychenkoai.allure.spring.data;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.EnableAspectJAutoProxy;

/**
 * Авто-активация логирования вызовов Spring Data репозиториев. Регистрирует
 * {@link AllureRepositoryAspect} и включает AspectJ-автопрокси. Активируется сама,
 * если на classpath есть AspectJ и Spring Data Repository — потребителю код не нужен.
 * Регистрируется через {@code META-INF/spring/...AutoConfiguration.imports}.
 */
@AutoConfiguration
@EnableAspectJAutoProxy(proxyTargetClass = true) // в синхрон с дефолтом Spring Boot — не понижаем режим прокси потребителя
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
