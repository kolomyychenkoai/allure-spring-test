package io.github.kolomyychenkoai.allure.spring.inventory;

import io.github.kolomyychenkoai.allure.spring.internal.InstrumentationDiagnostics;
import org.junit.platform.launcher.LauncherSession;
import org.junit.platform.launcher.LauncherSessionListener;

import java.io.IOException;
import java.io.UncheckedIOException;
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
 * Сам SPI-листенер уронить сборку не может (исключение отсюда не станет падением теста) —
 * поэтому он только ПИШЕТ, а краснеет настоящий тест {@link ReportInventoryCheck}.
 * Регистрация — {@code src/test/resources/META-INF/services/org.junit.platform.launcher.LauncherSessionListener}.
 */
public class InstrumentationDiagnosticsDump implements LauncherSessionListener {

    static final Path FILE = Path.of("target/instrumentation-diagnostics.txt");

    @Override
    public void launcherSessionClosed(LauncherSession session) {
        StringBuilder out = new StringBuilder()
                .append("installed=").append(InstrumentationDiagnostics.installed()).append('\n')
                .append("transformed=").append(InstrumentationDiagnostics.transformedCount()).append('\n')
                .append("failures=").append(InstrumentationDiagnostics.failureCount()).append('\n');
        InstrumentationDiagnostics.failures().forEach(failure -> out.append("failure: ").append(failure).append('\n'));
        try {
            Files.createDirectories(FILE.getParent());
            Files.writeString(FILE, out.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
