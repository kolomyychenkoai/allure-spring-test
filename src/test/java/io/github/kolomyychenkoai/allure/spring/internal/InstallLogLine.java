package io.github.kolomyychenkoai.allure.spring.internal;

/**
 * Мостик к пакетно-приватным кускам строки, которой библиотека сообщает о сбое привязки агента.
 * <p>
 * Живёт в ТЕСТАХ и в том же пакете, что и продукт, по той же причине, что
 * {@link DiagnosticsReset}: маркер сбоя и сборка имени компонента не должны быть {@code public}
 * в поставляемом jar — снаружи ими нечего делать.
 * <p>
 * Зачем вообще: README учит потребителя искать сбой self-attach по строке в логе, а складывают
 * её три разных места — литерал {@code AllureInstrumentation.INSTALL_MARKER}, сегмент
 * {@code InstrumentationDiagnostics.component} и скобка в {@code AllureInstrumentationLogger}.
 * Переименование любого из трёх делает README ложью, и молча: {@code CommentReferenceResolvesTest}
 * README не читает.
 */
public final class InstallLogLine {

    private InstallLogLine() {
    }

    /** Имя компонента в скобке лога, собранное ТЕМ ЖЕ кодом, что работает в продакшене. */
    public static String component() {
        return InstrumentationDiagnostics.component(AllureInstrumentation.INSTALL_MARKER);
    }
}
