package io.github.kolomyychenkoai.allure.spring.tools;

/**
 * Единственный вход в инструменты ревью: подкоманда выбирает инструмент.
 * <p>
 * Раньше это были два питон-скрипта, heredoc внутри shell-скрипта и инлайн {@code python3 -c}
 * ради метки времени — три формы одного и того же, расходившиеся при первой правке. Теперь
 * форма одна, а shell остался только там, где он и уместен: гонять прогоны и считать медианы.
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

        if (args.length == 0) {
            System.err.println(usage());
            System.exit(2);
        }
        String[] rest = new String[args.length - 1];
        System.arraycopy(args, 1, rest, 0, rest.length);

        int code = switch (args[0]) {
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
        System.exit(code);
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
