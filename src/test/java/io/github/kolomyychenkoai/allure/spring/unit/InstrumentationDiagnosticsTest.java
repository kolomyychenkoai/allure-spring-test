package io.github.kolomyychenkoai.allure.spring.unit;

import io.github.kolomyychenkoai.allure.spring.internal.AllureInstrumentation;
import io.github.kolomyychenkoai.allure.spring.internal.InstrumentationDiagnostics;
import net.bytebuddy.asm.Advice;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static net.bytebuddy.matcher.ElementMatchers.named;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Уровень A: диагностика байткод-инструментирования.
 * <p>
 * Главный тест здесь — НЕГАТИВНЫЙ ({@link #сломаннаяТрансформацияПопадаетВСчётчик()}): он
 * постоянно доказывает, что детектор не слепой. Позитивный тест сам по себе этого не даёт —
 * счётчик, который всегда показывает ноль, выглядит точно так же.
 */
class InstrumentationDiagnosticsTest {

    /** Мишень перехвата: только для этого теста, чтобы не трогать чужие типы. */
    public static class Probe {
        public String ping() {
            return "raw";
        }
    }

    /** Мишень для заведомо сломанной трансформации. */
    public static class NegativeProbe {
        public String ping() {
            return "raw";
        }
    }

    public static class RewriteAdvice {
        @Advice.OnMethodExit
        public static void exit(@Advice.Return(readOnly = false) String returned) {
            returned = "instrumented";
        }
    }

    @Test
    @DisplayName("успешная установка: агент привязан, типы трансформированы, сбоев не добавилось")
    void успешнаяУстановкаНеДаётСбоев() {
        int before = InstrumentationDiagnostics.failureCount();

        AllureInstrumentation.retransform(named(Probe.class.getName()),
                (builder, type, loader, module, pd) -> builder.visit(
                        Advice.to(RewriteAdvice.class).on(named("ping"))));

        // advice реально применился — не «агент установился», а «перехват работает»
        assertThat(new Probe().ping()).isEqualTo("instrumented");
        assertThat(InstrumentationDiagnostics.installed()).isTrue();
        assertThat(InstrumentationDiagnostics.transformedCount()).isPositive();
        assertThat(InstrumentationDiagnostics.failureCount()).isEqualTo(before);
    }

    @Test
    @DisplayName("сломанная трансформация: счётчик растёт, причина видна, вызывающий НЕ падает")
    void сломаннаяТрансформацияПопадаетВСчётчик() {
        int before = InstrumentationDiagnostics.failureCount();

        assertThatCode(() -> AllureInstrumentation.retransform(named(NegativeProbe.class.getName()),
                (builder, type, loader, module, pd) -> {
                    throw new IllegalStateException("мутация: трансформер сломан");
                }))
                .doesNotThrowAnyException();

        assertThat(InstrumentationDiagnostics.failureCount()).isGreaterThan(before);
        assertThat(InstrumentationDiagnostics.failures())
                .anyMatch(f -> f.contains("NegativeProbe") && f.contains("мутация: трансформер сломан"));
    }

    @Test
    @DisplayName("выборка сбоев наружу — копия (внутреннее состояние не портится)")
    void выборкаОтдаётсяКопией() {
        assertThatCode(() -> InstrumentationDiagnostics.failures().clear())
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
