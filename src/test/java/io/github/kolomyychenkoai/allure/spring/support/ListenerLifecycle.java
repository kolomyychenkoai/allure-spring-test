package io.github.kolomyychenkoai.allure.spring.support;

import io.github.kolomyychenkoai.allure.spring.internal.AllureInstrumentation;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Array;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * Прогоняет ВСЕ хуки {@code TestExecutionListener} в чужом загрузчике — чтобы проверить, что
 * без библиотеки листенер деградирует ТИХО.
 * <p>
 * Все семь хуков, а не только {@code beforeTestClass}: гейт легко поставить в одном хуке и
 * забыть про остальные — типичная форма дефекта, когда гейт стоит на установке инструментации,
 * а {@code beforeTestMethod}/{@code afterTestMethod} трогают чужие типы напрямую.
 * <p>
 * {@code TestContext} подсовываем динамическим прокси: настоящий требует загруженного
 * Spring-контекста, а нам нужен ровно противоположный сценарий — «контекст есть, в нём пусто».
 */
public final class ListenerLifecycle {

    /** Порядок как в жизни: класс → экземпляр → метод → выполнение → и обратно. */
    private static final List<String> HOOKS = List.of(
            "beforeTestClass", "prepareTestInstance", "beforeTestMethod", "beforeTestExecution",
            "afterTestExecution", "afterTestMethod", "afterTestClass");

    private static final String PACKAGE = "io.github.kolomyychenkoai.allure.spring.";

    private static final AtomicBoolean REAL_INSTRUMENTATION = new AtomicBoolean();

    private ListenerLifecycle() {
    }

    /**
     * Ставит НАСТОЯЩУЮ инструментацию в основном загрузчике — до того, как в JVM появятся
     * временные загрузчики.
     * <p>
     * <b>Зачем.</b> Байткод-агент ставится один раз, при первом Spring-тест-классе, и обходит
     * ВСЕ загруженные классы ({@code RedefinitionStrategy.Reiterating}). Если к этому моменту в
     * JVM висят классы из уже ЗАКРЫТОГО загрузчика, ByteBuddy не может разрешить их описания —
     * {@code NoSuchTypeException: Cannot resolve type description for org.assertj.core.api.Assert} —
     * и трансформация этих типов срывается. Наши модули (ассерты, JDBC, WireMock, RestTemplate,
     * MockMvc) молча теряют шаги на ВЕСЬ прогон.
     * <p>
     * Замерено: 20 полных прогонов со случайным порядком классов, ровно один красный — тот
     * единственный, где {@code ListenerDegradationTest} отработал РАНЬШЕ первого Spring-теста
     * (30 упавших тестов в семи классах). Корреляция 20/20.
     * <p>
     * Поэтому гарантия висит на {@link HiddenClassLoader#hiding}: опасность создаёт он, там же и
     * страхуемся. Идемпотентно, один раз на JVM. Установка «на всякий случай» тест не роняет:
     * если что-то пошло не так, настоящие листенеры всё равно поставят своё на первом
     * Spring-классе, а сорванные трансформации поймает гейт {@code InstrumentationFailures}.
     */
    static void ensureRealInstrumentationInstalled() {
        if (!REAL_INSTRUMENTATION.compareAndSet(false, true)) {
            return;
        }
        ClassLoader app = ListenerLifecycle.class.getClassLoader();
        for (String listener : registeredListeners(app)) {
            try {
                Class<?> listenerType = Class.forName(listener, true, app);
                Class<?> contextType = Class.forName("org.springframework.test.context.TestContext", true, app);
                listenerType.getMethod("beforeTestClass", contextType).invoke(
                        listenerType.getDeclaredConstructor().newInstance(),
                        proxy(app, contextType, new TestContextStub(app, listenerType)));
            } catch (Throwable degraded) {
                System.err.println("ПРЕДУСТАНОВКА ИНСТРУМЕНТАЦИИ: " + listener + " — " + degraded);
            }
        }
    }

