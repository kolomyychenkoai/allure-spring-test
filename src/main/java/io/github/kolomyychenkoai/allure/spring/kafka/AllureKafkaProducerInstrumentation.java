package io.github.kolomyychenkoai.allure.spring.kafka;

import io.github.kolomyychenkoai.allure.spring.internal.AllureAdviceSupport;
import io.github.kolomyychenkoai.allure.spring.internal.AllureInstrumentation;
import io.github.kolomyychenkoai.allure.spring.internal.AllureInstrumentationLogger;
import io.qameta.allure.Allure;
import io.qameta.allure.AllureLifecycle;
import io.qameta.allure.model.Status;
import io.qameta.allure.model.StatusDetails;
import io.qameta.allure.model.StepResult;
import net.bytebuddy.asm.Advice;
import org.apache.kafka.clients.producer.ProducerRecord;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

/**
 * ByteBuddy-инструментирование Kafka producer: при {@code KafkaProducer.send(record, callback)}
 * в отчёт пишется шаг «Kafka: отправлено → topic [key]» с вложением «Отправленное сообщение».
 * Матчим именно 2-арг send (1-арг send внутри делегирует в него) — без двойного шага.
 * Шаг ставится на ВЫХОДЕ из send: если send бросил синхронно (сериализация, буфер) — шаг
 * FAILED с причиной, иначе PASSED. Логирование — при активном тест-кейсе, всё в try/catch.
 * Установка идемпотентна (CAS-гард {@code INSTALLED}, потокобезопасно) — один раз на JVM.
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

    /** Успешная отправка (для тестов/совместимости). */
    public static void onSend(ProducerRecord<?, ?> record) {
        onSend(record, null);
    }

    /** Логика логирования (вынесена из advice, чтобы тестировать без брокера). */
    public static void onSend(ProducerRecord<?, ?> record, Throwable thrown) {
        try {
            if (record == null || !Allure.getLifecycle().getCurrentTestCase().isPresent()) {
                return;
            }
            String stepName = "Kafka: отправлено → " + record.topic()
                    + (record.key() != null ? " [" + AllureAdviceSupport.safe(record.key()) + "]" : "");
            StringBuilder sb = new StringBuilder()
                    .append("Topic: ").append(record.topic())
                    .append("\nKey: ").append(AllureAdviceSupport.safe(record.key()))
                    .append("\nValue: ").append(AllureAdviceSupport.safe(record.value()));
            if (record.partition() != null) {
                sb.append("\nPartition: ").append(record.partition());
            }
            final String body = sb.toString();
            // ручной lifecycle: статус шага зависит от исхода send (Allure.step(name, runnable)
            // всегда ставит PASSED после лямбды, поэтому FAILED так не выставить)
            AllureLifecycle lifecycle = Allure.getLifecycle();
            String stepId = UUID.randomUUID().toString();
            StepResult stepResult = new StepResult().setName(stepName)
                    .setStatus(thrown == null ? Status.PASSED : Status.FAILED);
            if (thrown != null) {
                stepResult.setStatusDetails(new StatusDetails().setMessage(thrown.getMessage()));
            }
            boolean started = false;
            try {
                lifecycle.startStep(stepId, stepResult);
                started = true;
                Allure.addAttachment("Отправленное сообщение", "text/plain", body);
            } finally {
                if (started) {
                    lifecycle.stopStep(stepId);
                }
            }
        } catch (Throwable t) {
            AllureInstrumentationLogger.warn("KafkaSend", t);
        }
    }

    public static class SendAdvice {
        @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
        public static void onExit(@Advice.Argument(0) ProducerRecord<?, ?> record,
                                  @Advice.Thrown Throwable thrown) {
            onSend(record, thrown);
        }
    }
}
