package io.github.kolomyychenkoai.allure.spring.internal;

import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

/**
 * Диагностика байткод-инструментирования: установился ли агент, сколько типов реально
 * трансформировано и какие трансформации СОРВАЛИСЬ.
 * <p>
 * <b>Зачем.</b> Библиотека ломается тихо: {@code AgentBuilder} уведомляет слушателя об ошибке
 * трансформации и идёт дальше, а {@link AllureInstrumentation#retransform} глотает Throwable —
 * шаг просто исчезает из отчёта при зелёных тестах. Без этого счётчика проверить «перехват
 * действительно встал» нечем: тесту негде взять факт.
 * <p>
 * <b>Поведение у потребителя не меняется:</b> сбои по-прежнему не роняют прогон, здесь только
 * учёт. Память не течёт — храним СТРОКИ (не {@code Throwable}: его стек держит {@code Class} →
 * {@code ClassLoader}) и ограниченную выборку.
 * <p>
 * Ответственность разделена: этот счётчик отвечает на «инструментация СЛОМАЛАСЬ с ошибкой»,
 * а инвентарь отчёта (тест {@code ReportInventoryCheck}) — на «шаг ИСЧЕЗ, ошибок не было».
 * При апгрейде Java/Spring типичен второй случай: матчер просто перестал совпадать.
 */
public final class InstrumentationDiagnostics {

    /**
     * Потолок РАЗНООБРАЗИЯ выборки: столько УНИКАЛЬНЫХ записей «тип → причина» храним для показа.
     * Общее число сбоев ({@link #failureCount()}) при этом точное и может быть кратно больше;
     * переполнение выборки поднимает {@link #sampleTruncated()}.
     */
    private static final int MAX_SAMPLE = 50;
    /** Сколько сбоев печатаем на WARNING, дальше — сводка + FINE (не заливать stderr). */
    private static final int MAX_LOGGED = 5;
    /**
     * Имя исключения byte-buddy «описание типа не разрешилось». Строкой, а не типом: см.
     * {@link #unresolvedType(Throwable)}. Держит канарейка {@code InstrumentationApiCanaryTest}.
     */
    private static final String UNRESOLVED_TYPE = "net.bytebuddy.pool.TypePool$Resolution$NoSuchTypeException";
    /** Предел обхода цепочки причин: у чужого исключения она бывает закольцована. */
    private static final int MAX_CAUSE_DEPTH = 20;

    private static final AtomicBoolean INSTALLED = new AtomicBoolean();
    private static final AtomicBoolean SAMPLE_TRUNCATED = new AtomicBoolean();
    private static final AtomicInteger FAILURES = new AtomicInteger();
    /**
     * Сколько сбоев ушло на WARNING. Отдельно от {@link #FAILURES} намеренно: бюджет
     * {@link #MAX_LOGGED} обязан тратиться на реально напечатанное, иначе ожидаемый шум
     * съедал бы слоты у настоящей поломки, и шестая по счёту настоящая уходила бы на FINE.
     */
    private static final AtomicInteger LOGGED = new AtomicInteger();
    private static final AtomicInteger TRANSFORMED = new AtomicInteger();
    private static final Queue<String> SAMPLE = new ConcurrentLinkedQueue<>();
    /** Множество уже увиденных записей: putIfAbsent даёт атомарное «я первый». */
    private static final Map<String, Boolean> SEEN = new ConcurrentHashMap<>();
    private static final AtomicInteger UNIQUE = new AtomicInteger();

    private InstrumentationDiagnostics() {
    }

    /** Удалось ли привязать байткод-агент к JVM хотя бы раз. */
    public static boolean installed() {
        return INSTALLED.get();
    }

    /** Сколько трансформаций СОРВАЛОСЬ (точное число, даже если в лог попали не все). */
    public static int failureCount() {
        return FAILURES.get();
    }

    /**
     * Сколько трансформаций реально применено — позитивный сигнал «агент не просто установился».
     * Считаются СОБЫТИЯ, а не уникальные типы: один тип, попавший под два модуля, даёт 2.
     */
    public static int transformedCount() {
        return TRANSFORMED.get();
    }

    /** Не поместились ли уникальные сбои в выборку — тогда {@link #failures()} НЕПОЛНА. */
    public static boolean sampleTruncated() {
        return SAMPLE_TRUNCATED.get();
    }

    /** Выборка УНИКАЛЬНЫХ сбоев «тип → причина» (до {@value #MAX_SAMPLE}); копия, не мутабельная. */
    public static List<String> failures() {
        return List.copyOf(SAMPLE);
    }

    static void markInstalled() {
        INSTALLED.set(true);
    }

    static void recordTransformation() {
        TRANSFORMED.incrementAndGet();
    }

