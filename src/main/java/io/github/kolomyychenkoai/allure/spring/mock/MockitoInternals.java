package io.github.kolomyychenkoai.allure.spring.mock;

import io.github.kolomyychenkoai.allure.spring.internal.AllureInstrumentationLogger;
import org.mockito.internal.progress.ThreadSafeMockingProgress;

import java.lang.reflect.Field;

/**
 * Единственный фасад над ВНУТРЕННИМИ полями Mockito — всё хрупкое в одном месте, чтобы
 * при апгрейде Mockito чинить точечно. Проверено на Mockito 5.x:
 * <ul>
 *   <li>{@code ThreadSafeMockingProgress.mockingProgress().verificationMode} — режим verify;</li>
 *   <li>обёртки режима: {@code Localized.object} → {@code MockAwareVerificationMode.mode};</li>
 *   <li>{@code Times/AtLeast.wantedCount} — ожидаемая кратность.</li>
 * </ul>
 * Имена полей вынесены в константы — на них же висит канарейка-тест (упадёт осмысленно,
 * если поле уедет при bump). Деградация мягкая: не нашли поле → null, кратность просто не покажем.
 */
final class MockitoInternals {

    static final String VERIFICATION_MODE_FIELD = "verificationMode";
    static final String WANTED_COUNT_FIELD = "wantedCount";
    static final String[] MODE_WRAPPER_FIELDS = {"object", "mode"};

    private MockitoInternals() {
    }

    /** Текущий режим verify (или null, если сейчас не verify). */
    static Object verificationMode() {
        try {
            Object progress = ThreadSafeMockingProgress.mockingProgress();
            Field field = progress.getClass().getDeclaredField(VERIFICATION_MODE_FIELD);
            field.setAccessible(true);
            return field.get(progress);
        } catch (Throwable t) {
            AllureInstrumentationLogger.warn("MockitoVerifyDetection", t);
            return null;
        }
    }

    /** Ожидаемая кратность из режима verify (best-effort): разворачиваем обёртки до {@code wantedCount}. */
    static String wantedCount(Object verificationMode) {
        Object mode = verificationMode;
        for (int depth = 0; mode != null && depth < 5; depth++) {
            try {
                Field field = mode.getClass().getDeclaredField(WANTED_COUNT_FIELD);
                field.setAccessible(true);
                return "×" + field.getInt(mode);
            } catch (NoSuchFieldException notHere) {
                mode = unwrapMode(mode); // ожидаемо: на этом слое поля нет — снимаем обёртку
            } catch (Throwable t) {
                // неожиданный сбой (напр. InaccessibleObjectException) — видим на WARNING, не молча
                AllureInstrumentationLogger.warn("MockitoWantedCount", t);
                return null;
            }
        }
        return null;
    }

    /** Снять один слой обёртки режима verify: Localized.object или MockAwareVerificationMode.mode. */
    private static Object unwrapMode(Object wrapper) {
        for (String fieldName : MODE_WRAPPER_FIELDS) {
            try {
                Field field = wrapper.getClass().getDeclaredField(fieldName);
                field.setAccessible(true);
                return field.get(wrapper);
            } catch (Throwable ignored) {
                // пробуем следующее имя поля
            }
        }
        return null;
    }
}
