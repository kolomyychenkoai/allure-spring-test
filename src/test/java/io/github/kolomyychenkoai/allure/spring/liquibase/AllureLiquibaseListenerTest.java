package io.github.kolomyychenkoai.allure.spring.liquibase;

import liquibase.integration.spring.SpringLiquibase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Гейт «снимок стартовой схемы — только там, где БД реально поднималась»: снимок рисуется, лишь
 * если в контексте теста есть бин {@link SpringLiquibase}. Иначе JVM-широкий снимок протекал бы в
 * тесты без Liquibase (Kafka/HTTP-only). Проверяем предикат детерминированно, без движка.
 */
class AllureLiquibaseListenerTest {

    @Test
    @DisplayName("контекст с бином SpringLiquibase → миграции реально применялись")
    void detectsLiquibaseBean() {
        StaticApplicationContext ctx = new StaticApplicationContext();
        ctx.refresh();
        assertThat(AllureLiquibaseListener.contextRanLiquibase(ctx)).isFalse();

        ctx.getBeanFactory().registerSingleton("liquibase", new SpringLiquibase());
        assertThat(AllureLiquibaseListener.contextRanLiquibase(ctx)).isTrue();
    }

    @Test
    @DisplayName("null-контекст → снимок не рисуем (не падаем)")
    void nullContextIsFalse() {
        assertThat(AllureLiquibaseListener.contextRanLiquibase(null)).isFalse();
    }
}
