package io.github.kolomyychenkoai.allure.spring.unit;

import io.qameta.allure.Epic;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Тест связки «дока обещает инструмент — инструмент существует», в обе стороны.
 * <p>
 * Процедура ревью и проверка потребителей держатся на скриптах из `scripts/`, и каждый из них
 * упомянут в доках как шаг. Обещание без теста — гипотеза: переименованный или удалённый скрипт
 * оставляет в playbook'е шаг, который просто не выполнить, и узнать об этом можно только
 * дойдя до него руками. Обратная сторона не менее важна: инструмент, о котором не сказано
 * ни в одной доке, не будет запущен никогда — а значит его и нет.
 */
@Epic("Внутренние проверки библиотеки")
class DocumentedScriptsTest {

    private static final Path SCRIPTS = Path.of("scripts");
    private static final Path PLAYBOOK = Path.of("docs/review-playbook.md");
    private static final Path PR_TEMPLATE = Path.of(".github/pull_request_template.md");
    private static final Path MANDATES = Path.of(".claude/agents");

    /**
     * Строки матрицы шаблона PR, которые осями не являются, и почему это законно.
     * Реестр нужен, чтобы гейт не требовал равенства множеств: у шаблона и playbook разные
     * жанры, и строка «перемер утверждений» не описывает предмет, а называет приём.
     * Появится ещё одна строка не из матрицы осей — гейт покраснеет и потребует решения.
     */
    private static final Map<String, String> ROWS_BEYOND_AXES = Map.of(
            "перемер утверждений",
            "это проход 2.1, а не ось: перемеряется утверждение по ЛЮБОЙ оси",
            "call sites изменённых общих точек",
            "это артефакт из раздела «Артефакты — по чему проходим», а не ось");

    /**
     * Мандаты, у которых оси в матрице охвата нет, и почему это законно.
     * Матрица описывает чтение ДИФА; мандат, работающий до первой правки, в ней не помещается.
     */
    private static final Map<String, String> MANDATES_OUTSIDE_MATRIX = Map.of(
            "plan-reader",
            "читает ЗАМЫСЕЛ до первой правки; матрица охвата описывает диф, оси у него нет");

    /**
     * Файлы, где процедура встречается с человеком: доки, README, шаблон PR, мандаты.
     * <p>
     * Мандаты попали сюда не для полноты: они обещают пути и инструменты наравне с доками,
     * а поймано это было мандатом, который звал эталон инвентаря из несуществующего каталога.
     */
    private static List<Path> documents() throws IOException {
        List<Path> all = new ArrayList<>();
        try (Stream<Path> docs = Files.walk(Path.of("docs"))) {
            docs.filter(p -> p.toString().endsWith(".md")).forEach(all::add);
        }
        try (Stream<Path> mandates = Files.list(MANDATES)) {
            mandates.filter(p -> p.toString().endsWith(".md")).forEach(all::add);
        }
        all.add(Path.of("README.md"));
        all.add(Path.of(".github/pull_request_template.md"));
        return all;
    }

