package io.github.kolomyychenkoai.allure.spring.kafka.internal;

import io.github.kolomyychenkoai.allure.spring.internal.AllureAdviceSupport;
import io.github.kolomyychenkoai.allure.spring.internal.AllureInstrumentation;
import io.github.kolomyychenkoai.allure.spring.internal.AllureInstrumentationLogger;
import io.qameta.allure.Allure;
import net.bytebuddy.asm.Advice;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;

import java.time.Duration;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

/**
 * ByteBuddy-инструментирование Kafka consumer: после {@code KafkaConsumer.poll(Duration)},
 * вернувшего сообщения, в отчёт пишется шаг «Kafka: получено N сообщ.» с вложением
 * «Принятые сообщения» (topic/partition/offset/key/value). Без кода в тестах.
 * <p>
 * ДВА пути логирования (poll идёт на разных потоках):
 * <ul>
 *   <li>РУЧНОЙ {@code poll} в теле теста — на тест-потоке с активным кейсом → шаг пишется
 *       СРАЗУ (виден в отчёте этого теста по ходу выполнения);</li>
 *   <li>{@code @KafkaListener} — контейнер-слушатель крутит {@code poll} на СВОЁМ потоке,
 *       где активного кейса нет → записи буферизуем и проигрываем на тест-потоке в
 *       {@code afterTestMethod} (см. {@code AllureKafkaListener}). Иначе самый частый
 *       паттерн приёма не давал бы ни одного consumer-шага.</li>
 * </ul>
 * Окно привязки буфера = выполнение тест-метода (буфер чистится в {@code beforeTestMethod}):
 * запись, принятая ВНЕ окна теста (между тестами / после того как тест перестал ждать),
 * не привязывается к нему. То же свойство, что у WireMock-листенера. Всё в try/catch.
 * <p>
 * Перехватывается {@code poll(Duration)} (современный API). Deprecated {@code poll(long)}
 * намеренно не матчим — современный код его не использует.
 * Установка идемпотентна (CAS-гард {@code INSTALLED}, потокобезопасно) — один раз на JVM.
 */
public final class AllureKafkaConsumerInstrumentation {

    private static final AtomicBoolean INSTALLED = new AtomicBoolean(false);

    // Записи, принятые на потоке контейнера (@KafkaListener) — ждут проигрывания на тест-потоке.
    // Рендерим в строку СРАЗУ при захвате (ConsumerRecords недолговечны), храним готовый текст.
    private static final Queue<Captured> BUFFER = new ConcurrentLinkedQueue<>();

    private record Captured(int count, String body) {
    }

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
            if (records == null || records.isEmpty()) {
                return;
            }
            Captured captured = render(records);
            if (Allure.getLifecycle().getCurrentTestCase().isPresent()) {
                emit(captured); // ручной poll на тест-потоке — пишем сразу
            } else {
                BUFFER.add(captured); // @KafkaListener на чужом потоке — проиграем в afterTestMethod
            }
        } catch (Throwable t) {
            AllureInstrumentationLogger.warn("KafkaPoll", t);
        }
    }

    /** Проигрывает буфер на ТЕКУЩЕМ (тест-)потоке. Зовётся из {@code AllureKafkaListener#afterTestMethod}. */
    public static void flush() {
        if (!Allure.getLifecycle().getCurrentTestCase().isPresent()) {
            BUFFER.clear(); // нет активного кейса — привязать не к чему, не копим между тестами
            return;
        }
        Captured captured;
        while ((captured = BUFFER.poll()) != null) {
            try {
                emit(captured);
            } catch (Throwable t) {
                AllureInstrumentationLogger.warn("KafkaPollFlush", t);
            }
        }
    }

    /** Чистит буфер между тестами (из {@code AllureKafkaListener#beforeTestMethod}). */
    public static void clear() {
        BUFFER.clear();
    }

    private static Captured render(ConsumerRecords<?, ?> records) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        for (ConsumerRecord<?, ?> record : records) {
            if (i++ > 0) {
                sb.append("\n---\n");
            }
            sb.append("Topic: ").append(record.topic())
                    .append("\nPartition: ").append(record.partition())
                    .append("\nOffset: ").append(record.offset())
                    .append("\nKey: ").append(AllureAdviceSupport.safe(record.key()))
                    .append("\nValue: ").append(AllureAdviceSupport.safe(record.value()));
        }
        return new Captured(records.count(), sb.toString());
    }

    private static void emit(Captured captured) {
        Allure.step("Kafka: получено " + captured.count() + " сообщ.", step -> {
            Allure.addAttachment("Принятые сообщения", "text/plain", captured.body());
        });
    }

    public static class PollAdvice {
        @Advice.OnMethodExit(suppress = Throwable.class)
        public static void onExit(@Advice.Return ConsumerRecords<?, ?> records) {
            onPoll(records);
        }
    }
}
