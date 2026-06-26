package io.github.kolomyychenkoai.allure.spring.awaitility;

import io.github.kolomyychenkoai.allure.spring.awaitility.internal.AllureAwaitilityConditionListener;
import io.github.kolomyychenkoai.allure.spring.internal.ClassPresence;
import org.awaitility.Awaitility;
import org.springframework.core.Ordered;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.TestExecutionListener;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Подключает логирование Awaitility-ожиданий один раз перед первым тест-классом: ставит наш
 * {@link AllureAwaitilityConditionListener} глобальным слушателем через официальный SPI
 * ({@code Awaitility.setDefaultConditionEvaluationListener}). Регистрируется через
 * {@code META-INF/spring.factories}. Код в тестах не нужен.
 * <p>
 * Гейт: нет Awaitility на classpath — тихий no-op (тип {@code Awaitility} не линкуется, т.к. до
 * него не доходим). byte-buddy здесь НЕ нужен — это листенерный, а не байткод-модуль.
 * Установка идемпотентна (CAS) — один раз на JVM.
 * <p>
 * Граница: если тест задаёт СВОЙ {@code conditionEvaluationListener(...)} на конкретном
 * {@code await()}, он переопределяет дефолтный — такое ожидание мы не залогируем (это by design
 * Awaitility). Общий случай {@code await()...until(...)} ловится.
 */
public class AllureAwaitilityListener implements TestExecutionListener, Ordered {

    private static final boolean AWAITILITY_PRESENT =
            ClassPresence.isPresent("org.awaitility.Awaitility");

    private static final AtomicBoolean INSTALLED = new AtomicBoolean(false);

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public void beforeTestClass(TestContext testContext) {
        if (!AWAITILITY_PRESENT || !INSTALLED.compareAndSet(false, true)) {
            return;
        }
        Awaitility.setDefaultConditionEvaluationListener(new AllureAwaitilityConditionListener());
    }
}
