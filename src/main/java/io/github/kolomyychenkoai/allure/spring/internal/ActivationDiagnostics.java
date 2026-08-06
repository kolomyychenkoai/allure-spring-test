package io.github.kolomyychenkoai.allure.spring.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;

/**
 * Жалуется, когда модуль МОЛЧА не активировался: технология у потребителя есть, а нашего крючка
 * нет. Типичная причина — переезд класса между мажорами Spring Boot: {@code @ConditionalOnClass}
 * читается ASM-ом без загрузки класса, поэтому условие просто становится ложным и автоконфиг
 * не применяется. Тесты при этом зелёные, а отчёт беднеет.
 * <p>
 * <b>Почему не в автоконфиге.</b> В этом сценарии автоконфиг НЕ ВЫПОЛНЯЕТСЯ по определению —
 * пожаловаться он физически не может. Проверка живёт в {@code TestExecutionListener}, который
 * регистрируется всегда и не линкует ни одного опционального типа (только строки через
 * {@link ClassPresence}).
 * <p>
 * <b>Правило узкое:</b> «фичи нет вовсе» (например, тесты без MockMvc) — молчим, иначе WARNING
 * будут в каждой сборке и их перестанут читать. Жалуемся только когда человек ОЖИДАЕТ шаги
 * и не получит их. Один раз на JVM, с выключателем {@code -Dallure.spring.diagnostics=off}.
 * Прогон не роняем никогда: библиотека диагностическая.
 */
public final class ActivationDiagnostics {

    private static final String SWITCH = "allure.spring.diagnostics";
    private static final AtomicBoolean REPORTED = new AtomicBoolean();

    private ActivationDiagnostics() {
    }

    /**
     * Проблемы активации. Чистая функция от «есть ли класс» — тестируется без classloader-фокусов.
     *
     * @param present          есть ли класс на classpath
     * @param byteBuddyPresent доступен ли байткод-перехват (для MockMvc это второй канал)
     */
    public static List<String> problems(Predicate<String> present, boolean byteBuddyPresent) {
        return problems(present, byteBuddyPresent, false, "?");
    }

    /**
     * То же плюс ось byte-buddy.
     *
     * @param byteBuddyTooOld  byte-buddy не знает формат классов этой JVM
     * @param byteBuddyVersion версия byte-buddy для сообщения
     */
    public static List<String> problems(Predicate<String> present, boolean byteBuddyPresent,
                                        boolean byteBuddyTooOld, String byteBuddyVersion) {
        List<String> problems = new ArrayList<>();

        // Самая тихая из всех поломок: агент ставится, а трансформация падает на КАЖДОМ типе.
        // Версию byte-buddy потребитель обычно не выбирает — она приходит из BOM Spring Boot,
        // поэтому «Boot 3.4 на Java 25» выглядит рабочей комбинацией с мёртвым перехватом.
        if (byteBuddyPresent && byteBuddyTooOld) {
            problems.add("byte-buddy " + byteBuddyVersion + " не знает формат классов Java "
                    + Runtime.version().feature() + " → байткод-перехват (ассерты, JDBC, Kafka, "
                    + "WireMock, Liquibase, Mockito) МОЛЧА выключен, в отчёт попадут только шаги "
                    + "из Spring-каналов. Подними byte-buddy до версии, знающей эту JVM "
                    + "(обычно вместе со Spring Boot), либо включи -Dnet.bytebuddy.experimental=true "
                    + "ОСОЗНАННО.");
        }

        boolean mockMvc = present.test("org.springframework.test.web.servlet.MockMvc");
        boolean mockMvcHook = MovedTypeNames.MOCKMVC_CUSTOMIZER.stream().anyMatch(present);
        if (mockMvc && !mockMvcHook) {
            problems.add("MockMvc есть на classpath, но MockMvcBuilderCustomizer не найден ни под одним "
                    + "известным именем — авто-кастомайзер не зарегистрирован. "
                    + (byteBuddyPresent
                    ? "Пока держит байткод-перехват MockMvc.perform. "
                    : "И байткод-перехват НЕДОСТУПЕН → HTTP-шаги MockMvc в отчёт НЕ ПОПАДУТ. ")
                    + "Добавь в test-scope: spring-boot-webmvc-test (Boot 4.x) "
                    + "или spring-boot-test-autoconfigure (Boot 3.x).");
        }

        boolean webTestClient = present.test("org.springframework.test.web.reactive.server.WebTestClient");
        boolean webTestClientHook = MovedTypeNames.WEBTESTCLIENT_CUSTOMIZER.stream().anyMatch(present);
        if (webTestClient && !webTestClientHook) {
            problems.add("WebTestClient есть на classpath, но WebTestClientBuilderCustomizer не найден "
                    + "ни под одним известным именем — обмены WebTestClient в отчёт НЕ ПОПАДУТ "
                    + "(байткод-фолбэка у этого модуля нет). Добавь в test-scope: "
                    + "spring-boot-webtestclient (Boot 4.x).");
        }
        return problems;
    }

    /**
     * Один раз на JVM, WARNING в логгер библиотеки; прогон не роняем.
     * <p>
     * ⚠️ <b>НЕ добавляй сюда проверку «зарегистрирован ли наш бин-кастомайзер»</b> (по имени,
     * через контекст). Замерено: она срабатывает на КАЖДОМ контексте без автоконфигурации
     * ({@code @SpringBootConfiguration} без {@code @EnableAutoConfiguration} — обычное дело
     * для узких тест-приложений), то есть сыплет WARNING в каждом прогоне.
     * Случай, который она закрывала бы («крючок есть, а наш автоконфиг собран против другого
     * имени»), закрыт конструктивно: интерфейс резолвится ПО ИМЕНИ
     * ({@link MovedCustomizerRegistrar}), а имя из списка находится в любом мажоре. Остаток —
     * ТРЕТЬЕ, неизвестное имя — ловит проверка ниже по тому же списку {@link MovedTypeNames}.
     */
    public static void reportOnce() {
        if ("off".equalsIgnoreCase(System.getProperty(SWITCH)) || !REPORTED.compareAndSet(false, true)) {
            return;
        }
        try {
            // ByteBuddyClassFormat трогаем ТОЛЬКО за гардом: он линкует типы byte-buddy, и без
            // библиотеки обращение к нему уронило бы сам диагност.
            boolean byteBuddy = ByteBuddyPresence.available();
            boolean tooOld = byteBuddy && ByteBuddyClassFormat.tooNewForByteBuddy();
            String version = byteBuddy ? ByteBuddyClassFormat.byteBuddyVersion() : "?";
            problems(ClassPresence::isPresent, byteBuddy, tooOld, version).forEach(problem ->
                    AllureInstrumentationLogger.logger().warning(
                            "[Allure Spring] модуль не активирован: " + problem
                                    + " (заглушить: -D" + SWITCH + "=off)"));
        } catch (Throwable diagnosticIsNotWorthATest) {
            // Ловим здесь, а не полагаемся на то, что Throwable съест ClassPresence внутри себя:
            // обещание «прогон не роняем никогда» обязано принадлежать этому методу, иначе оно
            // держится на реализации чужого хелпера. Диагност — вспомогательный сигнал, ронять
            // из-за него чужой тест недопустимо.
            AllureInstrumentationLogger.warn("ActivationDiagnostics", diagnosticIsNotWorthATest);
        }
    }
}
