package io.github.kolomyychenkoai.allure.spring.kafka.internal;

import io.github.kolomyychenkoai.allure.spring.internal.AllureAdviceSupport;
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
 * <p>
 * ВАЖНО про семантику: шаг отражает, что {@code send} ВЫЗВАН (запись поставлена в очередь
 * продюсера), а НЕ подтверждённую доставку. Синхронный сбой ({@code send} бросил —
 * сериализация, переполнение буфера) шага не создаёт (падение Allure покажет на уровне
 * теста). АСИНХРОННЫЙ сбой брокера приходит позже, через {@code Future}/callback на сетевом
 * потоке продюсера, где активного тест-кейса уже нет — отдельным шагом он НЕ отражается;
 * его увидит вызывающий на {@code future.get()} (тогда тест и упадёт). Хук в callback тут
 * не делаем намеренно: шаг писался бы на чужом потоке без тест-кейса и просто терялся.
 * <p>
 * Логирование — при активном тест-кейсе, всё в try/catch.
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
            // упавший send не логируем — падение покажет Allure (тест падает); шаг только для успешной отправки
            if (thrown != null || record == null || !Allure.getLifecycle().getCurrentTestCase().isPresent()) {
                return;
            }
            String stepName = "Kafka: отправлено → " + record.topic()
                    + (record.key() != null ? " [" + AllureAdviceSupport.safe(record.key()) + "]" : "");
            // метаданные (topic/key/partition) — text/plain; это ВЛОЖЕНИЕ, поэтому safeValue
            StringBuilder sb = new StringBuilder()
                    .append("Topic: ").append(record.topic())
                    .append("\nKey: ").append(AllureAdviceSupport.safeValue(record.key()));
            if (record.partition() != null) {
                sb.append("\nPartition: ").append(record.partition());
            }
            final String meta = sb.toString();
            // значение — отдельным вложением (application/json, если похоже на JSON), БЕЗ 500-обрезки
            // тело value БЕЗ обрезки (обрезка safe() остаётся только в имени шага), но НЕ-бросающий
            // рендер (String.valueOf уронил бы весь шаг, если toString() кинет) — attach развернёт JSON
            final String value = record.value() == null ? null : AllureAdviceSupport.render(record.value());
            Allure.step(stepName, step -> {
                AllureAdviceSupport.attach("Отправленное сообщение", meta, "Значение сообщения", value);
            });
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
