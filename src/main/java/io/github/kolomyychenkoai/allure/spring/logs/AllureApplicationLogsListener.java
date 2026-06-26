package io.github.kolomyychenkoai.allure.spring.logs;

import io.github.kolomyychenkoai.allure.spring.internal.ClassPresence;
import io.github.kolomyychenkoai.allure.spring.logs.internal.LogbackLogCapture;
import org.springframework.core.Ordered;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.TestExecutionListener;

/**
 * Захватывает логи приложения на время каждого теста и прикрепляет их к Allure-отчёту как
 * вложение «Application Logs». Активируется автоматически через {@code META-INF/spring.factories}
 * — потребителю не нужно писать код.
 * <p>
 * Вложение кладётся на уровень тест-кейса (а не отдельным шагом, как «Configuration»):
 * логи — это сквозной артефакт всего теста, а не дискретное действие.
 * <p>
 * {@code getOrder() = HIGHEST_PRECEDENCE}: {@code beforeTestMethod} срабатывает раньше
 * прочих листенеров, {@code afterTestMethod} (в обратном порядке) — позже, так захват
 * покрывает тело теста и колбэки листенеров с меньшим приоритетом. То, что логируется
 * ДО beforeTestMethod этого листенера (более ранние листенеры), в захват не попадёт.
 * <p>
 * <b>Граница — только Logback.</b> Захват работает, когда активный SLF4J-бэкенд — Logback
 * (штатно для Spring Boot через {@code spring-boot-starter-logging}). На потребителе с другим
 * бэкендом ({@code spring-boot-starter-log4j2}, JUL — Logback исключён) листенер делает тихий
 * no-op: гейт присутствия {@link #LOGBACK_PRESENT} + рантайм-проверка в
 * {@link LogbackLogCapture#createIfLogback()} не дают тронуть типы {@code ch.qos.logback.*},
 * поэтому ни {@link NoClassDefFoundError}, ни {@link ClassCastException} — тесты не падают
 * (как и все прочие модули, библиотека тихо пропускает то, чего нет на classpath). Вся работа
 * с Logback инкапсулирована в {@link LogbackLogCapture}, чтобы сам листенер не линковал
 * {@code ch.qos.logback.*} до прохождения гейта.
 * <p>
 * ⚠️ <b>Параллелизм:</b> аппендер вешается на ОБЩИЙ root-логгер JVM. Модуль рассчитан на
 * ПОСЛЕДОВАТЕЛЬНЫЙ прогон (forked-JVM surefire — штатно). Под {@code @Execution(CONCURRENT)}
 * аппендеры разных тестов будут ловить логи всех параллельных потоков — содержимое
 * перемешается. Захват намеренно по всем потокам (а не только потоку теста), чтобы видеть
 * async-логи (брокеры/исполнители) — ценой несовместимости с параллельным запуском.
 */
public class AllureApplicationLogsListener implements TestExecutionListener, Ordered {

    private static final String CAPTURE_KEY = AllureApplicationLogsListener.class.getName();

    /**
     * Гейт присутствия: есть ли Logback на classpath. Листенер регистрируется через
     * {@code spring.factories} ВСЕГДА, но типы {@code ch.qos.logback.*} трогает только при
     * {@code true} (см. {@link ClassPresence}). На Log4j2/JUL — тихий no-op.
     */
    private static final boolean LOGBACK_PRESENT =
            ClassPresence.isPresent("ch.qos.logback.classic.LoggerContext");

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public void beforeTestMethod(TestContext testContext) {
        if (!LOGBACK_PRESENT) {
            return;
        }
        LogbackLogCapture capture = LogbackLogCapture.createIfLogback();
        if (capture == null) {
            return; // Logback на classpath, но активный бэкенд другой — ничего не захватываем
        }
        // хэндл СТАВИМ ДО attach: тогда afterTestMethod гарантированно снимет аппендер,
        // даже если addAppender бросит (иначе аппендер «повис» бы на root навсегда)
        testContext.setAttribute(CAPTURE_KEY, capture);
        capture.attach();
    }

    @Override
    public void afterTestMethod(TestContext testContext) {
        if (!LOGBACK_PRESENT) {
            return;
        }
        Object capture = testContext.getAttribute(CAPTURE_KEY);
        if (capture == null) {
            return;
        }
        testContext.removeAttribute(CAPTURE_KEY);
        ((LogbackLogCapture) capture).detachAndWriteAttachment();
    }
}
