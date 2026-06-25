package io.github.kolomyychenkoai.allure.spring.liquibase.internal;

import io.github.kolomyychenkoai.allure.spring.internal.AllureAdviceSupport;
import io.github.kolomyychenkoai.allure.spring.internal.AllureInstrumentation;
import io.github.kolomyychenkoai.allure.spring.internal.AllureInstrumentationLogger;
import io.qameta.allure.Allure;
import liquibase.changelog.ChangeSet;
import net.bytebuddy.asm.Advice;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

/**
 * ByteBuddy-инструментирование Liquibase: после успешного применения changeset'а
 * ({@code ChangeSet.execute(...)}) в отчёт попадает информация о миграции. Без кода в тестах.
 * <p>
 * ДВА сценария (одна точка перехвата покрывает оба):
 * <ul>
 *   <li><b>Миграция ВО ВРЕМЯ теста</b> (тесты миграций, ручной {@code liquibase.update()}) —
 *       идёт на тест-потоке с активным кейсом → каждый changeset пишется СРАЗУ шагом
 *       «Liquibase: changeset &lt;id&gt; (&lt;author&gt;)».</li>
 *   <li><b>Миграция на СТАРТЕ контекста</b> (обычный случай Spring Boot) — идёт ДО теста, без
 *       активного кейса → changeset'ы буферизуем и выкладываем ОДНИМ снимком-шагом
 *       «Liquibase: применено N changeset на старте» в первом же тесте (см.
 *       {@link #flushStartupSnapshot()}, зовётся из {@code AllureLiquibaseListener}).</li>
 * </ul>
 * Снимок старта выкладывается РОВНО один раз на JVM (CAS-гард {@code SNAPSHOT_EMITTED}) — в
 * первом тесте, где есть активный кейс; дальше не повторяется. Живые changeset'ы в буфер
 * старта не попадают.
 * <p>
 * Перехватывается современная сигнатура {@code execute(DatabaseChangeLog, ChangeExecListener,
 * Database)} (3 аргумента). Упавший changeset шага не даёт — падение Allure покажет на уровне
 * теста. Всё в try/catch, сбой не роняет тест. Установка идемпотентна (CAS-гард) — раз на JVM.
 * <p>
 * ⚠️ <b>Версионно-хрупкие допущения</b> (проверено на Liquibase 4.x; закреплено канарейкой
 * {@code InstrumentationApiCanaryTest#liquibaseMatchers}):
 * <ul>
 *   <li>2-арг overload {@code execute} делегирует в 3-арг — поэтому матчим ТОЛЬКО 3-арг и не
 *       получаем дублей. НЕ добавляй {@code takesArguments(2)} — будет двойной шаг.</li>
 *   <li>live- и startup-пути ВЗАИМОИСКЛЮЧАЮТ во времени: startup-буфер заполняется только до
 *       первого теста (нет активного кейса), снимок выкладывается в первом тесте; во время теста
 *       changeset'ы идут только в live-путь. Поэтому дренаж буфера в {@link #flushStartupSnapshot()}
 *       не гонится с записью.</li>
 * </ul>
 */
public final class AllureLiquibaseInstrumentation {

    private static final AtomicBoolean INSTALLED = new AtomicBoolean(false);
    private static final AtomicBoolean SNAPSHOT_EMITTED = new AtomicBoolean(false);

    // changeset'ы, применённые на старте контекста (нет активного кейса) — ждут снимка в первом тесте.
    private static final Queue<String> STARTUP_BUFFER = new ConcurrentLinkedQueue<>();

    private AllureLiquibaseInstrumentation() {
    }

    public static void install() {
        if (!INSTALLED.compareAndSet(false, true)) {
            return;
        }
        AllureInstrumentation.retransform(named("liquibase.changelog.ChangeSet"),
                (builder, type, cl, module, pd) -> builder.visit(Advice.to(ExecuteAdvice.class)
                        .on(named("execute").and(takesArguments(3)))));
    }

    /** Логика логирования (вынесена из advice, чтобы тестировать без Liquibase-движка). */
    public static void onExecute(Object changeSetObj, Throwable thrown) {
        try {
            // упавший changeset не логируем — падение Allure покажет на уровне теста
            if (thrown != null || !(changeSetObj instanceof ChangeSet cs)) {
                return;
            }
            if (Allure.getLifecycle().getCurrentTestCase().isPresent()) {
                emitLive(cs); // миграция во время теста — пишем сразу
            } else {
                STARTUP_BUFFER.add(details(cs)); // старт контекста — в снимок
            }
        } catch (Throwable t) {
            AllureInstrumentationLogger.warn("Liquibase", t);
        }
    }

    /**
     * Выкладывает снимок changeset'ов, применённых на старте, ОДНИМ шагом — один раз на JVM,
     * в первом тесте с активным кейсом. Зовётся из {@code AllureLiquibaseListener#afterTestMethod}.
     */
    public static void flushStartupSnapshot() {
        try {
            if (!Allure.getLifecycle().getCurrentTestCase().isPresent() || STARTUP_BUFFER.isEmpty()) {
                return;
            }
            if (!SNAPSHOT_EMITTED.compareAndSet(false, true)) {
                return; // снимок уже выложен
            }
            List<String> applied = new ArrayList<>();
            String d;
            while ((d = STARTUP_BUFFER.poll()) != null) {
                applied.add(d);
            }
            int count = applied.size();
            String body = String.join("\n---\n", applied);
            Allure.step("Liquibase: применено " + count + " changeset на старте", step -> {
                Allure.addAttachment("Применённые миграции", "text/plain", body);
            });
        } catch (Throwable t) {
            AllureInstrumentationLogger.warn("Liquibase", t);
        }
    }

    private static void emitLive(ChangeSet cs) {
        String desc = AllureAdviceSupport.safe(cs.getId()) + " (" + AllureAdviceSupport.safe(cs.getAuthor()) + ")";
        Allure.step("Liquibase: changeset " + desc, step -> {
            Allure.addAttachment("Changeset", "text/plain", details(cs));
        });
    }

    private static String details(ChangeSet cs) {
        StringBuilder sb = new StringBuilder();
        sb.append("Id: ").append(AllureAdviceSupport.safe(cs.getId()))
                .append("\nAuthor: ").append(AllureAdviceSupport.safe(cs.getAuthor()))
                .append("\nChangelog: ").append(AllureAdviceSupport.safe(cs.getFilePath()));
        String comments = cs.getComments();
        if (comments != null && !comments.isBlank()) {
            sb.append("\nComments: ").append(AllureAdviceSupport.safe(comments));
        }
        return sb.toString();
    }

    public static class ExecuteAdvice {
        @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
        public static void onExit(@Advice.This Object self, @Advice.Thrown Throwable thrown) {
            onExecute(self, thrown);
        }
    }
}
