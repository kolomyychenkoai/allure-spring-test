package io.github.kolomyychenkoai.allure.spring.tools;

/**
 * Единственный вход в инструменты ревью: подкоманда выбирает инструмент.
 * <p>
 * Раньше это были два питон-скрипта, heredoc внутри shell-скрипта и инлайн {@code python3 -c}
 * ради метки времени — три формы одного и того же, расходившиеся при первой правке. Теперь
 * форма одна, а shell остался только там, где он и уместен: гонять прогоны и считать медианы.
 *
 * <h2>Коды возврата</h2>
 * Договор один на все подкоманды, потому что скрипты различают их именно по коду:
 * <ul>
 *   <li><b>0</b> — отработало;</li>
 *   <li><b>1</b> — <b>проверка провалена</b>. Только у {@code attribution}: шаг уехал в чужой
 *       кейс, маркеров нет вовсе, число маркеров не сошлось с ожидаемым. Сюда же попадает
 *       отсутствие каталога результатов: для проверки это не ошибка вызова, а красный
 *       результат — нечего проверять значит провал, а не «нарушений нет»;</li>
 *   <li><b>2</b> — позвали неправильно либо данных нет: неизвестная подкоманда или флаг,
 *       нет каталога, в каталоге нет ожидаемых файлов, нечисловой аргумент.</li>
 * </ul>
 * Зелёный код на пустом входе запрещён: «0 тестов, всё хорошо» читается как успех и прячет
 * сломавшийся сбор.
 *
 * @see <a href="../../../../../../../../docs/adr/0003-review-tooling-on-java.md">ADR 0003</a>
 */
public final class ReviewTools {

    private ReviewTools() {
    }

    public static void main(String[] args) throws Exception {
        // Вывод жёстко в UTF-8, а не в кодировку локали. Скрипты ревью намеренно работают
        // под LC_ALL=C (иначе BSD sed и grep спотыкаются о кириллицу), и в этой локали JVM
        // печатала бы весь русский текст вопросительными знаками. Поймано сверкой с эталоном.
        System.setOut(new java.io.PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.out),
                true, java.nio.charset.StandardCharsets.UTF_8));
        System.setErr(new java.io.PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.err),
                true, java.nio.charset.StandardCharsets.UTF_8));

        System.exit(dispatch(args));
    }

    /**
     * Разбор подкоманды отдельно от {@code main}: {@code main} только настраивает вывод
     * и выходит с кодом, а сюда можно позвать из теста, не роняя JVM через {@code System.exit}.
     */
    static int dispatch(String[] args) throws Exception {
        if (args.length == 0) {
            System.err.println(usage());
            return 2;
        }
        String[] rest = new String[args.length - 1];
        System.arraycopy(args, 1, rest, 0, rest.length);

        return switch (args[0]) {
            case "report-tree" -> ReportTree.run(rest);
            case "snapshot" -> Snapshot.run(rest);
            case "attribution" -> Attribution.run(rest);
            // Часы отдельной подкомандой: macOS `date` не умеет миллисекунды, а bash здесь 3.2
            // и не знает EPOCHREALTIME. Старт JVM (~75 мс) против прогона в десятки секунд —
            // шум, и он одинаков для обеих сторон замера, поэтому дельту не искажает.
            case "now" -> {
                System.out.println(System.currentTimeMillis());
                yield 0;
            }
            default -> {
                System.err.println("неизвестная подкоманда: " + args[0] + "\n\n" + usage());
                yield 2;
            }
        };
    }

    private static String usage() {
        return """
                Инструменты ревью. Использование: java -jar review-tools.jar <подкоманда> [аргументы]

                  report-tree <каталог-результатов> [--all]     дерево шагов отчёта
                  snapshot    <каталог-сервиса>                 исходы тестов + поведение
                  attribution <каталог-сервиса> [N] [префикс]   шаг обязан лежать в своём кейсе
                  now                                           метка времени в миллисекундах

                Зовут их скрипты из scripts/ — напрямую нужно редко.""";
    }
}
