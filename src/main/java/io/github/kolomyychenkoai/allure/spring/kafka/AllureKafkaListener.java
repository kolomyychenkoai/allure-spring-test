package io.github.kolomyychenkoai.allure.spring.kafka;

import io.github.kolomyychenkoai.allure.spring.internal.AllureInstrumentation;
import org.springframework.core.Ordered;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.TestExecutionListener;

/**
 * Ставит байткод-инструментирование Kafka (consumer poll + producer send) один раз
 * перед первым тест-классом. Регистрируется через {@code META-INF/spring.factories}.
 * <p>
 * Перед установкой проверяется {@link AllureInstrumentation#available()} — если byte-buddy
 * нет на classpath, листенер тихо ничего не ставит (типы matcher/advice не линкуются).
 * Если kafka-clients нет — матчер по имени класса просто ничего не находит (no-op).
 */
public class AllureKafkaListener implements TestExecutionListener, Ordered {

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public void beforeTestClass(TestContext testContext) {
        if (!AllureInstrumentation.available()
) {
            return;
        }
        AllureKafkaConsumerInstrumentation.install();
        AllureKafkaProducerInstrumentation.install();
    }
}
