package io.github.kolomyychenkoai.allure.spring.internal;

/**
 * Мостик к пакетно-приватной записи сбоя инструментирования.
 * <p>
 * Живёт в ТЕСТАХ и в том же пакете, что и продукт, по той же причине, что {@link DiagnosticsReset}:
 * ни маркер сбоя привязки, ни сама запись сбоя не должны быть {@code public} в поставляемом jar —
 * снаружи ими нечего делать.
 * <p>
 * Зачем именно мостик, а не вызов логгера напрямую: тест обязан ходить ТЕМ ЖЕ входом, что
 * продакшен. Пока тест звал {@code AllureInstrumentationLogger.warn} сам, появление отдельного
 * текста для сбоя привязки сделало бы его холостым — продакшен ушёл бы другой веткой, а тест
 * остался бы зелёным на старой.
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

    /** Забыть бюджет напечатанных WARNING; зачем — в {@code InstrumentationDiagnostics}. */
    public static void forgetBudget() {
        InstrumentationDiagnostics.forgetLoggedForTests();
    }

    /** Полная запись — со счётчиком и выборкой. Имя типа обязано быть мишенью нашего теста. */
    public static void recordFailure(String typeName, Throwable t) {
        InstrumentationDiagnostics.recordFailure(typeName, t);
    }
}
