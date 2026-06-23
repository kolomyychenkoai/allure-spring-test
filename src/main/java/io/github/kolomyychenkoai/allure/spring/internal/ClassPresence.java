package io.github.kolomyychenkoai.allure.spring.internal;

/**
 * Проверка наличия класса на classpath по ИМЕНИ, без инициализации и без линковки типа
 * у вызывающего. Нужна модулям, которые в lifecycle-хуках обращаются к классам
 * ОПЦИОНАЛЬНОЙ библиотеки (WireMock, RestAssured): эти листенеры регистрируются всегда
 * (через {@code spring.factories}), но если библиотеки нет — прямое обращение к её классу
 * даёт {@link NoClassDefFoundError} и роняет тест потребителя. Сначала спроси
 * {@link #isPresent(String)}, и только при {@code true} трогай классы библиотеки.
 */
public final class ClassPresence {

    private ClassPresence() {
    }

    /** Есть ли класс на classpath. {@code Class.forName} с {@code initialize=false} типы не тянет. */
    public static boolean isPresent(String className) {
        try {
            Class.forName(className, false, ClassPresence.class.getClassLoader());
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}
