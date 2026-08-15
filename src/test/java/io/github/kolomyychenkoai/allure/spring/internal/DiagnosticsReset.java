package io.github.kolomyychenkoai.allure.spring.internal;

/**
 * Мостик к пакетно-приватному сбросу {@link ActivationDiagnostics}.
 * <p>
 * Живёт в ТЕСТАХ и в том же пакете, что и продукт, — ровно затем, чтобы сам сброс не был
 * {@code public} в поставляемом jar. Единственный тест-хук библиотеки не должен быть частью
 * того, что скачивают чужие команды: подавление повторов глобальное, и публичный метод,
 * снимающий его на всю JVM, — приглашение выстрелить себе в ногу из чужого кода.
 * <p>
 * Зачем сброс вообще нужен: {@code SAID} статично и живёт до конца JVM, а у нас
 * {@code runOrder=random}. Без сброса ассерт «сказано ровно один раз» зависел бы от того,
 * какой тест-класс поднял контекст первым.
 */
public final class DiagnosticsReset {

    private DiagnosticsReset() {
    }

    /** Забыть всё сказанное через {@code noteOnce}. */
    public static void forget() {
        ActivationDiagnostics.forgetForTests();
    }
}
