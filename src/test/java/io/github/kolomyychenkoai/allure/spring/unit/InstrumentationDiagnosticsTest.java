package io.github.kolomyychenkoai.allure.spring.unit;

import io.qameta.allure.Epic;
import io.github.kolomyychenkoai.allure.spring.internal.AllureInstrumentation;
import io.github.kolomyychenkoai.allure.spring.internal.FailureLog;
import io.github.kolomyychenkoai.allure.spring.internal.InstrumentationDiagnostics;
import io.github.kolomyychenkoai.allure.spring.support.LibraryLog;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.pool.TypePool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.LogRecord;

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
@Epic("Внутренние проверки библиотеки")
class InstrumentationDiagnosticsTest {

    @BeforeEach
    void сбросБюджета() {
        // MAX_LOGGED глобален на JVM, порядок тест-классов случайный: без сброса проверка
        // уровня зависела бы от того, сколько сбоев успел записать сосед.
        FailureLog.forgetBudget();
    }

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
    @DisplayName("успешная установка: агент привязан, типы трансформированы, сбоев по своей мишени нет")
    void успешнаяУстановкаНеДаётСбоев() {
        AllureInstrumentation.retransform(named(Probe.class.getName()),
                (builder, type, loader, module, pd) -> builder.visit(
                        Advice.to(RewriteAdvice.class).on(named("ping"))));

        // advice реально применился — не «агент установился», а «перехват работает»
        assertThat(new Probe().ping()).isEqualTo("instrumented");
        assertThat(InstrumentationDiagnostics.installed()).isTrue();
        assertThat(InstrumentationDiagnostics.transformedCount()).isPositive();
        // проверяем АДРЕСНО: счётчик глобальный на JVM, и сбой соседнего модуля в этом окне
        // покрасил бы тест не по своей вине (порядок тестов случайный)
        assertThat(InstrumentationDiagnostics.failures()).noneMatch(f -> f.contains("$Probe →"));
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

    @Test
    @DisplayName("строка про сбой привязки агента: README цитирует ровно то, что складывает код")
    void строкаПроСбойПривязкиСовпадаетСReadme() throws Exception {
        // Обещание без теста: README учит искать сбой self-attach по строке в логе, а строку
        // складывают ТРИ места — литерал <install>, сегмент «Instrumentation/» и скобка
        // «[Allure …]». Мутация в любом из трёх красит этот тест; ссылками это не держится,
        // README не читает ни один другой гейт (CommentReferenceResolvesTest берёт только
        // src/main и src/test).
        String expected = "[Allure " + FailureLog.installComponent() + "]";

        String readme = Files.readString(Path.of("README.md"), StandardCharsets.UTF_8);
        assertThat(readme)
                .as("README обещает потребителю не ту строку, которую печатает библиотека")
                .contains(expected);

        // Идём ТЕМ ЖЕ входом, что продакшен: вызов логгера напрямую пережил бы появление
        // отдельного текста для сбоя привязки и остался бы зелёным на мёртвой ветке.
        // Уровень записи в скобку не входит НАМЕРЕННО: под Spring Boot запись уходит в Logback
        // и печатается как WARN, а не WARNING.
        List<LogRecord> said = LibraryLog.capture(
                () -> FailureLog.logInstallFailure(new IllegalStateException("Could not self-attach")));
        assertThat(said)
                .filteredOn(r -> r.getLevel() == Level.WARNING)
                .extracting(LogRecord::getMessage)
                .as("библиотека печатает не ту строку, которую обещает README")
                .anyMatch(m -> m.contains(expected));
    }

    @Test
    @DisplayName("сбой привязки агента не говорит «тест не затронут»: цел тест, а не отчёт")
    void сбойПривязкиНеЗанижаетПотерю() {
        // Мутация: вернуть <install> на общий warn → хвост снова «(тест не затронут)» → красный.
        List<LogRecord> said = LibraryLog.capture(
                () -> FailureLog.logInstallFailure(new IllegalStateException("Could not self-attach")));

        assertThat(said)
                .filteredOn(r -> r.getLevel() == Level.WARNING)
                .extracting(LogRecord::getMessage)
                .as("сбой привязки агента подан хвостом от сбоя трансформации — это занижение вдвое")
                .anyMatch(m -> m.contains("байткодный слой отчёта не поднялся"))
                .noneMatch(m -> m.contains("тест не затронут"));
    }

    @Test
    @DisplayName("чужой тип не разрешился: уходит на FINE без стека, но из счётчика не пропадает")
    void нерезолвнутыйЧужойТипНеКричит() {
        int before = InstrumentationDiagnostics.failureCount();

        List<LogRecord> said = LibraryLog.capture(
                () -> FailureLog.logFailure(FOREIGN, unresolvedType()));

        assertThat(said)
                .as("ожидаемый чужой сбой снова печатается на WARNING — это и есть #74")
                .noneMatch(r -> r.getLevel() == Level.WARNING);
        assertThat(said)
                .filteredOn(r -> r.getLevel() == Level.FINE)
                .extracting(LogRecord::getMessage)
                .as("след для разбора жалобы пропал — сбой стал невидим совсем")
                .anyMatch(m -> m.contains(FOREIGN));
        assertThat(said)
                .filteredOn(r -> r.getLevel() == Level.FINE)
                .as("стек приложен к следу: он и есть тот шум, из-за которого сбой уводили с WARNING")
                .allMatch(r -> r.getThrown() == null);
        // Полная запись — ОДНА и под именем мишени этого тест-класса: гейт инвентаря
        // терпит только их, а нам надо доказать, что уход на FINE не выносит сбой из счётчика.
        FailureLog.recordFailure(UNRESOLVED_PROBE, unresolvedType());
        assertThat(InstrumentationDiagnostics.failureCount())
                .as("сбой исчез из счётчика — гейт инвентаря ослеп вместе с логом")
                .isGreaterThan(before);
    }

    @Test
    @DisplayName("настоящий сбой остаётся на WARNING со стеком, сколько бы чужих ни прошло до него")
    void настоящийСбойКричитИБюджетНаНегоЕсть() {
        // Положительный якорь к тесту выше плюс проверка бюджета: MAX_LOGGED = 5, и если
        // тратить его на скрытые сбои (как было до #74), шестой настоящий уехал бы на FINE.
        List<LogRecord> said = LibraryLog.capture(() -> {
            for (int i = 0; i < 6; i++) {
                FailureLog.logFailure(FOREIGN + i, unresolvedType());
            }
            FailureLog.logFailure("org.real.Broken", new IllegalStateException("матчер сорвался"));
        });

        assertThat(said)
                .filteredOn(r -> r.getLevel() == Level.WARNING)
                .as("настоящий сбой утонул: бюджет WARNING тратится на скрытые записи")
                .anyMatch(r -> r.getMessage().contains("org.real.Broken") && r.getThrown() != null);
    }

    @Test
    @DisplayName("умолчание на неясном входе — кричать: причина не опознана значит не «не наша»")
    void неопознаннаяПричинаКричит() {
        // Пустой вход у детектора не должен давать зелёное: «не смог отличить» и «чужой» —
        // разные вещи, и вторая тише первой. Две клетки: причины нет вовсе и причина лишь
        // УПОМЯНУТА в тексте чужого исключения (зеркало ignoreMatchesTypeNotMessage в гейте).
        List<LogRecord> безПричины = LibraryLog.capture(() -> FailureLog.logFailure("org.real.A", null));
        assertThat(безПричины)
                .as("сбой без причины замолчали — «не опознал» подан как «не наш»")
                .anyMatch(r -> r.getLevel() == Level.WARNING);

        List<LogRecord> упоминание = LibraryLog.capture(() -> FailureLog.logFailure(
                "org.real.B", new IllegalStateException("похоже на NoSuchTypeException, но нет")));
        assertThat(упоминание)
                .as("сверка причины поехала на ТЕКСТ исключения — так глушится настоящая поломка")
                .anyMatch(r -> r.getLevel() == Level.WARNING);
    }

    /** Имя чужого типа для проверок уровня: в счётчик не попадает, только в лог. */
    private static final String FOREIGN = "org.foreign.Whatever";

    /** Мишень полной записи: имя нашего тест-класса, которое гейт инвентаря терпит. */
    private static final String UNRESOLVED_PROBE =
            "io.github.kolomyychenkoai.allure.spring.unit.InstrumentationDiagnosticsTest$UnresolvedProbe";

    /** Исключение, которым byte-buddy сообщает «описание типа не разрешилось». */
    private static Throwable unresolvedType() {
        try {
            TypePool.Default.ofSystemLoader().describe("no.such.Type$Ever").resolve();
            throw new AssertionError("резолв заведомо отсутствующего типа прошёл — фикстура мертва");
        } catch (Throwable t) {
            return t;
        }
    }
}
