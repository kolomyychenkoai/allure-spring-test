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
 * <p>
 * <b>Три рендера значения — три РАЗНЫХ места отчёта.</b> Путать их дорого: имя шага и тело
 * вложения живут по противоположным правилам, а перепутанный рендер деградирует отчёт МОЛЧА
 * (имя вложения, mime и признак «непусто» при этом не меняются, поэтому инвентарь такое не ловит).
 *
 * <table border="1">
 *   <caption>Что чем рендерить</caption>
 *   <tr><th>функция</th><th>куда</th><th>одна строка</th><th>обрезка 500</th><th>чистка мусора</th></tr>
 *   <tr><td>{@link #safe}</td><td>ИМЯ шага</td><td>да</td><td>да</td><td>да</td></tr>
 *   <tr><td>{@link #safeValue}</td><td>ЗНАЧЕНИЕ во вложении</td><td>нет</td><td>нет</td><td>да</td></tr>
 *   <tr><td>{@link #render}</td><td>СЫРОЕ тело (JSON, payload Kafka)</td><td>нет</td><td>нет</td><td>нет</td></tr>
 * </table>
 *
 * «Чистка мусора» — массивы поэлементно, лямбда → {@code <лямбда>}, объект без {@code toString}
 * → {@code <Класс>}. Инвариант: {@code safe} зовут ТОЛЬКО для имён шагов.
 */
public final class AllureAdviceSupport {

    /** Предел длины значения в имени шага — чтобы тяжёлый toString не раздувал отчёт. */
    private static final int MAX_LEN = 500;

    /**
     * Предел длины значения во ВЛОЖЕНИИ. На три порядка больше, чем у имени шага: во вложение
     * идут ЗА СОДЕРЖАНИЕМ, и лимит имени шага (500) обрезал бы его до бесполезного —
     * тихая деградация, ради которой и разведены {@link #safe} и {@link #safeValue}.
     * Совсем без потолка одно поле {@code @Lob} превращает вложение в мегабайты, поэтому
     * граница есть — просто далеко.
     */
    private static final int MAX_VALUE_LEN = 500_000;

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

    /** Больше — не печатаем поэлементно: перечисление на сотни элементов уже нечитаемо. */
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
     *       даёт {@code [B@4a3f2b1c});</li>
     *   <li>лямбды и method-reference → {@code <лямбда>} (без этого {@code Demo$$Lambda/0x…@1a2b});</li>
     *   <li>объект без своего {@code toString()} → {@code <ПростоеИмя>} (без этого {@code Класс@хэш}).</li>
     * </ul>
     * <p>
     * ⚠️ Только для ИМЕНИ шага. Для значения во ВЛОЖЕНИИ — {@link #safeValue}: там схлопывание
     * и обрезка убивают содержание (см. таблицу ролей в javadoc класса).
     */
    public static String safe(Object value) {
        // Имя шага — ОДНА строка: многострочное значение (JSON-тело, стек, SQL) разрывает вёрстку
        // отчёта, который читают вручную. Схлопываем пробельное ДО обрезки, иначе в лимит попадут
        // переносы вместо содержимого.
        String s = WHITESPACE.matcher(clean(value)).replaceAll(" ").trim();
        if (s.length() > MAX_LEN) {
            int cut = MAX_LEN;
            if (Character.isHighSurrogate(s.charAt(cut - 1))) {
                cut--; // не рвём суррогатную пару пополам — иначе в отчёте «кракозябра»
            }
            s = s.substring(0, cut) + "…";
        }
        return s;
    }

    /**
     * Безопасный рендер значения для ВЛОЖЕНИЯ: та же чистка, что у {@link #safe} (массивы
     * поэлементно, лямбда, identity-хэш, бросающий {@code toString}), но БЕЗ схлопывания
     * в одну строку и БЕЗ обрезки по {@link #MAX_LEN}.
     * <p>
     * Отдельно от {@code safe} потому, что во вложении многострочность — это и есть содержание:
     * диф near-miss у WireMock, комментарий changeset'а, сущность из БД. Схлопнутое тело выглядит
     * «нормальным» (имя, mime и «непусто» на месте), поэтому такую деградацию не видит ни
     * инвентарь отчёта, ни тест, проверяющий вложение на {@code isNotBlank} — она тихая.
     * <p>
     * Потолок массива (50 элементов) остаётся: он про читаемость перечисления, а не про длину
     * имени шага. Для СЫРОГО тела (JSON, payload) нужен {@link #render} — там чистка не нужна.
     */
    public static String safeValue(Object value) {
        String s = clean(value);
        if (s.length() <= MAX_VALUE_LEN) {
            return s;
        }
        // Обрезка НАЗВАНА, а не сделана молча: иначе усечённое вложение не отличить от полного.
        return s.substring(0, MAX_VALUE_LEN)
                + "\n… обрезано: значение " + s.length() + " символов, показан первый "
                + MAX_VALUE_LEN;
    }

    /** Общий конвейер чистки для {@link #safe} и {@link #safeValue}. Никогда не бросает и не {@code null}. */
    private static String clean(Object value) {
        // ⚠️ Ленивое значение НЕ трогаем: String.valueOf ниже позвало бы toString() прокси,
        // а это поход в БД. Почему страж стоит здесь — javadoc JpaLaziness.
        if (JpaLaziness.notLoaded(value)) {
            return JpaLaziness.NOT_LOADED;
        }
        String s;
        try {
            s = clean(value, 0);
        } catch (Throwable t) {
            s = "<?>";
        }
        return s == null ? "<?>" : s;
    }

    /** Предел вложенности массивов: у рукописного обхода нет детекта циклов, как у deepToString. */
    private static final int MAX_DEPTH = 4;
    private static final java.util.regex.Pattern WHITESPACE = java.util.regex.Pattern.compile("\\s+");

    private static String clean(Object value, int depth) {
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
            return depth >= MAX_DEPTH ? "[…]" : array(value, depth);
        }
        String text = String.valueOf(value);
        if (text == null) {
            return "<null-toString>"; // toString() вернул null — наружу null не отдаём
        }
        return isIdentityToString(text, value, type) ? "<" + readableName(type) + ">" : text;
    }

    /**
     * Похоже ли на {@code Object.toString()} (= имя класса + '@' + hex(hashCode)) — то есть
     * {@code toString()} не переопределён и в имя шага течёт хэш.
     * <p>
     * Сначала дешёвая проверка ФОРМЫ по префиксу, и только потом {@code hashCode()} — и он
     * в своём try: у неинициализированного Hibernate-прокси он и бросает
     * ({@code LazyInitializationException}), и будит прокси. Исправный {@code toString()}
     * из-за сломанного {@code hashCode()} терять нельзя.
     */
    private static boolean isIdentityToString(String text, Object value, Class<?> type) {
        String name = type.getName();
        if (text.length() <= name.length() || text.charAt(name.length()) != '@' || !text.startsWith(name)) {
            return false;
        }
        try {
            return text.equals(name + "@" + Integer.toHexString(value.hashCode()));
        } catch (Throwable keepText) {
            return false;
        }
    }

    /** У анонимных классов {@code getSimpleName()} пуст — берём то, чем он является. */
    private static String readableName(Class<?> type) {
        String simple = type.getSimpleName();
        if (!simple.isEmpty()) {
            return simple;
        }
        Class<?>[] interfaces = type.getInterfaces();
        if (interfaces.length > 0) {
            return interfaces[0].getSimpleName();
        }
        Class<?> parent = type.getSuperclass();
        return parent == null ? "?" : parent.getSimpleName();
    }

    private static String array(Object value, int depth) {
        if (value instanceof byte[] bytes) {
            return bytes.length > MAX_BINARY ? "<двоичные данные, " + bytes.length + " байт>" : Arrays.toString(bytes);
        }
        boolean objects = value instanceof Object[];
        int length = java.lang.reflect.Array.getLength(value);
        StringBuilder out = new StringBuilder("[");
        int shown = 0;
        // Элементы Object[] рендерим ТЕМ ЖЕ правилом, а не deepToString: varargs приходят массивом
        // (AssertJ satisfies/matches — это Consumer<T>...), и лямбда внутри массива иначе
        // печаталась бы как «$$Lambda/0x…@1a2b» в обход всей очистки.
        while (shown < Math.min(length, MAX_ARRAY_ITEMS) && out.length() <= MAX_LEN) {
            Object item = java.lang.reflect.Array.get(value, shown);
            out.append(shown > 0 ? ", " : "").append(objects ? clean(item, depth + 1) : item);
            shown++;
        }
        return out.append(shown < length ? ", … всего " + length + "]" : "]").toString();
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
     * Рендер СЫРОГО тела вложения (JSON, payload Kafka): {@code String.valueOf} без обрезки и
     * БЕЗ чистки. Не бросает: {@code toString()} объекта может кинуть → тогда {@code "<?>"}
     * (иначе упал бы весь шаг).
     * <p>
     * Отличие от {@link #safeValue}, с которым его и путают: тот чистит технический мусор
     * (массивы поэлементно, лямбда, identity-хэш), а этот отдаёт текст как есть — потому что
     * тело уже сериализовано и любая «чистка» его исказит. Для имени шага — {@link #safe}.
     */
    public static String render(Object value) {
        if (JpaLaziness.notLoaded(value)) {
            return JpaLaziness.NOT_LOADED; // см. clean(): toString() прокси — это SELECT
        }
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