    /** Наши листенеры из НАСТОЯЩЕГО {@code spring.factories} — источник правды один. */
    public static Set<String> registeredListeners(ClassLoader loader) {
        URL url = loader.getResource("META-INF/spring.factories");
        if (url == null) {
            return Set.of();
        }
        String text;
        try (InputStream in = url.openStream()) {
            text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("не прочитать spring.factories", e);
        }
        return Arrays.stream(text.replace("\\", "").split("[\\r\\n,]+"))
                .map(String::trim)
                .filter(line -> line.startsWith(PACKAGE))
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /**
     * Создаёт листенер в загрузчике {@code loader} и дёргает все хуки.
     * Любое исключение пробрасывается наружу — тест ждёт, что его не будет.
     */
    public static void runAllHooks(ClassLoader loader, String listenerClassName) throws Throwable {
        // Instrumentation — общий на всю JVM, чужой загрузчик от него не спасает: без выключателя
        // листенеры навесили бы ВТОРУЮ копию advice на уже инструментированные классы, и шаги
        // задвоились бы во всём прогоне (поймано ровно так — счётчиками в тестах ассертов).
        String previous = System.getProperty(AllureInstrumentation.SWITCH);
        System.setProperty(AllureInstrumentation.SWITCH, "off");
        try {
            Class<?> listenerType = Class.forName(listenerClassName, true, loader);
            Object listener = listenerType.getDeclaredConstructor().newInstance();

            Class<?> contextType = Class.forName("org.springframework.test.context.TestContext", true, loader);
            Object testContext = proxy(loader, contextType, new TestContextStub(loader, listenerType));

            for (String hook : HOOKS) {
                Method method = listenerType.getMethod(hook, contextType);
                try {
                    method.invoke(listener, testContext);
                } catch (InvocationTargetException e) {
                    // не приводим к Exception: NoClassDefFoundError — Error, и именно его мы ловим
                    throw e.getCause();
                }
            }
        } finally {
            if (previous == null) {
                System.clearProperty(AllureInstrumentation.SWITCH);
            } else {
                System.setProperty(AllureInstrumentation.SWITCH, previous);
            }
        }
    }

    private static Object proxy(ClassLoader loader, Class<?> type, InvocationHandler handler) {
        return Proxy.newProxyInstance(loader, new Class<?>[]{type}, handler);
    }

    /** Тест-контекст без реального Spring-контекста: «контекст есть, бинов в нём нет». */
    private record TestContextStub(ClassLoader loader, Class<?> testClass) implements InvocationHandler {

        @Override
        public Object invoke(Object self, Method method, Object[] args) throws Throwable {
            Object common = objectMethod(self, method, args);
            if (common != null) {
                return common;
            }
            return switch (method.getName()) {
                case "getTestClass" -> testClass;
                case "hasApplicationContext" -> true;
                // Пустой контекст — самый интересный случай: код обязан пережить «бинов нет»
                case "getApplicationContext" -> proxy(loader,
                        Class.forName("org.springframework.context.ApplicationContext", true, loader),
                        new EmptyContextStub(loader));
                default -> defaultValue(method.getReturnType());
            };
        }
    }

    /** Пустой ApplicationContext: любой поиск бинов даёт пусто, окружение — не Configurable. */
    private record EmptyContextStub(ClassLoader loader) implements InvocationHandler {

        @Override
        public Object invoke(Object self, Method method, Object[] args) throws Throwable {
            Object common = objectMethod(self, method, args);
            if (common != null) {
                return common;
            }
            if ("getEnvironment".equals(method.getName())) {
                // намеренно НЕ ConfigurableEnvironment: снимок конфигов должен уйти в тихий выход,
                // а не начать писать вложения из чужого загрузчика в общий отчёт
                return proxy(loader, Class.forName("org.springframework.core.env.Environment", true, loader),
                        new EmptyContextStub(loader));
            }
            return defaultValue(method.getReturnType());
        }
    }

    /** {@code toString}/{@code hashCode}/{@code equals} у прокси — иначе падаем на диагностике. */
    private static Object objectMethod(Object self, Method method, Object[] args) {
        return switch (method.getName()) {
            case "toString" -> "<заглушка " + method.getDeclaringClass().getSimpleName() + ">";
            case "hashCode" -> System.identityHashCode(self);
            case "equals" -> self == args[0];
            default -> null;
        };
    }

    /**
     * Безопасное значение по типу возврата. Пустая коллекция/массив, а не {@code null}: код,
     * который сразу зовёт {@code .length}/{@code .values()}, упал бы по NPE — и тест соврал бы,
     * будто дело в отсутствующей библиотеке.
     */
    private static Object defaultValue(Class<?> type) {
        if (type.isArray()) {
            return Array.newInstance(type.getComponentType(), 0);
        }
        if (type == Optional.class) {
            return Optional.empty();
        }
        if (type == Map.class) {
            return Map.of();
        }
        if (type == List.class || type == Collection.class || type == Iterable.class) {
            return List.of();
        }
        if (type == Set.class) {
            return Set.of();
        }
        if (!type.isPrimitive()) {
            return null;
        }
        // тип обязан совпасть точно: рефлексия не приведёт Integer к long
        if (type == boolean.class) {
            return false;
        }
        if (type == char.class) {
            return (char) 0;
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == short.class) {
            return (short) 0;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == float.class) {
            return 0f;
        }
        return type == double.class ? 0d : null; // остаётся void
    }
}
