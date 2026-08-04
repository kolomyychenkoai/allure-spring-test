package io.github.kolomyychenkoai.allure.spring.inventory;

import io.github.kolomyychenkoai.allure.spring.internal.InstrumentationDiagnostics;
import org.junit.platform.launcher.LauncherSession;
import org.junit.platform.launcher.LauncherSessionListener;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Выгружает {@link InstrumentationDiagnostics} на диск в конце тестовой сессии.
 * <p>
 * <b>Зачем через файл.</b> Счётчики — статика ОДНОЙ JVM, а проверять их надо после того, как
 * установились ВСЕ модули (порядок тестов случайный, любой обычный тест может отработать раньше
 * последней установки). Инвентарь же гоняется во ВТОРОЙ JVM и оттуда до статики не дотянуться.
 * Файл решает обе проблемы: пишем в самом конце основного прогона, читаем из второго.
 * <p>
 * <b>Файл — на КАЖДУЮ JVM.</b> Листенер зарегистрирован на весь test-classpath, поэтому
 * срабатывает и в JVM инвентаря — той самой, что читает дамп; с общим именем она затирала бы
 * его своим пустым срезом («агент не установлен» после зелёной сборки). А при {@code forkCount>1}
 * последний закрывшийся форк становился бы молчаливым арбитром правды. Имя по номеру форка
 * (или PID) + слияние при чтении — как устроены сами {@code surefire-reports}.
 * <p>
 * Сам SPI-листенер уронить сборку не может и не должен: {@code launcherSessionClosed} вызывается
 * БЕЗ try/catch внутри junit-platform, поэтому исключение отсюда оборвало бы прогон уже ПОСЛЕ
 * всех тестов, ошибкой, не связанной ни с одним из них. Здесь только пишем и жалуемся в stderr;
 * краснеет настоящий тест {@link ReportInventoryCheck} (нет дампа — «сигнал не работает»).
 */
public class InstrumentationDiagnosticsDump implements LauncherSessionListener {

    static final Path DIR = Path.of("target/instrumentation-diagnostics");

    @Override
    public void launcherSessionClosed(LauncherSession session) {
        // роль JVM — для ЧЕЛОВЕКА: в каталоге лежат файлы с противоположным installed, и когда
        // гейт покраснеет по-настоящему, не надо гадать, какой из них «правильный»
        StringBuilder out = new StringBuilder()
                .append("jvm=").append(InstrumentationDiagnostics.installed() ? "main" : "inventory-or-idle").append('\n')
                .append("installed=").append(InstrumentationDiagnostics.installed()).append('\n')
                .append("transformed=").append(InstrumentationDiagnostics.transformedCount()).append('\n')
                .append("failures=").append(InstrumentationDiagnostics.failureCount()).append('\n')
                .append("sample_truncated=").append(InstrumentationDiagnostics.sampleTruncated()).append('\n');
        InstrumentationDiagnostics.failures().forEach(failure -> out.append("failure: ").append(failure).append('\n'));
        try {
            Files.createDirectories(DIR);
            Files.writeString(DIR.resolve("jvm-" + jvmId() + ".txt"), out.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            System.err.println("ДИАГНОСТИКА ПЕРЕХВАТА: не удалось записать дамп в " + DIR + " — " + e);
        }
    }

    /**
     * Идентификатор JVM для имени файла — лишь бы две JVM не делили один.
     * <p>
     * На практике работает PID: {@code surefire.forkNumber} — плейсхолдер для интерполяции в
     * КОНФИГУРАЦИИ плагина ({@code argLine}, {@code systemPropertyVariables}), внутрь форка как
     * системное свойство он не приезжает, и файлы у нас называются {@code jvm-<pid>.txt}.
     * Ветку оставляем на случай, если её начнут прокидывать явно, — но рассчитывать надо на PID.
     */
    private static String jvmId() {
        String fork = System.getProperty("surefire.forkNumber");
        return fork == null || fork.isBlank() ? String.valueOf(ProcessHandle.current().pid()) : fork;
    }
}
