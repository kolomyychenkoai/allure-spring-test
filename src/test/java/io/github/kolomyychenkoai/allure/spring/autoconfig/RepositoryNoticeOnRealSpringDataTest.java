package io.github.kolomyychenkoai.allure.spring.autoconfig;

import io.github.kolomyychenkoai.allure.spring.data.internal.AllureRepositoryAspect;
import io.github.kolomyychenkoai.allure.spring.internal.ActivationDiagnostics;
import io.github.kolomyychenkoai.allure.spring.internal.AllureInstrumentationLogger;
import io.github.kolomyychenkoai.allure.spring.support.JpaTestApp;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ЧЕРНОВИК (не коммитить). Ось блокера круга 2: новость про исчезнувший раздел БД должна
 * дойти до потребителя НАСТОЯЩЕЙ Spring Data, а не только до фикстуры уровня A.
 * <p>
 * Почему уровень B обязателен. Гейт новости спрашивает у фабрики бинов, есть ли у потребителя
 * репозитории. В фазе {@code BeanDefinitionRegistryPostProcessor} репозиторий Spring Data —
 * это ещё определение {@code JpaRepositoryFactoryBean} с неизвестным типом продукта, поэтому
 * запрос по маркеру {@code Repository} отвечает пусто. Собрать такую форму руками уровень A
 * пытался трижды и не воспроизвёл: любая рукотворная фикстура проверяет НАШЕ представление
 * о том, как Spring Data заводит бин, а блокер жил ровно в расхождении представления с фактом.
 * Здесь бины заводит сама Spring Data.
 * <p>
 * Мутация: в {@code AllureDataJpaAutoConfiguration#hasRepositoryBeans} убрать первый запрос
 * (по {@code RepositoryFactoryBeanSupport}), оставив только {@code Repository} → строк
 * предупреждения станет 0 → RED.
 */
class RepositoryNoticeOnRealSpringDataTest {

    @Test
    @DisplayName("настоящая Spring Data без AspectJ-создателя: про потерянный раздел БД сказано")
    void noticeReachesConsumerOfRealSpringData() {
        ActivationDiagnostics.forgetForTests();

        List<LogRecord> said = logWhile(() -> {
            try (ConfigurableApplicationContext ctx = new SpringApplicationBuilder(JpaTestApp.class)
                    .web(WebApplicationType.NONE)
                    .bannerMode(org.springframework.boot.Banner.Mode.OFF)
                    .properties(
                            "spring.aop.auto=false",
                            // Своя база: контекст поднимается вне тест-кэша, и делить схему
                            // с остальными тестами ему незачем.
                            "spring.datasource.url=jdbc:h2:mem:notice-axis;DB_CLOSE_DELAY=-1",
                            "spring.jpa.hibernate.ddl-auto=create-drop")
                    .run()) {

                // ЯКОРЬ. Без него ассерт про строку прошёл бы и на контексте, где аспект
                // на месте: тогда новость была бы просто ложью, а не сигналом.
                assertThat(ctx.getBeanNamesForType(AllureRepositoryAspect.class))
                        .as("авто-проксирование выключено, а аспект всё-таки зарегистрирован")
                        .isEmpty();

                // Второй якорь: репозитории у потребителя ЕСТЬ. Иначе молчание было бы
                // правильным поведением («терять нечего»), и тест сторожил бы пустоту.
                assertThat(ctx.getBeanNamesForType(
                        org.springframework.data.repository.Repository.class))
                        .as("в фикстуре нет ни одного репозитория — ось не воспроизведена")
                        .isNotEmpty();
            }
        });

        assertThat(said.stream()
                .filter(r -> r.getMessage().contains("AspectJ-создателя прокси"))
                .count())
                .as("потребитель настоящей Spring Data не узнал, что раздел «DB Repo.method» "
                        + "из отчёта исчез — ровно тот блокер, из-за которого новость молчала у всех")
                .isEqualTo(1);
    }

    /** Что библиотека сказала в свой логгер, пока поднимался контекст. */
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
}
