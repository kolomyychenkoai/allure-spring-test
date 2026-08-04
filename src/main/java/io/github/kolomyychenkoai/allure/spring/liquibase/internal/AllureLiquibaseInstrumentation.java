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
 *       активного кейса → changeset'ы буферизуем и повторяем ОДНИМ снимком-шагом
 *       «🛢️ Liquibase: схема БД (N changeset)» в НАЧАЛЕ КАЖДОГО теста (см.
 *       {@link #emitStartupSnapshot()}, зовётся из {@code AllureLiquibaseListener#beforeTestMethod}).</li>
 * </ul>
 * Снимок старта копится в JVM-широкий {@code STARTUP_SNAPSHOT} (первый тест сливает в него буфер,
 * дальше — реплей; дедуп по содержимому changeset) и рисуется в НАЧАЛЕ каждого теста — чтобы любой
 * тест был самодостаточен (видно, на какой схеме БД он работает). Живые changeset'ы в буфер старта
 * не попадают.
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
 *   <li>live- и startup-пути ВЗАИМОИСКЛЮЧАЮТ во времени: startup-буфер заполняется только на старте
 *       контекста (нет активного кейса — фаза {@code prepare()} узла JUnit-Platform ДО старта кейса);
 *       во время теста changeset'ы идут только в live-путь. Дренаж буфера в {@link #emitStartupSnapshot()}
 *       идёт под локом на {@code STARTUP_SNAPSHOT}, поэтому не гонится с записью.</li>
 * </ul>
 */
public final class AllureLiquibaseInstrumentation {

    private static final AtomicBoolean INSTALLED = new AtomicBoolean(false);

    // changeset'ы старта, ждущие слива из буфера в снимок (нет активного кейса на старте контекста).
    private static final Queue<String> STARTUP_BUFFER = new ConcurrentLinkedQueue<>();
    // накопленный снимок стартовой схемы (JVM-широкий) — реплеим в НАЧАЛЕ каждого теста; дедуп по содержимому.
    private static final List<String> STARTUP_SNAPSHOT = new ArrayList<>();

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
     * Рисует снимок стартовой схемы БД ОДНИМ шагом в НАЧАЛЕ каждого теста — чтобы любой тест был
     * самодостаточен. Сливает новые changeset'ы из буфера в JVM-широкий {@code STARTUP_SNAPSHOT}
     * (дедуп по содержимому) и повторяет весь снимок. Зовётся из
     * {@code AllureLiquibaseListener#beforeTestMethod} (кейс уже активен: платформенный слушатель
     * Allure {@code AllureJunitPlatform.executionStarted} стартует кейс до фазы {@code before} узла).
     * <p>
     * Потокобезопасен: дренаж буфера + дедуп + защитная копия — под локом {@code STARTUP_SNAPSHOT}
     * (буфер — {@code ConcurrentLinkedQueue}); {@code Allure.step} пишет в thread-local кейс своего теста.
     */
    public static void emitStartupSnapshot() {
        try {
            if (!Allure.getLifecycle().getCurrentTestCase().isPresent()) {
                return;
            }
            List<String> applied;
            synchronized (STARTUP_SNAPSHOT) {
                String d;
                while ((d = STARTUP_BUFFER.poll()) != null) {
                    if (!STARTUP_SNAPSHOT.contains(d)) {
                        STARTUP_SNAPSHOT.add(d); // n мал (число changeset базы) — O(n) contains ок
                    }
                }
                if (STARTUP_SNAPSHOT.isEmpty()) {
                    return; // Liquibase на старте не запускался — тихо выходим
                }
                applied = new ArrayList<>(STARTUP_SNAPSHOT);
            }
            int count = applied.size();
            String body = String.join("\n---\n", applied);
            Allure.step("🛢️ Liquibase: схема БД (" + count + " changeset)", step -> {
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

    // ВЛОЖЕНИЕ, а не имя шага → render(), а не safe(): всё здесь уже String, а комментарий
    // changeset'а бывает многострочным — safe() схлопнул бы его в одну строку.
    private static String details(ChangeSet cs) {
        StringBuilder sb = new StringBuilder();
        sb.append("Id: ").append(AllureAdviceSupport.render(cs.getId()))
                .append("\nAuthor: ").append(AllureAdviceSupport.render(cs.getAuthor()))
                .append("\nChangelog: ").append(AllureAdviceSupport.render(cs.getFilePath()));
        String comments = cs.getComments();
        if (comments != null && !comments.isBlank()) {
            sb.append("\nComments: ").append(AllureAdviceSupport.render(comments));
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
