package io.github.kolomyychenkoai.allure.spring.internal;

import net.bytebuddy.agent.ByteBuddyAgent;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.utility.JavaModule;

import java.lang.instrument.Instrumentation;

/**
 * Общая база байткод-инструментирования для всех модулей (Spring-ассерты, Hamcrest,
 * AssertJ, Kafka, WireMock-verify…). Сам {@code ByteBuddyAgent.install()} идемпотентен
 * (переиспользует уже привязанный к JVM агент — совместимо с Mockito), радиус узкий
 * (только заданный тип), {@code disableClassFormatChanges} — безопасно для соседних
 * агентов. Сбой инструментирования логируется на WARNING и НЕ роняет тест.
 * <p>
 * <b>byte-buddy в scope {@code provided}</b> — у потребителя он обычно есть транзитивно
 * (mockito / spring-boot-starter-test). Если есть сомнение, что byte-buddy на classpath,
 * вызывающий модуль обязан спросить {@link ByteBuddyPresence#available()} ПЕРЕД тем, как
 * трогать этот класс: строить matcher и transformer нельзя (их типы из byte-buddy), да и сам
 * {@code AllureInstrumentation} без библиотеки НЕ ЗАГРУЗИТСЯ — он упоминает её типы в теле
 * {@link #retransform}, а линкуется класс целиком.
 */
public final class AllureInstrumentation {

    /**
     * Аварийный выключатель байткод-перехвата: {@code -Dallure.spring.instrumentation=off}.
     * <p>
     * Нужен потребителю, у которого перехват конфликтует с чужим агентом или ломает сборку:
     * без выключателя единственный выход — выкинуть библиотеку целиком, хотя Spring-каналы
     * (MockMvc, WebTestClient, конфиги, логи, Liquibase) работают и без байткода.
     * <p>
     * ⚠️ Рассчитан на СТАРТ JVM (флаг в командной строке), а не на переключение по ходу прогона.
     * Свойство честно перечитывается на каждом {@link #retransform}, но у каждого модуля
     * {@code install()} взводит свой CAS-гард ДО вызова — модуль, чей {@code install()} отработал
     * при {@code off}, обратно в этой JVM уже не включится. Гард намеренно не двигаем: он же гасит
     * повторную установку, а сценария «включить обратно» нет.
     * <p>
     * Перечитывание нужно другому: {@code unit/ListenerDegradationTest} поднимает листенеры в
     * ОТДЕЛЬНОМ загрузчике (там CAS-гарды свои, чистые) и выключает перехват на время своего
     * прогона — иначе он навесил бы вторую копию advice на уже инструментированные классы и
     * задвоил шаги во всём прогоне ({@code Instrumentation} общий на JVM).
     */
    public static final String SWITCH = "allure.spring.instrumentation";

    private AllureInstrumentation() {
    }

    /** Выключен ли перехват свойством {@link #SWITCH}. */
    public static boolean disabled() {
        return "off".equalsIgnoreCase(System.getProperty(SWITCH));
    }

    /**
     * Есть ли byte-buddy на classpath.
     *
     * @deprecated всегда возвращает {@code true} либо не вызывается вовсе: чтобы добраться сюда,
     * JVM должна загрузить {@code AllureInstrumentation}, а он без byte-buddy не линкуется
     * ({@link NoClassDefFoundError} до входа в метод). Гард — {@link ByteBuddyPresence#available()}.
     */
    @Deprecated(since = "0.1.0")
    public static boolean available() {
        try {
            Class.forName("net.bytebuddy.agent.ByteBuddyAgent", false,
                    AllureInstrumentation.class.getClassLoader());
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Ретрансформировать тип(ы) под {@code typeMatcher} переданным {@code transformer}
     * (advice). Сбой ловится и логируется на WARNING — тест не затрагивается.
     * <p>
     * <b>НЕ идемпотентен:</b> каждый вызов регистрирует НОВЫЙ {@code ClassFileTransformer}
     * в {@link Instrumentation} на весь срок жизни JVM и заново ретрансформирует
     * подходящие классы. Вызывающий ОБЯЗАН гарантировать однократность установки
     * (потокобезопасно, напр. {@code AtomicBoolean.compareAndSet} — как сделано во всех
     * модулях), иначе под параллельными тестами навесятся дубли трансформеров и шаги
     * в отчёте задвоятся.
     */
    public static void retransform(ElementMatcher<? super TypeDescription> typeMatcher,
                                   AgentBuilder.Transformer transformer) {
        if (disabled()) {
            return;
        }
        try {
            Instrumentation instrumentation = ByteBuddyAgent.install();
            // отмечаем СРАЗУ после привязки агента: во-первых, это она и есть по смыслу;
            // во-вторых, installOn с Reiterating тут же обходит загруженные классы и может
            // дёрнуть колбэк — инициализировать класс диагностики внутри ClassFileTransformer
            // не стоит (классический источник ClassCircularityError в агентах)
            InstrumentationDiagnostics.markInstalled();
            new AgentBuilder.Default()
                    .disableClassFormatChanges()
                    .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                    // Reiterating: повторно сканирует загруженные классы, пока набор не стабилизируется —
                    // ловит и УЖЕ загруженные классы (иерархию AssertJ, рано подтянутую Spring/surefire),
                    // и те, что подгружаются во время самой ретрансформации. Без этого методы из рано
                    // загруженных абстрактных классов (AbstractCharSequenceAssert.startsWith,
                    // AbstractIterableAssert.contains) оставались без advice и пропадали из отчёта.
                    // Стоимость — разовый обход загруженных классов на КАЖДУЮ установку модуля
                    // (один раз на JVM, на init листенера); в steady-state не пересканирует.
                    .with(AgentBuilder.RedefinitionStrategy.DiscoveryStrategy.Reiterating.INSTANCE)
                    // без слушателя ошибки трансформации уходят в Listener.NoOp — не видно НИЧЕГО
                    .with(new FailureListener())
                    .type(typeMatcher)
                    .transform(transformer)
                    .installOn(instrumentation);
        } catch (Throwable t) {
            InstrumentationDiagnostics.recordFailure("<install>", t);
        }
    }

    /**
     * Копит сбои трансформации в {@link InstrumentationDiagnostics}. Переопределяем РОВНО два
     * колбэка: {@code onError} (главная цель) и {@code onTransformation} (позитивный сигнал
     * «типы реально перехвачены»). {@code onDiscovery}/{@code onIgnored}/{@code onComplete}
     * намеренно оставлены no-op: они дёргаются на КАЖДЫЙ загружаемый класс JVM — это горячий
     * путь загрузки классов, а полезной информации там нет.
     */
    private static final class FailureListener extends AgentBuilder.Listener.Adapter {

        @Override
        public void onTransformation(TypeDescription typeDescription, ClassLoader classLoader,
                                     JavaModule module, boolean loaded, DynamicType dynamicType) {
            InstrumentationDiagnostics.recordTransformation();
        }

        @Override
        public void onError(String typeName, ClassLoader classLoader, JavaModule module,
                            boolean loaded, Throwable throwable) {
            InstrumentationDiagnostics.recordFailure(typeName, throwable);
        }
    }
}
