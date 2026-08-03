package io.github.kolomyychenkoai.allure.spring.kafka;

import io.github.kolomyychenkoai.allure.spring.internal.AllureInstrumentation;
import io.github.kolomyychenkoai.allure.spring.internal.ByteBuddyPresence;
import io.github.kolomyychenkoai.allure.spring.kafka.internal.AllureKafkaConsumerInstrumentation;
import io.github.kolomyychenkoai.allure.spring.kafka.internal.AllureKafkaProducerInstrumentation;
import org.springframework.core.Ordered;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.TestExecutionListener;

/**
 * Ставит байткод-инструментирование Kafka (consumer poll + producer send) один раз
 * перед первым тест-классом. Регистрируется через {@code META-INF/spring.factories}.
 * <p>
 * Перед установкой проверяется {@link ByteBuddyPresence#available()} — если byte-buddy
 * нет на classpath, листенер тихо ничего не ставит (типы matcher/advice не линкуются).
 * Если kafka-clients нет — матчер по имени класса просто ничего не находит (no-op).
 * <p>
 * Кроме установки, проигрывает буфер consumer-записей, принятых на потоке
 * {@code @KafkaListener}: чистит его в {@code beforeTestMethod}, выкладывает шагами в
 * {@code afterTestMethod} — на тест-потоке, где есть активный Allure-кейс
 * (см. {@link AllureKafkaConsumerInstrumentation}). flush/clear безопасны и без kafka на
 * classpath — буфер тогда просто пуст.
 */
public class AllureKafkaListener implements TestExecutionListener, Ordered {

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public void beforeTestClass(TestContext testContext) {
        if (!ByteBuddyPresence.available()) {
            return;
        }
        AllureKafkaConsumerInstrumentation.install();
        AllureKafkaProducerInstrumentation.install();
    }

    @Override
    public void beforeTestMethod(TestContext testContext) {
        if (!ByteBuddyPresence.available()) {
            return; // см. комментарий в afterTestMethod
        }
        AllureKafkaConsumerInstrumentation.clear(); // окно привязки = текущий тест-метод
    }

    @Override
    public void afterTestMethod(TestContext testContext) {
        // Гейт нужен и здесь, а не только на установке: буфер живёт ВНУТРИ класса-инструментатора,
        // а тот держит типы byte-buddy — без библиотеки обращение к нему даёт NoClassDefFoundError
        // прямо из хука и роняет тест-класс потребителя. Смысла в вызове всё равно нет: без
        // инструментации в буфер никто ничего не клал. Закреплено unit/ListenerDegradationTest.
        if (!ByteBuddyPresence.available()) {
            return;
        }
        AllureKafkaConsumerInstrumentation.flush(); // проиграть записи @KafkaListener на тест-потоке
    }
}
