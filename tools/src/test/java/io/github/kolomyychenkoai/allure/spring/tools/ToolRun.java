package io.github.kolomyychenkoai.allure.spring.tools;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Запуск подкоманды в тесте: перехватывает оба потока и код возврата.
 * <p>
 * Инструменты общаются с миром ровно через это — текст и код, — поэтому и проверяются
 * через это, а не через внутренние методы. Тест, дёргающий приватную логику, разошёлся бы
 * с тем, что видит скрипт.
 */
record ToolRun(int code, String out, String err) {

    static ToolRun of(String... args) {
        PrintStream savedOut = System.out;
        PrintStream savedErr = System.err;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        try {
            // UTF-8 явно: под LC_ALL=C кодировка по умолчанию превратила бы кириллицу
            // в вопросительные знаки, и тесты сравнивали бы мусор с мусором.
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
            int code = ReviewTools.dispatch(args);
            return new ToolRun(code, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
        } catch (Exception failed) {
            throw new AssertionError("подкоманда упала исключением вместо кода возврата: " + failed, failed);
        } finally {
            System.setOut(savedOut);
            System.setErr(savedErr);
        }
    }

    /** Строки вывода без хвостовых пустых — сравнивать удобнее, смысла не теряем. */
    String[] lines() {
        return out.stripTrailing().split("\n", -1);
    }

    static Path write(Path dir, String name, String content) throws IOException {
        Files.createDirectories(dir);
        Path file = dir.resolve(name);
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }
}
