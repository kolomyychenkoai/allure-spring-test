package io.github.kolomyychenkoai.allure.spring.unit;

import io.qameta.allure.Epic;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Тесты ВТОРОГО сигнала (гейт сбоев перехвата). Он сам — проверяющий, поэтому обязан быть
 * проверен: молча сломавшийся гейт неотличим от здоровой сборки, а именно от этого ветка
 * и защищает.
 * <p>
 * Класс {@code InstrumentationFailures} package-private (живёт рядом с проверкой), зовём
 * рефлексией — ради теста расширять его видимость не стоит.
 */
@Epic("Внутренние проверки библиотеки")
class InstrumentationFailuresTest {

    private static String report(Path dir) throws Exception {
        Class<?> type = Class.forName("io.github.kolomyychenkoai.allure.spring.inventory.InstrumentationFailures");
        Method report = type.getDeclaredMethod("report", Path.class);
        report.setAccessible(true);
        try {
            return (String) report.invoke(null, dir);
        } catch (InvocationTargetException e) {
            throw (Exception) e.getCause();
        }
    }

    private static void dump(Path dir, String name, String content) throws IOException {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(name), content, StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("здоровый дамп — сигнал молчит")
    void healthyDumpIsSilent(@TempDir Path dir) throws Exception {
        dump(dir, "jvm-1.txt", "installed=true\ntransformed=117\nfailures=0\n");

        assertThat(report(dir)).isNull();
    }

    @Test
    @DisplayName("сбой перехвата — красный с именем типа и объяснением, что это НЕ «матчер не совпал»")
    void realFailureIsReported(@TempDir Path dir) throws Exception {
        dump(dir, "jvm-1.txt", """
                installed=true
                transformed=10
                failures=1
                failure: org.assertj.core.api.ListAssert → IllegalStateException: Cannot catch exception
                """);

        assertThat(report(dir))
                .contains("org.assertj.core.api.ListAssert")
                .contains("НЕ «матчер не совпал»");
    }

    @Test
    @DisplayName("дампов нет — «сигнал не работает», а не «проверка прошла»")
    void missingDumpIsLoud(@TempDir Path dir) throws Exception {
        // самая опасная ветка: молчание тут означало бы, что гейт выключился и никто не заметил
        assertThat(report(dir.resolve("нет-каталога"))).contains("не найдены").contains("не работает");
    }

    @Test
    @DisplayName("агент не установился — красный с подсказкой про self-attach")
    void notInstalledIsLoud(@TempDir Path dir) throws Exception {
        dump(dir, "jvm-1.txt", "installed=false\ntransformed=0\nfailures=0\n");

        assertThat(report(dir)).contains("НЕ установлен").contains("EnableDynamicAgentLoading");
    }

    @Test
    @DisplayName("сбои ТОЛЬКО из списка игнорируемых — сигнал молчит (иначе гейт отключат)")
    void ignoredFailuresStaySilent(@TempDir Path dir) throws Exception {
        dump(dir, "jvm-1.txt", """
                installed=true
                transformed=117
                failures=2
                failure: io.github.kolomyychenkoai.allure.spring.unit.InstrumentationDiagnosticsTest$NegativeProbe → IllegalStateException: мутация
                failure: org.mockito.internal.creation.bytebuddy.MockMethodAdvice → NoSuchTypeException: MockMethodDispatcher
                """);

        assertThat(report(dir)).isNull();
    }

    @Test
    @DisplayName("игнор — по ИМЕНИ ТИПА, а не по тексту исключения")
    void ignoreMatchesTypeNotMessage(@TempDir Path dir) throws Exception {
        // настоящий сбой, у которого имя игнорируемого типа лишь упомянуто в сообщении,
        // проглатывать нельзя — иначе список-глушитель начнёт гасить реальные поломки
        dump(dir, "jvm-1.txt", """
                installed=true
                failures=1
                failure: org.assertj.core.api.ListAssert → IllegalStateException: не удалось из-за MockMethodAdvice
                """);

        assertThat(report(dir)).contains("org.assertj.core.api.ListAssert");
    }

    @Test
    @DisplayName("подавленных сбоев стало на порядок больше — красный (шумовой пол тоже сигнал)")
    void suppressionCeilingIsGuarded(@TempDir Path dir) throws Exception {
        // подавление узкое, но если ту же причину начнут давать и абстрактные предки AssertJ
        // (сейчас они вплетаются успешно), картина изменится принципиально при зелёном гейте
        StringBuilder dump = new StringBuilder("installed=true\n");
        for (int i = 0; i < 200; i++) {
            dump.append("failure: org.mockito.internal.creation.bytebuddy.MockMethodAdvice")
                    .append(" → NoSuchTypeException: MockMethodDispatcher\n");
        }
        dump(dir, "jvm-1.txt", dump.toString());

        assertThat(report(dir)).contains("подавленных сбоев 200");
    }

    @Test
    @DisplayName("выборка сбоев усечена — красный (ограниченный буфер, прочитанный как полная картина, врёт)")
    void truncatedSampleIsLoud(@TempDir Path dir) throws Exception {
        // счётчик точный, а выборка ограничена: если сбоев больше, чем строк, «настоящий 51-й»
        // в дамп не попадёт и гейт промолчит. Об усечении обязан сообщать сам механизм.
        dump(dir, "jvm-1.txt", "installed=true\nfailures=90\nsample_truncated=true\nfailure: com.acme.Thing → Boom\n");

        assertThat(report(dir)).contains("УСЕЧЕНА");
    }

    @Test
    @DisplayName("несколько JVM: агент установлен хотя бы в одной, сбои — объединением")
    void mergesDumpsFromSeveralJvms(@TempDir Path dir) throws Exception {
        // JVM инвентаря и форки surefire пишут свои дампы; «последний закрывшийся» не должен
        // решать за всех — раньше он затирал общий файл и давал ложный «агент не установлен»
        dump(dir, "jvm-1.txt", "installed=true\ntransformed=117\nfailures=0\n");
        dump(dir, "jvm-2.txt", "installed=false\ntransformed=0\nfailures=0\n");

        assertThat(report(dir)).isNull();

        dump(dir, "jvm-3.txt", "installed=false\nfailures=1\nfailure: com.acme.Thing → Boom: сломалось\n");
        assertThat(report(dir)).contains("com.acme.Thing");
    }
}
