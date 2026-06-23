package io.github.kolomyychenkoai.allure.spring.internal;

import net.bytebuddy.agent.ByteBuddyAgent;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

import java.lang.instrument.Instrumentation;

/**
 * Общая база байткод-инструментирования для всех модулей (Spring-ассерты, Hamcrest,
 * AssertJ, Kafka, WireMock-verify…). Сам {@code ByteBuddyAgent.install()} идемпотентен
 * (переиспользует уже привязанный к JVM агент — совместимо с Mockito), радиус узкий
 * (только заданный тип), {@code disableClassFormatChanges} — безопасно для соседних
 * агентов. Сбой инструментирования логируется на WARNING и НЕ роняет тест.
 * <p>
 * <b>byte-buddy в scope {@code provided}</b> — у потребителя он обычно есть транзитивно
 * (mockito / spring-boot-starter-test). Если есть сомнение, что byte-buddy на classpath,
 * вызывающий модуль должен проверить {@link #available()} ПЕРЕД тем, как строить matcher
 * и transformer (их типы из byte-buddy, иначе упадёт линковка вызывающего класса).
 */
public final class AllureInstrumentation {

    private AllureInstrumentation() {
    }

    /**
     * Есть ли byte-buddy на classpath. Проверка по имени класса (без инициализации),
     * сама по себе типы byte-buddy не тянет — безопасно звать даже когда его нет.
     */
    public static boolean available() {
        try {
            Class.forName("net.bytebuddy.agent.ByteBuddyAgent", false,
                    AllureInstrumentation.class.getClassLoader());
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Ретрансформировать тип(ы) под {@code typeMatcher} переданным {@code transformer}
     * (advice). Сбой ловится и логируется на WARNING — тест не затрагивается.
     * <p>
     * <b>НЕ идемпотентен:</b> каждый вызов регистрирует НОВЫЙ {@code ClassFileTransformer}
     * в {@link Instrumentation} на весь срок жизни JVM и заново ретрансформирует
     * подходящие классы. Вызывающий ОБЯЗАН гарантировать однократность установки
     * (потокобезопасно, напр. {@code AtomicBoolean.compareAndSet} — как сделано во всех
     * модулях), иначе под параллельными тестами навесятся дубли трансформеров и шаги
     * в отчёте задвоятся.
     */
    public static void retransform(ElementMatcher<? super TypeDescription> typeMatcher,
                                   AgentBuilder.Transformer transformer) {
        try {
            Instrumentation instrumentation = ByteBuddyAgent.install();
            new AgentBuilder.Default()
                    .disableClassFormatChanges()
                    .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                    // Reiterating: повторно сканирует загруженные классы, пока набор не стабилизируется —
                    // ловит и УЖЕ загруженные классы (иерархию AssertJ, рано подтянутую Spring/surefire),
                    // и те, что подгружаются во время самой ретрансформации. Без этого методы из рано
                    // загруженных абстрактных классов (AbstractCharSequenceAssert.startsWith,
                    // AbstractIterableAssert.contains) оставались без advice и пропадали из отчёта.
                    // Стоимость — разовый обход загруженных классов на КАЖДУЮ установку модуля
                    // (один раз на JVM, на init листенера); в steady-state не пересканирует.
                    .with(AgentBuilder.RedefinitionStrategy.DiscoveryStrategy.Reiterating.INSTANCE)
                    .type(typeMatcher)
                    .transform(transformer)
                    .installOn(instrumentation);
        } catch (Throwable t) {
            AllureInstrumentationLogger.warn("Instrumentation", t);
        }
    }
}
