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
 * Процедура ревью и проверка потребителей держатся на скриптах из `scripts/`, и каждый из них
 * упомянут в доках как шаг. Обещание без стража — гипотеза: переименованный или удалённый скрипт
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
        // Подчёркивание в имени обязательно: `_tools.sh` — общий кусок, который подключают
        // через source, и без него регулярка считала бы его неупомянутым, сколько его
        // ни описывай (поймано при переезде оснастки на Java).
        Pattern reference = Pattern.compile("scripts/([a-z0-9_-]+\\.(?:sh|py))");
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
                    // .py тоже: их зовут и напрямую, и из .sh — незадокументированный
                    // python-скрипт ломает шаг процедуры так же, как незадокументированный shell
                    .filter(n -> n.endsWith(".sh") || n.endsWith(".py"))
                    .collect(java.util.stream.Collectors.toCollection(TreeSet::new));
        }
    }

    @Test
    @DisplayName("каждый упомянутый в доках скрипт существует и запускается")
    void mentionedScriptsExistAndAreExecutable() throws IOException {
        Set<String> mentioned = mentionedScripts();
        assertThat(mentioned)
                .as("ни одной ссылки на scripts/*.{sh,py} в доках — сломался сам сбор, а не доки")
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
    @DisplayName("оснастку надо собрать, и об этом сказано там, где её берут в руки")
    void toolsProjectIsDocumented() throws IOException {
        // Разбор данных живёт в отдельном maven-проекте `tools/`, и это единственное
        // предусловие оснастки. Раньше предусловием был python3, и его не описывала ни одна
        // дока — узнавали о нём падением. Гейт держит, чтобы история не повторилась.
        assertThat(Path.of("tools/pom.xml")).as("проект оснастки пропал").exists();

        String howToBuild = "cd tools && mvn -q package";
        boolean documented = documents().stream().filter(Files::exists).anyMatch(doc -> {
            try {
                return Files.readString(doc, StandardCharsets.UTF_8).contains(howToBuild);
            } catch (IOException unreadable) {
                return false;
            }
        });
        assertThat(documented)
                .as("нигде не сказано, как собрать оснастку («%s») — предусловие снова "
                        + "придётся узнавать падением", howToBuild)
                .isTrue();

        assertThat(Files.readString(Path.of("scripts/_tools.sh"), StandardCharsets.UTF_8))
                .as("общий кусок скриптов перестал объяснять, что делать, когда jar не собран — "
                        + "вернётся разнобой, ради устранения которого он и появился")
                .contains(howToBuild);
    }

    @Test
    @DisplayName("проход «отчёт глазами» описан в playbook и требует своего инструмента")
    void reportTreePassIsWiredIntoProcedure() throws IOException {
        // Чтение allure-results проверяет, что данные НА МЕСТЕ, а не что отчёт ЧИТАЕМ, —
        // это разные вопросы, и второй закрывает только проход 2.6. Без строки в playbook
        // проход снова станет «посмотреть, если вспомню».
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

    @Test
    @DisplayName("проход «комментарии подряд» описан среди проходов и требует своего инструмента")
    void commentPassIsWiredIntoProcedure() {
        // Стережём две вещи: проход стоит СРЕДИ проходов (в завершающем разделе он
        // вырождается в самоотчёт) и у него есть инструмент — иначе «сплошное чтение»
        // снова станет пожеланием.
        String playbook = read("docs/review-playbook.md");
        assertThat(playbook)
                .as("проход по комментариям пропал из playbook — вместе с ним пропадает "
                        + "единственная проверка правил 2, 3 и 5, которые грепом не берутся")
                .contains("scripts/comment-scan.sh")
                .contains("СПЛОШНОЕ ЧТЕНИЕ");
        assertThat(playbook.indexOf("scripts/comment-scan.sh"))
                .as("проход уехал из раздела «2. Порядок» в завершающий — там он и вырождался "
                        + "в самоотчёт «перечитал»")
                .isLessThan(playbook.indexOf("## 3. Завершающий проход"));

        assertThat(read("docs/java-code-standard.md"))
                .as("правило «один факт — одно место» обязано называть дубль МЕЖДУ файлами: "
                        + "именно он невидим в дифе и стоил всех находок последнего круга")
                .contains("про РЕПОЗИТОРИЙ, а не про файл");
    }

    @Test
    @DisplayName("проход «стиль документа» описан среди проходов и требует отпечатка фактов")
    void docStylePassIsWiredIntoProcedure() {
        // Проход держится на двух половинах, и порознь они не работают. Список классов кринжа
        // без отпечатка — правка вслепую: вместе с абзацем уходит строка таблицы, а перечитывание
        // этого не ловит (глаз проверяет то, что осталось). Отпечаток без списка — инструмент,
        // который никто не запускает: он молчит, пока правку не начали.
        String playbook = read("docs/review-playbook.md");
        assertThat(playbook)
                .as("проход по стилю документа пропал из playbook — вместе с ним пропадает "
                        + "единственная проверка того, как текст звучит для читателя со стороны")
                .contains("scripts/doc-facts.sh")
                .contains("рассказ о процессе создания документа");
        // Вторая ось прохода — язык. Она появилась после того, как вычищенный по первой оси
        // текст всё равно читался как перевод: первая смотрит, ЧТО написано, и молчит о том,
        // на каком языке. Потеряется инструмент — ось снова станет вкусовщиной.
        assertThat(playbook)
                .as("ось «текст читается как перевод» пропала из прохода 2.10 — а это дефект "
                        + "системный: одна и та же цифра во всех доках репозитория")
                .contains("scripts/prose-scan.sh")
                .contains("по-русски так не говорят");
        // Правило родилось из дефекта: заголовки вывели из правки, чтобы отпечаток фактов
        // остался пустым, — и они остались единственными строками, которых не смотрел никто.
        // Пропадёт правило, и инвариант снова превратится в запрет трогать текст.
        assertThat(playbook)
                .as("из прохода пропало правило «отпечаток не запрещает менять» — без него "
                        + "инвариант снова начнёт выводить куски текста из проверки")
                .contains("не запрещает менять");
        // Проверки на звучание мало: «Поток управления» звучит по-русски и всё равно не
        // говорит, что внутри. Заголовки вернулись от заказчика именно с этим — «непонятно»,
        // а не «коряво».
        assertThat(playbook)
                .as("пропала проверка заголовка на содержимое — останется проверка на звучание, "
                        + "а она пропускает заголовки, по которым не понять, что в разделе")
                .contains("сказать, что будет внутри");
        assertThat(playbook.indexOf("scripts/doc-facts.sh"))
                .as("проход уехал из раздела «2. Порядок» в завершающий — там он выродится "
                        + "в самоотчёт «перечитал», как это уже было с комментариями")
                .isLessThan(playbook.indexOf("## 3. Завершающий проход"));
    }

    @Test
    @DisplayName("правила, выведенные из ложно-зелёных гейтов, остались в критериях")
    void falseGreenLessonsAreWrittenDown() {
        // Оба правила про гейт, который доказан мутацией и всё равно врёт. Пропадут из доков —
        // вернётся тот же класс дефекта, и мутация его снова не поймает.
        assertThat(read("docs/java-code-standard.md"))
                .as("правило «у отрицательной проверки должен быть якорь» пропало — без него "
                        + "тест зеленеет от МОЛЧАНИЯ канала, а не от отсутствия дефекта")
                .contains("Проверка ОТСУТСТВИЯ обязана стоять рядом с положительным якорем");
        assertThat(read("docs/review-playbook.md"))
                .as("вопрос «что гейт говорит на ПУСТОМ входе» пропал из 2.8 — мутация его "
                        + "не заменяет: она проверяет ловлю дефекта, а не поведение без данных")
                .contains("ПУСТОМ входе");
    }

    private static String read(String path) {
        try {
            return Files.readString(Path.of(path), StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            throw new AssertionError("нет файла процедуры: " + path, unreadable);
        }
    }
}