    static void recordFailure(String typeName, Throwable t) {
        FAILURES.incrementAndGet();
        String brief = typeName + " → " + brief(t);
        // Выборка ограничена, поэтому храним УНИКАЛЬНЫЕ записи: иначе полсотни одинаковых
        // артефактов одного апгрейда вытеснили бы из неё настоящую поломку — а она приходит
        // позже (артефакты установки регистрируются первыми).
        //
        // «Я первый» решается ОДНОЙ атомарной операцией: onError дёргается на потоках загрузки
        // классов (в Spring/surefire они параллельны), и пара «contains → add» пропустила бы
        // и дубли, и переполнение мимо флага.
        if (SEEN.putIfAbsent(brief, Boolean.TRUE) == null) {
            if (UNIQUE.incrementAndGet() <= MAX_SAMPLE) {
                SAMPLE.add(brief);
            } else {
                // выборка переполнена УНИКАЛЬНЫМИ записями: дальше гейт видит не всё,
                // и знать об этом он должен от механизма, а не по расхождению чисел
                SAMPLE_TRUNCATED.set(true);
            }
        }
        logFailure(typeName, t);
    }

    /**
     * Забыть, сколько сбоев уже напечатано. ТОЛЬКО для тестов и намеренно пакетно-приватный:
     * бюджет {@link #MAX_LOGGED} глобален на JVM, а порядок тест-классов случайный, и без
     * сброса проверка уровня зависела бы от того, сколько сбоев записал сосед.
     * <p>
     * Счётчик сбоев {@link #FAILURES} и выборку НЕ трогает: их читает гейт инвентаря,
     * и обнулять его сигнал посреди прогона нельзя.
     */
    static void forgetLoggedForTests() {
        LOGGED.set(0);
    }

    /**
     * Решение об уровне и тексте записи — отдельно от счётчиков намеренно. Так тест уровня
     * ходит ТЕМ ЖЕ кодом, что продакшен, и при этом не подсыпает выдуманные сбои в дамп,
     * который читает гейт инвентаря: гейт стережёт настоящие сбои прогона, и фикстуры в нём
     * выглядели бы поломкой перехвата.
     */
    static void logFailure(String typeName, Throwable t) {
        if (unresolvedType(t)) {
            // Чужой тип не разрешился при обходе загруженных классов: наш трансформер до него
            // не дошёл, перехват цел, терять в отчёте нечего. На WARNING этому не место —
            // предупреждение, которое видно в КАЖДОЙ сборке и ничего не значит, обесценивает
            // канал целиком, и потребитель перестаёт замечать настоящие (#74).
            AllureInstrumentationLogger.trace(component(typeName), () -> typeName + " → " + brief(t));
            return;
        }
        int n = LOGGED.incrementAndGet();
        if (n <= MAX_LOGGED) {
            if (AllureInstrumentation.INSTALL_MARKER.equals(typeName)) {
                AllureInstrumentationLogger.warnInstall(component(typeName), t);
            } else {
                AllureInstrumentationLogger.warn(component(typeName), t);
            }
        } else if (n == MAX_LOGGED + 1) {
            AllureInstrumentationLogger.logger().warning(
                    "[Allure Instrumentation] дальнейшие сбои трансформации в лог не печатаются; "
                            + "итог — InstrumentationDiagnostics.failureCount()");
        } else {
            AllureInstrumentationLogger.logger()
                    .log(Level.FINE, () -> "[Allure Instrumentation] " + typeName + " → " + brief(t));
        }
    }

    /**
     * Сбой случился ДО нашего трансформера: ByteBuddy не смог разрешить описание типа при
     * обходе загруженных классов (чужой класс в отдельном загрузчике, отсутствующий предок).
     * <p>
     * Сверка по ИМЕНИ класса, а не по {@code instanceof}: ссылка на тип byte-buddy тянула бы
     * его загрузку внутрь {@code catch}, а сюда приходит в том числе сбой самой привязки
     * агента — то есть случай, когда byte-buddy могло не оказаться вовсе. Что имя именно
     * такое, держит канарейка {@code InstrumentationApiCanaryTest}.
     * <p>
     * Цепочка причин обходится с пределом: у чужого исключения она может быть закольцована,
     * и тогда обход без предела повесил бы сборку потребителя.
     */
    private static boolean unresolvedType(Throwable t) {
        Throwable c = t;
        for (int depth = 0; c != null && depth < MAX_CAUSE_DEPTH; depth++, c = c.getCause()) {
            if (UNRESOLVED_TYPE.equals(c.getClass().getName())) {
                return true;
            }
        }
        return false;
    }

    /** Имя компонента в скобке лога: «[Allure Instrumentation/ТИП]». Цитируется в README. */
    static String component(String typeName) {
        return "Instrumentation/" + typeName;
    }

    /** Только тип и сообщение, с обрезкой: стек не храним (иначе утечка через ClassLoader). */
    private static String brief(Throwable t) {
        if (t == null) {
            return "неизвестная причина";
        }
        String message = t.getMessage();
        String text = t.getClass().getSimpleName() + (message == null ? "" : ": " + message);
        return text.length() <= 200 ? text : text.substring(0, 200) + "…";
    }
}
