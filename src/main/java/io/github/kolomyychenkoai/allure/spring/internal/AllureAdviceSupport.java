package io.github.kolomyychenkoai.allure.spring.internal;

import io.qameta.allure.Allure;
import io.qameta.allure.model.Status;

import java.util.Arrays;

/**
 * Общие хелперы, вызываемые из inline-advice (ByteBuddy копирует тело advice в чужой
 * байткод, поэтому хелперы обязаны быть {@code public static}). Это НЕ публичный API
 * (см. {@code package-info}). Переиспользуется всеми инструментирующими модулями
 * (ассерты, Kafka, WireMock, Mockito…), чтобы рендер значений и выбор статуса были
 * единообразны и безопасны.
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

    /**
     * Безопасный рендер значения для имени шага: не бросает ({@code toString()}
     * пользовательского объекта может кинуть), массивы печатает поэлементно
     * ({@code deepToString}), длину ограничивает {@link #MAX_LEN}. При сбое — {@code "<?>"}.
     */
    public static String safe(Object value) {
        String s;
        try {
            s = (value instanceof Object[]) ? Arrays.deepToString((Object[]) value) : String.valueOf(value);
        } catch (Throwable t) {
            s = "<?>";
        }
        if (s != null && s.length() > MAX_LEN) {
            s = s.substring(0, MAX_LEN) + "…";
        }
        return s;
    }

    /**
     * Кладёт метаданные (заголовки/строку статуса и т.п.) и ТЕЛО ОТДЕЛЬНЫМИ вложениями. Тело —
     * с content-type {@code application/json}, если похоже на JSON: тогда Allure-репорт сам
     * форматирует его красиво (с отступами/подсветкой), а не одной строкой. Пустое тело не кладём.
     * Так мы НЕ пересериализуем (храним точные байты) и не тянем JSON-зависимость.
     */
    public static void attach(String metaName, String meta, String bodyName, String body) {
        Allure.addAttachment(metaName, "text/plain", meta);
        if (body != null && !body.isBlank()) {
            Allure.addAttachment(bodyName, bodyContentType(body), body);
        }
    }

    /** {@code application/json}, если строка (после trim) начинается с {@code &#123;}/{@code [}; иначе {@code text/plain}. */
    public static String bodyContentType(String body) {
        String t = body == null ? "" : body.stripLeading();
        return (t.startsWith("{") || t.startsWith("[")) ? "application/json" : "text/plain";
    }
}
