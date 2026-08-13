package io.github.kolomyychenkoai.allure.spring.internal;

import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Общий логгер для байткод-модулей инструментирования (ByteBuddy advice, Mockito и т.п.).
 * Используется java.util.logging, а НЕ SLF4J: advice-классы инлайнятся в чужой байткод,
 * где ссылка на SLF4J недоступна из-за ограничений загрузки классов.
 * <p>
 * Сбой инструментирования логируется на {@link Level#WARNING} — он виден по умолчанию
 * (JUL печатает WARNING в stderr), но тест НЕ роняет. Подробную трассу при отладке можно
 * включить через {@code logger().setLevel(Level.FINE)}.
 */
public final class AllureInstrumentationLogger {

    private static final Logger LOGGER = Logger.getLogger("io.github.kolomyychenkoai.allure.spring");

    private AllureInstrumentationLogger() {
    }

    /** Корневой JUL-логгер библиотеки — для тонкой настройки уровня/handler'ов. */
    public static Logger logger() {
        return LOGGER;
    }

    /**
     * Залогировать сбой инструментирования компонента {@code component} на
     * {@link Level#WARNING}: виден по умолчанию, но тест не роняет. Сообщение строится
     * лениво (supplier), сам {@code t} прикладывается — стек печатается даже если
     * {@code getMessage()} == {@code null}.
     */
    public static void warn(String component, Throwable t) {
        LOGGER.log(Level.WARNING, t, () -> "[Allure " + component + "] сбой инструментирования (тест не затронут)");
    }

    /**
     * След для разбора жалобы: при обычном прогоне он был бы шумом, а когда потребитель
     * пришёл с расхождением в отчёте — единственная зацепка. Уровень {@link Level#FINE},
     * по умолчанию молчит; включается {@code logger().setLevel(Level.FINE)}.
     * <p>
     * <b>Отличие от {@link #note(String, String)}</b>, с которым его легко спутать (обе берут
     * две строки): {@code note} говорит о СПРОЕКТИРОВАННОМ исходе, который потребителю нужно
     * знать сразу — часть отчёта не будет собрана. {@code trace} говорит о случае, который
     * может ни во что не вылиться, и предъявлять его всем — тот же дефект, из-за которого
     * {@code note} в своё время отделили от {@code warn}.
     * <p>
     * Сообщение — supplier, а не строка: на выключенном FINE склейка не выполняется вовсе.
     */
    public static void trace(String component, Supplier<String> message) {
        LOGGER.log(Level.FINE, () -> "[Allure " + component + "] " + message.get());
    }

    /**
     * Сказать о СПРОЕКТИРОВАННОМ исходе — например о том, что модуль сознательно не стал
     * перехватывать чужой объект и часть отчёта будет беднее.
     * <p>
     * Отдельно от {@link #warn(String, Throwable)} намеренно: там слово «сбой» и стек, и на
     * штатной ветке это врёт дважды. Потребитель, увидевший стек при каждом старте контекста,
     * идёт заводить issue вместо того, чтобы прочитать причину.
     */
    public static void note(String component, String message) {
        LOGGER.log(Level.WARNING, () -> "[Allure " + component + "] " + message);
    }
}
