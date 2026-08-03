package io.github.kolomyychenkoai.allure.spring.internal;

import io.qameta.allure.Allure;
import io.qameta.allure.model.Status;

import java.util.Arrays;

/**
 * Общие хелперы отчёта. Часть ({@link #step}, {@link #safe}) вызывается из inline-advice
 * (ByteBuddy копирует тело advice в чужой байткод, поэтому {@code public static}); часть
 * ({@link #attach}, {@link #render}, {@link #bodyContentType}) — из обычных листенеров/
 * фильтров/интерцепторов (REST/WireMock/Kafka), чтобы рендер значений, выбор статуса и раскладка
 * вложений были единообразны и безопасны. Это НЕ публичный API (см. {@code package-info}).
 */
public final class AllureAdviceSupport {

    /** Предел длины значения в имени шага — чтобы тяжёлый toString не раздувал отчёт. */
    private static final int MAX_LEN = 500;

    private AllureAdviceSupport() {
    }

    /**
     * Шаг отчёта для УСПЕШНО завершившейся проверки ({@code thrown == null}). Упавшую
     * проверку НЕ логируем шагом — падение Allure показывает из коробки на уровне теста
     * (сообщение + стек), фабриковать FAILED-шаг незачем.
     * <p>
     * Только при активном тест-кейсе: ассерт-инструментирование инлайнится в AbstractAssert и
     * срабатывает на ЛЮБОЙ {@code assertThat} в JVM — в т.ч. на проверочных ассертах самих
     * тестов вне активного кейса; без гейта это сыпало бы «no test case running» в лог.
     */
    public static void step(String name, Throwable thrown) {
        if (thrown != null || !Allure.getLifecycle().getCurrentTestCase().isPresent()) {
            return;
        }
        Allure.step(name, Status.PASSED);
    }

    /** Больше — не печатаем поэлементно: имя шага всё равно обрежется по {@link #MAX_LEN}. */
    private static final int MAX_ARRAY_ITEMS = 50;
    /** Двоичное содержимое поэлементно нечитаемо — как и в SQL-вложениях, показываем размер. */
    private static final int MAX_BINARY = 32;

    /**
     * Безопасный рендер значения для ИМЕНИ ШАГА: не бросает ({@code toString()} пользовательского
     * объекта может кинуть), длину ограничивает {@link #MAX_LEN}. При сбое — {@code "<?>"}.
     * <p>
     * Имя шага читает ЧЕЛОВЕК (отчёт принимают вручную), поэтому здесь же убирается технический
     * мусор, который иначе течёт во все модули сразу — это единственная общая точка рендера
     * значений для AssertJ, Hamcrest, Jupiter, Spring-ассертов, Mockito и WireMock-verify:
     * <ul>
     *   <li>массивы — поэлементно, ВКЛЮЧАЯ примитивные (без этого {@code assertThat(bytes)}
     *       давал {@code [B@4a3f2b1c});</li>
     *   <li>лямбды и method-reference → {@code <лямбда>} (было {@code Demo$$Lambda/0x…@1a2b});</li>
     *   <li>объект без своего {@code toString()} → {@code <ПростоеИмя>} (было {@code Класс@хэш}).</li>
     * </ul>
     */
    public static String safe(Object value) {
        String s;
        try {
            s = renderForName(value);
        } catch (Throwable t) {
            s = "<?>";
        }
        if (s != null && s.length() > MAX_LEN) {
            s = s.substring(0, MAX_LEN) + "…";
        }
        return s;
    }

    private static String renderForName(Object value) {
        if (value == null) {
            return "null";
        }
        Class<?> type = value.getClass();
        // Проверяем И synthetic, И маркер имени: synthetic бывает у прокси и записей компилятора,
        // а «$$» без synthetic — у классов пользователя с таким именем. Нужны оба признака.
        if (type.isSynthetic() && type.getName().contains("$$Lambda")) {
            return "<лямбда>";
        }
        if (type.isArray()) {
            return array(value);
        }
        String text = String.valueOf(value);
        // toString() не переопределён → Object.toString() = имя класса + '@' + hex(hashCode).
        // Сравнение ТОЧНОЕ, поэтому легитимные toString с '@' внутри не страдают.
        // hashCode() зовём только ПОСЛЕ успешного toString — не будим ленивые прокси раньше времени.
        return text != null && text.equals(type.getName() + "@" + Integer.toHexString(value.hashCode()))
                ? "<" + type.getSimpleName() + ">"
                : text;
    }

