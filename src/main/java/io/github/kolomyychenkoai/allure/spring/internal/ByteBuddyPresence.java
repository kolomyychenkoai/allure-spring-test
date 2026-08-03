package io.github.kolomyychenkoai.allure.spring.internal;

/**
 * Есть ли byte-buddy на classpath. Гард для листенеров: они регистрируются ВСЕГДА (через
 * {@code spring.factories}), а byte-buddy у нас в scope {@code provided} — у потребителя он
 * обычно приходит транзитивно (mockito / spring-boot-starter-test), но гарантии нет.
 * <p>
 * <b>Почему отдельный класс, а не метод в {@link AllureInstrumentation}.</b> Гранулярность
 * линковки в JVM — КЛАСС, а не метод: {@code AllureInstrumentation} упоминает типы byte-buddy
 * в теле {@code retransform}, поэтому без библиотеки не загружается ЦЕЛИКОМ — даже вызов его
 * «безопасного» {@code available()} даёт {@link NoClassDefFoundError}. То есть гард, живущий
 * внутри инструментируемого класса, защищает ровно от того, чем сам и является.
 * <p>
 * Правило: класс, упоминающий опциональную библиотеку, сам становится опциональным — гард
 * обязан жить снаружи. Инвариант проверяется тестом (загрузка в classloader'е без byte-buddy),
 * а не комментарием: комментарий не краснеет.
 */
public final class ByteBuddyPresence {

    private static final String AGENT = "net.bytebuddy.agent.ByteBuddyAgent";

    private ByteBuddyPresence() {
    }

    /**
     * Можно ли строить matcher/advice и звать {@link AllureInstrumentation#retransform}.
     * Безопасно звать всегда: тут нет ни одной ссылки на типы byte-buddy.
     */
    public static boolean available() {
        return ClassPresence.isPresent(AGENT);
    }
}
