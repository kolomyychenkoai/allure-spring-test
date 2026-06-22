package io.github.kolomyychenkoai.allure.spring.kafka;

import org.springframework.core.Ordered;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.TestExecutionListener;

/**
 * Ставит байткод-инструментирование Kafka (consumer poll + producer send) один раз
 * перед первым тест-классом. Регистрируется через {@code META-INF/spring.factories};
 * если ByteBuddy/Kafka нет на classpath, Spring пропустит листенер / матчер не сработает.
 */
public class AllureKafkaListener implements TestExecutionListener, Ordered {

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public void beforeTestClass(TestContext testContext) {
        AllureKafkaConsumerInstrumentation.install();
        AllureKafkaProducerInstrumentation.install();
    }
}