    private static Set<String> mentionedScripts() throws IOException {
        // Подчёркивание в имени обязательно: `_tools.sh` — общий кусок, который подключают
        // через source, и без него регулярка считала бы его неупомянутым, сколько его
        // ни описывай (поймано при переезде инструментов на Java).
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
                    .collect(Collectors.toCollection(TreeSet::new));
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
    @DisplayName("инструменты надо собрать, и об этом сказано там, где их берут в руки")
    void toolsProjectIsDocumented() throws IOException {
        // Разбор данных живёт в отдельном maven-проекте `tools/`, и это единственное
        // предусловие инструментов. Раньше предусловием был python3, и его не описывала ни одна
        // дока — узнавали о нём падением. Гейт держит, чтобы история не повторилась.
        assertThat(Path.of("tools/pom.xml")).as("проект инструментов пропал").exists();

        String howToBuild = "cd tools && mvn -q package";
        boolean documented = documents().stream().filter(Files::exists).anyMatch(doc -> {
            try {
                return Files.readString(doc, StandardCharsets.UTF_8).contains(howToBuild);
            } catch (IOException unreadable) {
                return false;
            }
        });
        assertThat(documented)
                .as("нигде не сказано, как собрать инструменты («%s») — предусловие снова "
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
        // Чек-лист и лупа у прохода были с 2026-08-10 и не удержали его два круга подряд.
        // Удержало другое — второй читатель. Снять это правило молча теперь нельзя.
        assertThat(playbook)
                .as("из прохода 2.9 убрано требование НЕЗАВИСИМОГО читателя. Оно и есть то "
                        + "единственное, что дало находки: автор своих комментариев не видит, "
                        + "и три круга подряд ось закрывалась словом «перечитал»")
                .contains("ноль находок у независимого = проход не состоялся");
        assertThat(playbook)
                .as("из прохода 2.9 убран гейт «правка не тронула код» — без него «изменились "
                        + "только комментарии» снова становится самоотчётом")
                .contains("scripts/comments-only.py");
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

        // Третья ось про то, что текст тяжёлый при коротких и правильных фразах. Цифрами она
        // не берётся: документ, возвращённый со словами «сложно написано», по всем мерам
        // совпадал с принятым. Пропадёт ось — вернётся правка на глаз.
        assertThat(playbook)
                .as("пропала ось «текст не читается с первого раза» — а её ни одна цифра "
                        + "не заменяет, проверено замером двух документов")
                .contains("ярлык вместо имени");
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

    /**
     * Ключ строки таблицы: имя до первой скобки или двоеточия, со свёрнутыми пробелами.
     * Полное имя сравнивать нельзя — шаблон PR намеренно несёт в скобках подсказку с командой
     * (`scripts/repeat.sh`), а playbook её не несёт и не должен: это разные жанры текста.
     * Двоеточие расщепляет одну ось на несколько строк чек-листа («отчёт: тела вложений»
     * и «отчёт: дерево прочитано глазами» — обе про ось «отчёт»).
     */
    private static String rowKey(String cell) {
        String name = cell.trim();
        int cut = name.length();
        for (char c : new char[]{'(', ':'}) {
            int at = name.indexOf(c);
            if (at >= 0 && at < cut) {
                cut = at;
            }
        }
        return name.substring(0, cut).replace("`", "").trim().replaceAll("\\s+", " ");
    }

    /** Строки таблицы, идущей сразу после заголовка: без шапки и без разделителя. */
    private static List<String[]> tableAfter(Path doc, String heading) {
        String text = read(doc.toString());
        int start = text.indexOf(heading);
        assertThat(start)
                .as("в %s пропал заголовок «%s» — разбор таблицы ниже стал бы пустым, "
                        + "а пустой разбор гейт принял бы за «расхождений нет»", doc, heading)
                .isNotNegative();
        List<String[]> rows = new ArrayList<>();
        boolean seenSeparator = false;
        for (String line : text.substring(start).lines().toList()) {
            String trimmed = line.trim();
            if (!trimmed.startsWith("|")) {
                if (seenSeparator) {
                    break;
                }
                continue;
            }
            if (trimmed.replace("|", "").replace("-", "").replace(":", "").isBlank()) {
                seenSeparator = true;
                continue;
            }
            if (seenSeparator) {
                rows.add(trimmed.substring(1).split("\\|", -1));
            }
        }
        return rows;
    }

    /** Оси охвата: имя оси → мандаты, которым она принадлежит. */
    private static Map<String, List<String>> axes() {
        Map<String, List<String>> axes = new LinkedHashMap<>();
        for (String[] row : tableAfter(PLAYBOOK, "### Оси — что смотрим")) {
            // Ровно четыре: три ячейки плюс хвостовая пустая от замыкающей трубы. Меньше или
            // больше значит, что труба стоит ВНУТРИ ячейки — и тогда разбор съедет на колонку,
            // а сообщение ниже обвинит несуществующий мандат вместо разметки.
            assertThat(row.length)
                    .as("строка матрицы охвата разобралась не на три ячейки, а на %d: «%s». "
                            + "Скорее всего труба стоит внутри ячейки — экранируй её",
                            row.length - 1, String.join("|", row))
                    .isEqualTo(4);
            // Составная клетка «architect + security» — законная форма: ось делят двое.
            axes.put(rowKey(row[0]), Arrays.stream(row[2].split("\\+"))
                    .map(String::trim).filter(m -> !m.isEmpty()).toList());
        }
        // Якорь на пустой вход: разбор, сломавшийся о правку разметки, обязан покраснеть сам,
        // а не отдать пустую карту, на которой все сверки ниже сойдутся (playbook, 2.8).
        //
        // Порог, а не точное число: точное пришлось бы поднимать на каждую новую ось, то есть
        // держать самосчёт в тесте. Чего порог НЕ ловит: осознанное удаление двух-трёх осей
        // сразу ИЗ ОБОИХ файлов. Это ослабление, и его ловит чтение удалённых строк
        // (мандат gatekeeper), а не гейт.
        assertThat(axes)
                .as("из матрицы охвата разобрано %d осей — столько их не бывает, сломался разбор "
                        + "таблицы, а не таблица", axes.size())
                .hasSizeGreaterThan(10);
        return axes;
    }

    private static Set<String> mandateFiles() throws IOException {
        try (Stream<Path> files = Files.list(MANDATES)) {
            return files.map(f -> f.getFileName().toString())
                    .filter(n -> n.endsWith(".md"))
                    .map(n -> n.substring(0, n.length() - ".md".length()))
                    .collect(Collectors.toCollection(TreeSet::new));
        }
    }

    @Test
    @DisplayName("у каждой оси охвата есть владелец, и у владельца есть мандат")
    void everyAxisHasAMandateWithAFile() throws IOException {
        Map<String, List<String>> axes = axes();
        Set<String> files = mandateFiles();

        Set<String> ownerless = new TreeSet<>();
        Set<String> missingFiles = new TreeSet<>();
        axes.forEach((axis, owners) -> {
            if (owners.isEmpty() || owners.contains("—")) {
                ownerless.add(axis);
            }
            owners.stream().filter(o -> !o.equals("—") && !files.contains(o)).forEach(missingFiles::add);
        });

        assertThat(ownerless)
                .as("ось без владельца. Прочерк в колонке — это не «пока никто», а дефект "
                        + "процедуры: пять таких осей дали большинство находок круга сентября "
                        + "2026 именно потому, что их не смотрел никто по обязанности")
                .isEmpty();
        assertThat(missingFiles)
                .as("матрица охвата называет мандат, которого нет в %s: ось объявлена закрытой "
                        + "за тем, кого не существует", MANDATES)
                .isEmpty();
    }

    @Test
    @DisplayName("каждый мандат владеет осью либо назван исключением с причиной")
    void everyMandateOwnsAnAxisOrIsDeclared() throws IOException {
        Set<String> owners = new TreeSet<>();
        axes().values().forEach(owners::addAll);

        Set<String> homeless = new TreeSet<>(mandateFiles());
        homeless.removeAll(owners);
        homeless.removeAll(MANDATES_OUTSIDE_MATRIX.keySet());

        assertThat(homeless)
                .as("мандат есть файлом, но ни одной оси не закрывает — его не позовут никогда. "
                        + "Либо впиши его в колонку владельцев матрицы охвата, либо назови "
                        + "исключением в MANDATES_OUTSIDE_MATRIX с причиной")
                .isEmpty();

        assertThat(mandateFiles())
                .as("исключение названо для мандата, файла которого нет: реестр пережил свой "
                        + "предмет и теперь разрешает несуществующее")
                .containsAll(MANDATES_OUTSIDE_MATRIX.keySet());
    }

    @Test
    @DisplayName("матрица шаблона PR и оси охвата описывают одно и то же")
    void pullRequestTemplateCoversEveryAxis() {
        Set<String> axes = new TreeSet<>(axes().keySet());
        Set<String> rows = new TreeSet<>();
        for (String[] row : tableAfter(PR_TEMPLATE, "## Матрица охвата")) {
            rows.add(rowKey(row[0]));
        }
        assertThat(rows)
                .as("из матрицы шаблона PR разобрано %d строк — сломался разбор, а не шаблон",
                        rows.size())
                .hasSizeGreaterThan(10);

        Set<String> withoutRow = new TreeSet<>(axes);
        withoutRow.removeAll(rows);
        assertThat(withoutRow)
                .as("ось есть в playbook и нет в шаблоне PR. Шаблон — единственное место, где "
                        + "процедура встречается с человеком в нужный момент: ось без строки "
                        + "не будет закрыта никогда, и пустой клетки, по которой это видно, тоже "
                        + "не будет")
                .isEmpty();

        Set<String> extra = new TreeSet<>(rows);
        extra.removeAll(axes);
        extra.removeAll(ROWS_BEYOND_AXES.keySet());
        assertThat(extra)
                .as("в шаблоне PR строка, которой нет среди осей охвата. Либо ось переименована "
                        + "в одном месте из двух, либо это приём, а не ось — тогда назови его "
                        + "в ROWS_BEYOND_AXES с причиной")
                .isEmpty();

        // Обратная сторона, как у MANDATES_OUTSIDE_MATRIX: реестр не должен пережить свой
        // предмет. Иначе запись продолжит молча разрешать строку, которой в шаблоне давно нет.
        assertThat(rows)
                .as("исключение названо для строки, которой в шаблоне PR больше нет: реестр "
                        + "пережил свой предмет и теперь разрешает несуществующее")
                .containsAll(ROWS_BEYOND_AXES.keySet());
    }

    @Test
    @DisplayName("README перечисляет ровно те мандаты, которые лежат файлами")
    void readmeListsExactlyTheMandateFiles() throws IOException {
        String readme = read("README.md");
        Matcher line = Pattern.compile("`\\.claude/agents/` — мандаты ревьюеров \\(([^)]+)\\)")
                .matcher(readme);
        assertThat(line.find())
                .as("README перестал перечислять мандаты в ожидаемой форме — сверка молча "
                        + "перестала бы что-либо проверять")
                .isTrue();
        Set<String> listed = new TreeSet<>(List.of(line.group(1).split("/")));
        assertThat(listed)
                .as("перечень мандатов в README разошёлся с каталогом %s. Читатель README "
                        + "узнаёт из него, кого звать на ревью, — недостающего не позовут",
                        MANDATES)
                .isEqualTo(mandateFiles());
    }

    private static String read(String path) {
        try {
            return Files.readString(Path.of(path), StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            throw new AssertionError("нет файла процедуры: " + path, unreadable);
        }
    }
}
