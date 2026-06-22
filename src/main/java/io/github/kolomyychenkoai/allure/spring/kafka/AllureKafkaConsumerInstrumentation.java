package io.github.kolomyychenkoai.allure.spring.kafka;

import io.github.kolomyychenkoai.allure.spring.internal.AllureInstrumentation;
import io.github.kolomyychenkoai.allure.spring.internal.AllureInstrumentationLogger;
import io.qameta.allure.Allure;
import net.bytebuddy.asm.Advice;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

/**
 * ByteBuddy-инструментирование Kafka consumer: после {@code KafkaConsumer.poll(Duration)},
 * вернувшего сообщения, в отчёт пишется шаг «Kafka: получено N сообщ.» с вложением
 * «Принятые сообщения» (topic/partition/offset/key/value). Без кода в тестах.
 * <p>
 * Логирование — только при активном Allure тест-кейсе (poll часто идёт на потоке
 * слушателя, где тест-кейса нет — там молчим); всё в try/catch. Ставится один раз на JVM.
 * <p>
 * Перехватывается {@code poll(Duration)} (современный API). Deprecated {@code poll(long)}
 * намеренно не матчим — современный код его не использует.
 */
public final class AllureKafkaConsumerInstrumentation {

    private static final AtomicBoolean INSTALLED = new AtomicBoolean(false);

    private AllureKafkaConsumerInstrumentation() {
    }

    public static void install() {
        if (!INSTALLED.compareAndSet(false, true)) {
            return;
        }
        AllureInstrumentation.retransform(
                named("org.apache.kafka.clients.consumer.KafkaConsumer"),
                (builder, type, cl, module, pd) -> builder.visit(Advice.to(PollAdvice.class)
                        .on(named("poll").and(takesArgument(0, Duration.class)))));
    }

    /** Логика логирования (вынесена из advice, чтобы тестировать без брокера). */
    public static void onPoll(ConsumerRecords<?, ?> records) {
        try {
            if (records == null || records.isEmpty()
                    || !Allure.getLifecycle().getCurrentTestCase().isPresent()) {
                return;
            }
            StringBuilder sb = new StringBuilder();
            int i = 0;
            for (ConsumerRecord<?, ?> record : records) {
                if (i++ > 0) {
                    sb.append("\n---\n");
                }
                sb.append("Topic: ").append(record.topic())
                        .append("\nPartition: ").append(record.partition())
                        .append("\nOffset: ").append(record.offset())
                        .append("\nKey: ").append(record.key())
                        .append("\nValue: ").append(record.value());
            }
            final String body = sb.toString();
            Allure.step("Kafka: получено " + records.count() + " сообщ.", step -> {
                Allure.addAttachment("Принятые сообщения", "text/plain", body);
            });
        } catch (Throwable t) {
            AllureInstrumentationLogger.warn("KafkaPoll", t);
        }
    }

    public static class PollAdvice {
        @Advice.OnMethodExit(suppress = Throwable.class)
        public static void onExit(@Advice.Return ConsumerRecords<?, ?> records) {
            onPoll(records);
        }
    }
}
