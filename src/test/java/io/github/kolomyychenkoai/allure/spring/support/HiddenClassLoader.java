package io.github.kolomyychenkoai.allure.spring.support;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.List;

/**
 * Загрузчик поверх РЕАЛЬНОГО classpath, из которого спрятаны классы по префиксу имени —
 * симуляция потребителя, у которого нужной библиотеки нет.
 * <p>
 * Почему не {@code FilteredClassLoader} из Spring Boot: он не имеет своих URL и делегирует
 * загрузку РОДИТЕЛЮ, поэтому классы всё равно определяет обычный загрузчик приложения. А наши
 * гейты спрашивают наличие через {@code ClassPresence.class.getClassLoader()} — то есть у
 * родителя, который библиотеку прекрасно видит. Гейт бы «прошёл», ничего не проверив.
 * Здесь классы определяются ЭТИМ загрузчиком (родитель — платформенный), поэтому и наш код,
 * и его проверки живут в одном мире, где библиотеки действительно нет.
 * <p>
 * Прячем по ИМЕНИ, а не вырезанием jar-а: {@code MockMvc} лежит в том же {@code spring-test},
 * что и {@code TestContext} — вырезать jar нельзя, а спрятать пакет можно.
 */
public final class HiddenClassLoader extends URLClassLoader {

    private final List<String> hidden;

    private HiddenClassLoader(URL[] classpath, List<String> hidden) {
        super("без " + String.join(" / ", hidden), classpath, ClassLoader.getPlatformClassLoader());
        this.hidden = hidden;
    }

    /** Загрузчик, в котором не видно классов с указанными префиксами имени. */
    public static HiddenClassLoader hiding(String... prefixes) {
        return new HiddenClassLoader(classpath(), List.of(prefixes));
    }

    /**
     * Элементы {@code java.class.path}. Под surefire это обычно ОДИН «manifest-only» jar —
     * {@link URLClassLoader} сам разворачивает его атрибут {@code Class-Path}, поэтому
     * настоящий classpath доезжает целиком.
     */
    private static URL[] classpath() {
        String[] entries = System.getProperty("java.class.path").split(File.pathSeparator);
        URL[] urls = new URL[entries.length];
        for (int i = 0; i < entries.length; i++) {
            try {
                urls[i] = Path.of(entries[i]).toUri().toURL();
            } catch (MalformedURLException e) {
                throw new IllegalStateException("не разобрать элемент classpath: " + entries[i], e);
            }
        }
        return urls;
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        for (String prefix : hidden) {
            if (name.startsWith(prefix)) {
                throw new ClassNotFoundException(name + " спрятан: симуляция «библиотеки нет у потребителя»");
            }
        }
        return super.loadClass(name, resolve);
    }
}
