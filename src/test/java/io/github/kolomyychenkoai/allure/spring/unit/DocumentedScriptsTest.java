package io.github.kolomyychenkoai.allure.spring.unit;

import io.qameta.allure.Epic;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Страж связки «дока обещает инструмент — инструмент существует», в обе стороны.
 * <p>
 * Процедура ревью держится на четырёх скриптах, и каждый из них упомянут в доках как
 * обязательный шаг. Обещание без стража — гипотеза: переименованный или удалённый скрипт
 * оставляет в playbook'е шаг, который просто не выполнить, и узнать об этом можно только
 * дойдя до него руками. Обратная сторона не менее важна: инструмент, о котором не сказано
 * ни в одной доке, не будет запущен никогда — а значит его и нет.
 */
@Epic("Внутренние проверки библиотеки")
class DocumentedScriptsTest {

    private static final Path SCRIPTS = Path.of("scripts");

    /** Файлы, где процедура встречается с человеком: доки, README, шаблон PR. */
    private static List<Path> documents() throws IOException {
        try (Stream<Path> docs = Files.walk(Path.of("docs"))) {
            List<Path> all = new java.util.ArrayList<>(docs.filter(p -> p.toString().endsWith(".md")).toList());
            all.add(Path.of("README.md"));
            all.add(Path.of(".github/pull_request_template.md"));
            return all;
        }
    }

    private static Set<String> mentionedScripts() throws IOException {
        Pattern reference = Pattern.compile("scripts/([a-z0-9-]+\\.sh)");
        Set<String> found = new TreeSet<>();
        for (Path doc : documents()) {
            if (!Files.exists(doc)) {
                continue;
            }
            Matcher m = reference.matcher(Files.readString(doc, StandardCharsets.UTF_8));
            while (m.find()) {
                found.add(m.group(1));
            }
        }
        return found;
    }

    private static Set<String> existingScripts() throws IOException {
        try (Stream<Path> files = Files.list(SCRIPTS)) {
            return files.map(p -> p.getFileName().toString())
                    .filter(n -> n.endsWith(".sh"))
                    .collect(java.util.stream.Collectors.toCollection(TreeSet::new));
        }
    }

    @Test
    @DisplayName("каждый упомянутый в доках скрипт существует и запускается")
    void mentionedScriptsExistAndAreExecutable() throws IOException {
        Set<String> mentioned = mentionedScripts();
        assertThat(mentioned)
                .as("ни одной ссылки на scripts/*.sh в доках — сломался сам сбор, а не доки")
                .isNotEmpty();

        for (String name : mentioned) {
            Path script = SCRIPTS.resolve(name);
            assertThat(script)
                    .as("дока обещает шаг `scripts/%s`, а файла нет: шаг процедуры невыполним", name)
                    .exists();
            assertThat(Files.isExecutable(script))
                    .as("`scripts/%s` не исполняемый — шаг споткнётся на первом же запуске", name)
                    .isTrue();
        }
    }

    @Test
    @DisplayName("каждый скрипт упомянут хотя бы в одной доке — иначе его никто не запустит")
    void everyScriptIsDocumented() throws IOException {
        Set<String> undocumented = new TreeSet<>(existingScripts());
        undocumented.removeAll(mentionedScripts());
        assertThat(undocumented)
                .as("инструмент, о котором не сказано ни в README, ни в docs/, ни в шаблоне PR, "
                        + "не будет запущен никогда — впиши его в процедуру либо удали")
                .isEmpty();
    }

    @Test
    @DisplayName("проход «отчёт глазами» описан в playbook и требует своего инструмента")
    void reportTreePassIsWiredIntoProcedure() throws IOException {
        // Ось «отчёт» пять кругов ревью закрывалась чтением allure-results — то есть проверкой,
        // что данные НА МЕСТЕ. Читаемость так не проверяется, и находки нашёл заказчик, а не
        // ревьюер. Проход 2.6 закрывает именно этот разрыв; без строки в playbook он снова
        // станет «посмотреть, если вспомню».
        assertThat(Files.readString(Path.of("docs/review-playbook.md"), StandardCharsets.UTF_8))
                .as("проход «отчёт глазами» пропал из playbook — вместе с ним пропадает "
                        + "единственная проверка ЧИТАЕМОСТИ отчёта")
                .contains("scripts/report-tree.sh")
                .contains("не открывая код тестов");

        assertThat(Files.readString(Path.of("docs/acceptance-report-standard.md"), StandardCharsets.UTF_8))
                .as("стандарт приёмки обязан называть, кто читает отчёт ПЕРВЫМ: иначе непрочитанный "
                        + "отчёт снова уедет заказчику")
                .contains("до того, как показать отчёт");
    }
}
