package io.github.kolomyychenkoai.allure.spring.internal;

/**
 * Разворачивает компактный JSON в столбик (отступы 2 пробела) для читаемых вложений отчёта.
 * <p>
 * ⚠️ Только БЕЛЫЕ ПРОБЕЛЫ: добавляет переносы/отступы между структурными символами и выкидывает
 * незначимые пробелы ВНЕ строк — но ЗНАЧЕНИЯ (ключи/строки/числа/литералы) не трогает вовсе
 * (в отличие от пересериализации через Jackson: числа/порядок ключей остаются байт-в-байт).
 * Строковые литералы (с учётом escape) копируются как есть — скобки/запятые внутри строк не ломают
 * раскладку. Пустые {@code {}}/{@code []} не разворачиваются. Не-JSON (не начинается с {@code &#123;}/
 * {@code [}) и любой сбой → исходная строка без изменений (не бросает). Allure поверх подсветит синтаксис.
 */
public final class AllureJson {

    private AllureJson() {
    }

    /** JSON в столбик (2 пробела). Не-JSON/сбой → исходник как есть. */
    public static String indent(String json) {
        if (json == null) {
            return null;
        }
        String t = json.strip();
        if (t.isEmpty() || (t.charAt(0) != '{' && t.charAt(0) != '[')) {
            return json; // не JSON-объект/массив — не трогаем
        }
        try {
            StringBuilder out = new StringBuilder(t.length() + 64);
            int depth = 0;
            boolean inStr = false;
            boolean esc = false;
            for (int i = 0; i < t.length(); i++) {
                char c = t.charAt(i);
                if (inStr) {
                    out.append(c);
                    if (esc) {
                        esc = false;
                    } else if (c == '\\') {
                        esc = true;
                    } else if (c == '"') {
                        inStr = false;
                    }
                    continue;
                }
                switch (c) {
                    case '"' -> {
                        inStr = true;
                        out.append(c);
                    }
                    case '{', '[' -> {
                        int close = nextNonWs(t, i + 1);
                        if (close >= 0 && t.charAt(close) == mate(c)) {
                            out.append(c).append(mate(c)); // пустой контейнер {} / []
                            i = close;
                        } else {
                            depth++;
                            out.append(c).append('\n');
                            pad(out, depth);
                        }
                    }
                    case '}', ']' -> {
                        depth--;
                        out.append('\n');
                        pad(out, depth);
                        out.append(c);
                    }
                    case ',' -> {
                        out.append(c).append('\n');
                        pad(out, depth);
                    }
                    case ':' -> out.append(": ");
                    case ' ', '\t', '\n', '\r' -> { /* незначимый пробел вне строк — выкидываем */ }
                    default -> out.append(c);
                }
            }
            return out.toString();
        } catch (Throwable e) {
            return json; // любой сбой — исходник, инструментирование не роняем
        }
    }

    /** Индекс следующего НЕ-пробельного символа с позиции {@code from}, либо -1. */
    private static int nextNonWs(String s, int from) {
        for (int i = from; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != ' ' && c != '\t' && c != '\n' && c != '\r') {
                return i;
            }
        }
        return -1;
    }

    /** Парная закрывающая скобка. */
    private static char mate(char open) {
        return open == '{' ? '}' : ']';
    }

    private static void pad(StringBuilder out, int depth) {
        for (int i = 0; i < depth; i++) {
            out.append("  ");
        }
    }
}
