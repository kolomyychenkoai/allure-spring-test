package io.github.kolomyychenkoai.allure.spring.internal;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Свидетель параллельного запуска: сколько тест-методов было открыто ОДНОВРЕМЕННО.
 * <p>
 * <b>Почему факт, а не конфигурация.</b> Спросить «включена ли потоковая параллель» у JUnit
 * нельзя: параметр приходит тремя каналами — прямо в запрос Платформы, системным свойством
 * и файлом {@code junit-platform.properties}, — и первый из них старше остальных. Именно им
 * пользуется surefire: свой {@code <configurationParameters>} он кладёт в запрос, минуя
 * и свойства, и файл. Детектор, читающий свойство или файл, был бы слеп у Maven-потребителя
 * и вдобавок мог бы соврать в обратную сторону: в файле {@code true}, а запрос перекрыл
 * на {@code false}.
 * <p>
 * Пик открытых окон отвечает на тот же вопрос точно и одинаково для всех трёх каналов.
 * Цена честная: узнаём постфактум, в первом же параллельном прогоне, а не до него.
 * <p>
 * Потокобезопасен: счётчики атомарны, пик обновляется {@code accumulateAndGet}.
 */
public final class ConcurrencyWitness {

    private static final AtomicInteger OPEN = new AtomicInteger();
    private static final AtomicInteger PEAK = new AtomicInteger();

    private ConcurrencyWitness() {
    }

    /** Тест-метод начался. Зовётся из листенера, который регистрируется ВСЕГДА. */
    public static void testStarted() {
        int open = OPEN.incrementAndGet();
        PEAK.accumulateAndGet(open, Math::max);
    }

    /** Тест-метод закончился. Без парного вызова пик поехал бы вверх на последовательном прогоне. */
    public static void testFinished() {
        OPEN.decrementAndGet();
    }

    /** Видели ли мы хоть раз два открытых окна сразу — то есть настоящую потоковую параллель. */
    public static boolean concurrentSeen() {
        return PEAK.get() > 1;
    }

    /** Только для тестов: счётчики глобальны на JVM, а порядок тест-классов случайный. */
    static void forgetForTests() {
        OPEN.set(0);
        PEAK.set(0);
    }
}
