package io.github.kolomyychenkoai.allure.spring.support;

import io.qameta.allure.Allure;
import io.qameta.allure.model.Attachment;
import io.qameta.allure.model.StepResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Чтение шагов/вложений ИЗ ТЕКУЩЕГО (реального) Allure тест-кейса — для уровня B.
 * В отличие от {@link InMemoryAllure} НЕ подменяет lifecycle: инструментация пишет шаги в
 * настоящий тест-кейс (они попадают в реальный отчёт — showcase сохраняется), а тест тут же
 * читает их и проверяет, что вся цепочка (регистрация + байткод/листенеры + перехват)
 * действительно записала ожидаемое. Так level-B ловит поломку отчёта, не теряя демонстрацию.
 */
public final class CurrentReport {

    private CurrentReport() {
    }

    /** Плоский список шагов текущего тест-кейса (с вложенными). */
    public static List<StepResult> steps() {
        List<StepResult> out = new ArrayList<>();
        Allure.getLifecycle().updateTestCase(tr -> flatten(tr.getSteps(), out));
        return out;
    }

    /** Имена шагов текущего тест-кейса (с вложенными). */
    public static List<String> stepNames() {
        return steps().stream().map(StepResult::getName).toList();
    }

    /** Имена всех вложений (на шагах любого уровня). */
    public static List<String> attachmentNames() {
        return steps().stream().flatMap(s -> s.getAttachments().stream())
                .map(Attachment::getName).toList();
    }

    /**
     * СОДЕРЖИМОЕ вложения по имени. Allure пишет байты вложения на диск (в
     * {@code allure.results.directory}) в момент addAttachment — во время теста файл уже
     * есть, поэтому читаем его по {@code source}. Так level-B проверяет, что РЕАЛЬНАЯ цепочка
     * (а не только прямая логика на уровне A) положила во вложение правильное содержимое.
     */
    public static Optional<String> attachmentContent(String name) {
        String source = steps().stream()
                .flatMap(s -> s.getAttachments().stream())
                .filter(a -> name.equals(a.getName()))
                .map(Attachment::getSource)
                .filter(s -> s != null && !s.isBlank())
                .findFirst().orElse(null);
        if (source == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(Files.readString(
                    Paths.get(System.getProperty("allure.results.directory", "allure-results"), source),
                    StandardCharsets.UTF_8));
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /**
     * Есть ли среди УЖЕ ЗАПИСАННЫХ на диск результатов файл (вложение/результат) с данным
     * текстом. Нужно для контента, который листенер пишет в {@code afterTestMethod} (логи,
     * пошаговые WireMock-запросы): из тела теста его не прочитать, но к следующему
     * упорядоченному тесту Allure уже положил файл в {@code allure.results.directory}. Так
     * level-B проверяет такой контент через реальную цепочку, а не только на уровне A.
     */
    public static boolean anyResultFileContains(String text) {
        Path dir = Paths.get(System.getProperty("allure.results.directory", "allure-results"));
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(Files::isRegularFile).anyMatch(p -> {
                try {
                    return Files.readString(p, StandardCharsets.UTF_8).contains(text);
                } catch (IOException e) {
                    return false; // бинарный/нечитаемый файл — пропускаем
                }
            });
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Есть ли среди записанных файлов ОДИН, содержащий ВСЕ перечисленные подстроки. Нужно,
     * когда отдельные подстроки могут встретиться в РАЗНЫХ вложениях/тестах, а доказать надо
     * именно их совместное появление в одном файле (напр. {@code Offset:} + значение —
     * признак consumer-вложения, а не producer'а с тем же payload).
     */
    public static boolean anyResultFileContainsAll(String... texts) {
        Path dir = Paths.get(System.getProperty("allure.results.directory", "allure-results"));
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(Files::isRegularFile).anyMatch(p -> {
                try {
                    String content = Files.readString(p, StandardCharsets.UTF_8);
                    for (String text : texts) {
                        if (!content.contains(text)) {
                            return false;
                        }
                    }
                    return true;
                } catch (IOException e) {
                    return false;
                }
            });
        } catch (IOException e) {
            return false;
        }
    }

    private static void flatten(List<StepResult> steps, List<StepResult> out) {
        if (steps == null) {
            return;
        }
        for (StepResult s : steps) {
            out.add(s);
            flatten(s.getSteps(), out);
        }
    }
}
