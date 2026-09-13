package io.github.kolomyychenkoai.allure.spring.internal;

/**
 * Мостик к пакетно-приватной записи сбоя инструментирования.
 * <p>
 * Живёт в ТЕСТАХ и в том же пакете, что и продукт, по той же причине, что {@link DiagnosticsReset}:
 * ни маркер сбоя привязки, ни сама запись сбоя не должны быть {@code public} в поставляемом jar —
 * снаружи ими нечего делать.
 * <p>
 * Зачем именно мостик, а не вызов логгера напрямую: тест обязан ходить ТЕМ ЖЕ входом, что
 * продакшен. Прямой вызов логгера зеленеет на ветке, которой продакшен больше не ходит.
 */
public final class FailureLog {

    private FailureLog() {
    }

    /** Имя компонента в скобке лога, собранное ТЕМ ЖЕ кодом, что работает в продакшене. */
    public static String installComponent() {
        return InstrumentationDiagnostics.component(AllureInstrumentation.INSTALL_MARKER);
    }

    /**
     * Записать сбой в ЛОГ тем же кодом, что продакшен, не трогая счётчик сбоев и выборку.
     * Почему разделено — в javadoc {@code InstrumentationDiagnostics#logFailure}.
     */
    public static void logFailure(String typeName, Throwable t) {
        InstrumentationDiagnostics.logFailure(typeName, t);
    }

    /** То же для сбоя привязки агента: маркер берётся из продакшена, а не из литерала в тесте. */
    public static void logInstallFailure(Throwable t) {
        InstrumentationDiagnostics.logFailure(AllureInstrumentation.INSTALL_MARKER, t);
    }

    /** Снять счётчики бюджета; зачем — в {@code InstrumentationDiagnostics}. */
    public static int[] budget() {
        return InstrumentationDiagnostics.countersForTests();
    }

    /** Вернуть счётчики бюджета как было (или обнулить, передав нули). */
    public static void restoreBudget(int[] counters) {
        InstrumentationDiagnostics.restoreCountersForTests(counters[0], counters[1]);
    }

    /** Имя исключения «описание типа не разрешилось» — из продакшена, а не из литерала в тесте. */
    public static String unresolvedTypeName() {
        return InstrumentationDiagnostics.unresolvedTypeName();
    }
}
