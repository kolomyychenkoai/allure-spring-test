package io.github.kolomyychenkoai.allure.spring.unit;

import io.qameta.allure.Epic;
import io.github.kolomyychenkoai.allure.spring.internal.ByteBuddyPresence;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Инвариант мягкой деградации: без byte-buddy на classpath гард обязан вернуть {@code false},
 * а не упасть. На нём стоят девять листенеров — они регистрируются ВСЕГДА (через
 * {@code spring.factories}), и если гард бросит, тесты потребителя посыплются с
 * {@link NoClassDefFoundError} ровно там, где должна была быть тихая деградация.
 * <p>
 * Проверяем НАСТОЯЩИМ classloader'ом без byte-buddy, а не рассуждением: до вынесения гарда в
 * отдельный класс он жил в {@code AllureInstrumentation}, который сам без byte-buddy не
 * линкуется — и «безопасный» метод падал ещё до входа. Комментарий такое не ловит, тест ловит.
 */
@Epic("Внутренние проверки библиотеки")
class ByteBuddyPresenceTest {

    /** Загрузчик, видящий ТОЛЬКО наши классы и платформу — byte-buddy недоступен. */
    private static URLClassLoader withoutByteBuddy() throws IOException {
        URL classes = Path.of("target/classes").toUri().toURL();
        return new URLClassLoader("без-byte-buddy", new URL[]{classes}, ClassLoader.getPlatformClassLoader());
    }

    private static Object call(ClassLoader loader, String className) throws Throwable {
        Class<?> type = Class.forName(className, true, loader);
        try {
            return type.getMethod("available").invoke(null);
        } catch (InvocationTargetException e) {
            throw e.getCause(); // не приводим к Exception: Error спрятал бы настоящую причину
        }
    }

    @Test
    @DisplayName("без byte-buddy гард отдаёт false и НЕ бросает (мягкая деградация девяти листенеров)")
    void gateDegradesSoftly() throws Exception {
        try (URLClassLoader loader = withoutByteBuddy()) {
            assertThatCode(() -> assertThat(call(loader,
                    "io.github.kolomyychenkoai.allure.spring.internal.ByteBuddyPresence"))
                    .isEqualTo(false))
                    .doesNotThrowAnyException();
        }
    }

    @Test
    @DisplayName("гард не должен жить в классе, который сам ссылается на byte-buddy")
    void instrumentationClassItselfDoesNotLoadWithoutByteBuddy() throws Exception {
        // Фиксируем ПРИЧИНУ, по которой гард вынесен наружу: гранулярность линковки в JVM — класс,
        // а не метод. Если однажды AllureInstrumentation перестанет ссылаться на byte-buddy,
        // тест покраснеет и заставит перечитать решение (а не тихо оставит мёртвое правило).
        try (URLClassLoader loader = withoutByteBuddy()) {
            assertThatThrownBy(() -> call(loader,
                    "io.github.kolomyychenkoai.allure.spring.internal.AllureInstrumentation"))
                    .isInstanceOf(NoClassDefFoundError.class);
        }
    }

    @Test
    @DisplayName("диагност активации работает без byte-buddy (иначе умрёт там, где должен предупредить)")
    void diagnosticsSurviveWithoutByteBuddy() throws Exception {
        // Диагност жалуется в т.ч. НА byte-buddy (старый не знает формат классов новой JVM).
        // Если он сам без библиотеки не загрузится, предупреждения не будет ровно тогда, когда
        // оно нужнее всего. Проверяем не загрузку класса, а ВЫЗОВ: ссылки в телах методов JVM
        // разрешает лениво, поэтому «класс загрузился» ещё ничего не доказывает.
        try (URLClassLoader loader = withoutByteBuddy()) {
            Class<?> diagnostics = Class.forName(
                    "io.github.kolomyychenkoai.allure.spring.internal.ActivationDiagnostics", true, loader);
            assertThatCode(() -> diagnostics.getMethod("reportOnce").invoke(null))
                    .doesNotThrowAnyException();
        }
    }

    @Test
    @DisplayName("на обычном classpath (byte-buddy есть) гард отдаёт true")
    void gateTrueWhenPresent() {
        assertThat(ByteBuddyPresence.available()).isTrue();
    }
}
