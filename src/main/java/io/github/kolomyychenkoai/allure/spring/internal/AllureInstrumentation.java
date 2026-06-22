package io.github.kolomyychenkoai.allure.spring.internal;

import net.bytebuddy.agent.ByteBuddyAgent;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

import java.lang.instrument.Instrumentation;

/**
 * Общая база байткод-инструментирования для всех модулей (Spring-ассерты, Hamcrest,
 * AssertJ, Kafka, WireMock-verify…). Один {@code ByteBuddyAgent.install()} на JVM
 * (идемпотентен, переиспользует уже привязанный агент — совместимо с Mockito),
 * узкий радиус (только заданный тип), {@code disableClassFormatChanges} — безопасно
 * для соседних агентов. Сбой агента логируется и НЕ роняет тест.
 */
public final class AllureInstrumentation {

    private AllureInstrumentation() {
    }

    /** Ретрансформировать тип(ы) под matcher переданным трансформером (advice). */
    public static void retransform(ElementMatcher<? super TypeDescription> typeMatcher,
                                   AgentBuilder.Transformer transformer) {
        try {
            Instrumentation instrumentation = ByteBuddyAgent.install();
            new AgentBuilder.Default()
                    .disableClassFormatChanges()
                    .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                    .type(typeMatcher)
                    .transform(transformer)
                    .installOn(instrumentation);
        } catch (Throwable t) {
            AllureInstrumentationLogger.warn("Instrumentation", t);
        }
    }
}
