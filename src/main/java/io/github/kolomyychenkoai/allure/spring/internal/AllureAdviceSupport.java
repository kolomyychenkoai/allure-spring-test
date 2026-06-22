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
     * Шаг отчёта с автоматическим статусом по факту падения ({@code thrown != null} → FAILED).
     * Только при активном тест-кейсе: ассерт-инструментирование инлайнится в AbstractAssert и
     * срабатывает на ЛЮБОЙ {@code assertThat} в JVM — в т.ч. на проверочных ассертах самих
     * тестов вне активного кейса; без гейта это сыпало бы «no test case running» в лог.
     */
    public static void step(String name, Throwable thrown) {
        if (!Allure.getLifecycle().getCurrentTestCase().isPresent()) {
            return;
        }
        Allure.step(name, thrown == null ? Status.PASSED : Status.FAILED);
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
}