    private static String array(Object value) {
        if (value instanceof Object[] objects) {
            // Элементы рендерим ТЕМ ЖЕ правилом, а не deepToString: varargs-аргументы приходят
            // массивом (AssertJ satisfies/matches — это Consumer<T>...), и лямбда внутри массива
            // иначе печаталась бы как «$$Lambda/0x…@1a2b» в обход всей очистки.
            StringBuilder out = new StringBuilder("[");
            for (int i = 0; i < Math.min(objects.length, MAX_ARRAY_ITEMS); i++) {
                out.append(i > 0 ? ", " : "").append(renderForName(objects[i]));
            }
            return out.append(objects.length > MAX_ARRAY_ITEMS ? ", … всего " + objects.length + "]" : "]")
                    .toString();
        }
        if (value instanceof byte[] bytes) {
            return bytes.length > MAX_BINARY ? "<двоичные данные, " + bytes.length + " байт>" : Arrays.toString(bytes);
        }
        int length = java.lang.reflect.Array.getLength(value);
        StringBuilder out = new StringBuilder("[");
        for (int i = 0; i < Math.min(length, MAX_ARRAY_ITEMS); i++) {
            out.append(i > 0 ? ", " : "").append(java.lang.reflect.Array.get(value, i));
        }
        return out.append(length > MAX_ARRAY_ITEMS ? ", … всего " + length + "]" : "]").toString();
    }

    /**
     * Кладёт метаданные (заголовки/строку статуса и т.п.) и ТЕЛО ОТДЕЛЬНЫМИ вложениями. JSON-тело
     * разворачиваем в столбик ({@link AllureJson#indent} — только пробелы/переносы, значения не
     * трогаем) и помечаем {@code application/json} → в отчёте оно и с ОТСТУПАМИ, и с подсветкой
     * синтаксиса (Allure красит {@code application/json}). Не-JSON тело — как есть, {@code text/plain}.
     * Пустое тело не кладём. JSON не пересериализуем (числа/порядок ключей байт-в-байт) и не тянем
     * JSON-зависимость.
     */
    public static void attach(String metaName, String meta, String bodyName, String body) {
        Allure.addAttachment(metaName, "text/plain", meta);
        attachBody(bodyName, body);
    }

    /**
     * Кладёт ТОЛЬКО тело отдельным вложением (без метаданных): JSON → {@code application/json} +
     * развёрнуто в столбик ({@link AllureJson#indent}), иначе {@code text/plain} как есть. Пустое/
     * {@code null} тело не кладём. Для случаев, где метаданные общие, а тел несколько (напр. Kafka —
     * value КАЖДОЙ принятой записи своим вложением).
     */
    public static void attachBody(String name, String body) {
        if (body != null && !body.isBlank()) {
            String type = bodyContentType(body);
            String content = "application/json".equals(type) ? AllureJson.indent(body) : body;
            Allure.addAttachment(name, type, content);
        }
    }

    /**
     * Безопасный ПОЛНЫЙ рендер значения для ВЛОЖЕНИЯ (в отличие от {@link #safe} — БЕЗ обрезки по
     * длине: тело/значение во вложении режем незачем). Не бросает: {@code toString()} объекта может
     * кинуть → тогда {@code "<?>"} (иначе упал бы весь шаг). Для имени шага используй {@link #safe}.
     */
    public static String render(Object value) {
        try {
            return String.valueOf(value);
        } catch (Throwable t) {
            return "<?>";
        }
    }

    /** {@code application/json}, если строка (после trim) начинается с {@code &#123;}/{@code [}; иначе {@code text/plain}. */
    public static String bodyContentType(String body) {
        String t = body == null ? "" : body.stripLeading();
        return (t.startsWith("{") || t.startsWith("[")) ? "application/json" : "text/plain";
    }
}
