package io.github.kolomyychenkoai.allure.spring.kafka;

import io.github.kolomyychenkoai.allure.spring.internal.AllureInstrumentation;
import io.github.kolomyychenkoai.allure.spring.internal.AllureInstrumentationLogger;
import io.qameta.allure.Allure;
import net.bytebuddy.asm.Advice;
import org.apache.kafka.clients.producer.ProducerRecord;

import java.util.concurrent.atomic.AtomicBoolean;

import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

/**
 * ByteBuddy-инструментирование Kafka producer: при {@code KafkaProducer.send(record, callback)}
 * в отчёт пишется шаг «Kafka: отправлено → topic [key]» с вложением «Отправленное сообщение».
 * Матчим именно 2-арг send (1-арг send внутри делегирует в него) — без двойного шага.
 * Логирование — при активном тест-кейсе, всё в try/catch. Ставится один раз на JVM.
 */
public final class AllureKafkaProducerInstrumentation {

    private static final AtomicBoolean INSTALLED = new AtomicBoolean(false);

    private AllureKafkaProducerInstrumentation() {
    }

    public static void install() {
        if (!INSTALLED.compareAndSet(false, true)) {
            return;
        }
        AllureInstrumentation.retransform(
                named("org.apache.kafka.clients.producer.KafkaProducer"),
                (builder, type, cl, module, pd) -> builder.visit(Advice.to(SendAdvice.class)
                        .on(named("send").and(takesArgument(0, ProducerRecord.class)).and(takesArguments(2)))));
    }

    /** Логика логирования (вынесена из advice, чтобы тестировать без брокера). */
    public static void onSend(ProducerRecord<?, ?> record) {
        try {
            if (record == null || !Allure.getLifecycle().getCurrentTestCase().isPresent()) {
                return;
            }
            String stepName = "Kafka: отправлено → " + record.topic()
                    + (record.key() != null ? " [" + record.key() + "]" : "");
            StringBuilder sb = new StringBuilder()
                    .append("Topic: ").append(record.topic())
                    .append("\nKey: ").append(record.key())
                    .append("\nValue: ").append(record.value());
            if (record.partition() != null) {
                sb.append("\nPartition: ").append(record.partition());
            }
            final String body = sb.toString();
            Allure.step(stepName, step -> {
                Allure.addAttachment("Отправленное сообщение", "text/plain", body);
            });
        } catch (Throwable t) {
            AllureInstrumentationLogger.warn("KafkaSend", t);
        }
    }

    public static class SendAdvice {
        @Advice.OnMethodEnter(suppress = Throwable.class)
        public static void onEnter(@Advice.Argument(0) ProducerRecord<?, ?> record) {
            onSend(record);
        }
    }
}
