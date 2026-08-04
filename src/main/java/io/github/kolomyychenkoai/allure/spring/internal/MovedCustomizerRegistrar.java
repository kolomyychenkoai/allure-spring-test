package io.github.kolomyychenkoai.allure.spring.internal;

import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Регистрирует бин-кастомайзер, интерфейс которого ПЕРЕЕЗЖАЕТ между мажорами Spring Boot
 * (см. {@link MovedTypeNames}), не упоминая этот интерфейс на этапе компиляции.
 * <p>
 * <b>Как.</b> Интерфейс поднимается по имени, реализация — динамический {@link Proxy},
 * бин регистрируется программно с типом = найденный интерфейс, чтобы Spring Boot собрал его
 * в свой {@code List<…Customizer>} обычным поиском по типу.
 * <p>
 * <b>Почему не {@code @Bean} с типизированной сигнатурой.</b> Возвращаемый тип метода — это
 * компайл-тайм привязка: jar, собранный под одно имя, у потребителя с другим мажором молча
 * не активируется. Прокси снимает привязку, и артефакт живёт на обоих.
 * <p>
 * <b>Загрузчик обязателен параметром.</b> Резолвить имя загрузчиком ЭТОГО класса нельзя:
 * тесты автоконфигов прячут типы через {@code FilteredClassLoader}, и проверка «модуль
 * выключился» стала бы фиктивной — класс всё равно нашёлся бы у родителя.
 */
public final class MovedCustomizerRegistrar {

    private MovedCustomizerRegistrar() {
    }

    /**
     * Регистрирует кастомайзер, если интерфейс нашёлся под одним из известных имён.
     * Не нашёлся — тихо ничего не делает (модуль просто выключен, ошибок нет).
     *
     * @param customize что сделать с билдером; аргумент — тип из {@code spring-test},
     *                  который между мажорами НЕ переезжал, поэтому приводится обычным кастом
     */
    public static void register(BeanDefinitionRegistry registry, ClassLoader loader, String beanName,
                                List<String> candidateNames, Consumer<Object> customize) {
        // Регистрируем под КАЖДОЕ найденное имя, а не под первое. Если у потребителя на classpath
        // окажутся ОБА интерфейса (переходное состояние миграции — старый артефакт ещё не выкинут),
        // «первое найденное» — это лотерея: Spring Boot собирает кастомайзеры строго СВОЕГО типа,
        // и бин чужого типа он просто не увидит. Модуль выключился бы молча — ровно та беда,
        // от которой уходили. Лишний бин безвреден: его тип никто не собирает.
        List<Class<?>> types = resolveAll(loader, candidateNames);
        for (int i = 0; i < types.size(); i++) {
            registerProxy(registry, loader, i == 0 ? beanName : beanName + "#" + i, types.get(i), customize);
        }
    }

    /** ВСЕ известные типы, которые есть у этого загрузчика (обычно ровно один). */
    public static List<Class<?>> resolveAll(ClassLoader loader, List<String> candidateNames) {
        List<Class<?>> found = new ArrayList<>();
        for (String name : candidateNames) {
            try {
                found.add(Class.forName(name, false, loader));
            } catch (ClassNotFoundException | LinkageError next) {
                // следующее известное имя
            }
        }
        return found;
    }

    /** Отдельный метод ради захвата wildcard: {@code Class<?>} → {@code Class<T>}. */
    private static <T> void registerProxy(BeanDefinitionRegistry registry, ClassLoader loader, String beanName,
                                          Class<T> customizerType, Consumer<Object> customize) {
        T proxy = customizerType.cast(Proxy.newProxyInstance(loader, new Class<?>[]{customizerType},
                new CustomizeHandler(customizerType, customize)));
        registry.registerBeanDefinition(beanName, BeanDefinitionBuilder
                .genericBeanDefinition(customizerType, () -> proxy)
                .getBeanDefinition());
    }

    /** Первый из известных типов, который реально есть у этого загрузчика. */
    public static Optional<Class<?>> resolve(ClassLoader loader, List<String> candidateNames) {
        List<Class<?>> found = resolveAll(loader, candidateNames);
        return found.isEmpty() ? Optional.empty() : Optional.of(found.get(0));
    }

    /**
     * У обоих кастомайзеров ровно один метод — {@code customize(builder)}. Всё остальное, что
     * может прийти в прокси, — методы {@link Object}; отвечаем на них сами, иначе диагностика
     * (лог, сообщение об ошибке) упала бы на {@code toString}.
     */
    private record CustomizeHandler(Class<?> customizerType, Consumer<Object> customize)
            implements InvocationHandler {

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            return switch (method.getName()) {
                case "toString" -> "AllureCustomizer(" + customizerType.getName() + ")";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == (args == null ? null : args[0]);
                // Имя метода проверяем ЯВНО, а не «любой метод с одним аргументом»: интерфейс
                // чужой, и когда в нём появится второй метод, широкая ветка позвала бы наш
                // рендер не на то. Всё незнакомое — no-op с безопасным значением по типу
                // (null для ссылок сломал бы распаковку у примитивного возврата).
                case "customize" -> {
                    if (args != null && args.length == 1) {
                        customize.accept(args[0]);
                    }
                    yield null; // customize(...) — void у обоих интерфейсов
                }
                default -> defaultValue(method.getReturnType());
            };
        }

        /** Безопасное значение по типу возврата для методов, которых мы не знаем. */
        private static Object defaultValue(Class<?> returnType) {
            if (!returnType.isPrimitive() || returnType == void.class) {
                return null;
            }
            return java.lang.reflect.Array.get(java.lang.reflect.Array.newInstance(returnType, 1), 0);
        }
    }
}
