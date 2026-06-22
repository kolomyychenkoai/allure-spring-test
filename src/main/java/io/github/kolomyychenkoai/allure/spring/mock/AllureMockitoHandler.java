package io.github.kolomyychenkoai.allure.spring.mock;

import io.github.kolomyychenkoai.allure.spring.internal.AllureInstrumentationLogger;
import io.qameta.allure.Allure;
import org.mockito.internal.progress.ThreadSafeMockingProgress;
import org.mockito.invocation.Invocation;
import org.mockito.invocation.InvocationContainer;
import org.mockito.invocation.MockHandler;
import org.mockito.mock.MockCreationSettings;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Обёртка над Mockito {@link MockHandler}: логирует взаимодействия с моками в Allure
 * шагами «Мок-заглушка/Мок-вызов/Мок-проверка: mock.method(args) → result».
 * Фаза определяется ДО {@code delegate.handle(...)} (пока Mockito не «съел» своё
 * внутреннее состояние). Логируем только при активном Allure тест-кейсе; всё в try/catch.
 * <p>
 * Различение «заглушка vs вызов» — best-effort по стеку (прямой вызов из теста =
 * настройка заглушки, вызов через прод-класс = вызов); завязано на суффиксы тест-классов
 * (Test/Tests/IT). «Проверка» определяется надёжно — по состоянию Mockito. Падение verify
 * происходит в самом Mockito (вне этого handler'а), поэтому статус FAILED у шага
 * «Мок-проверка» не выставляется — провал виден по падению теста.
 */
public class AllureMockitoHandler<T> implements MockHandler<T> {

    private final MockHandler<T> delegate;

    public AllureMockitoHandler(MockHandler<T> delegate) {
        this.delegate = delegate;
    }

    @Override
    public Object handle(Invocation invocation) throws Throwable {
        boolean objectMethod = isObjectMethod(invocation.getMethod());
        boolean verify = !objectMethod && isVerificationMode();
        boolean productionCall = !objectMethod && !verify
                && isCalledFromProductionCode(invocation.getMock().getClass());
        String phase = objectMethod ? null
                : verify ? "Мок-проверка: "
                : productionCall ? "Мок-вызов: "
                : "Мок-заглушка: ";

        Object result = delegate.handle(invocation);

        try {
            if (phase != null && Allure.getLifecycle().getCurrentTestCase().isPresent()) {
                // результат осмыслен только у реального вызова; у заглушки (настройка) и
                // у verify это дефолт/ничего — «→ result» там вводит в заблуждение
                Allure.step(productionCall ? phase + invocation + " → " + result : phase + invocation);
            }
        } catch (Throwable t) {
            AllureInstrumentationLogger.warn("Mockito", t);
        }

        return result;
    }

    @Override
    public MockCreationSettings<T> getMockSettings() {
        return delegate.getMockSettings();
    }

    @Override
    public InvocationContainer getInvocationContainer() {
        return delegate.getInvocationContainer();
    }

    private boolean isObjectMethod(Method method) {
        return method.getDeclaringClass() == Object.class;
    }

    /** verify(mock) выставляет verificationMode на MockingProgress — подсматриваем, не потребляя. */
    private boolean isVerificationMode() {
        try {
            Object progress = ThreadSafeMockingProgress.mockingProgress();
            Field field = progress.getClass().getDeclaredField("verificationMode");
            field.setAccessible(true);
            return field.get(progress) != null;
        } catch (Throwable t) {
            AllureInstrumentationLogger.warn("MockitoVerifyDetection", t);
            return false;
        }
    }

    /**
     * Вызван ли мок через прод-код (а не напрямую из теста = настройка заглушки).
     * Inline-моки (Mockito 5) используют исходный класс без $$-подкласса — поэтому
     * сам класс мока пропускаем явно.
     */
    private boolean isCalledFromProductionCode(Class<?> mockClass) {
        String mockClassName = mockClass.getName();
        boolean passedHandler = false;
        for (StackTraceElement frame : Thread.currentThread().getStackTrace()) {
            String className = frame.getClassName();
            if (className.equals(AllureMockitoHandler.class.getName())) {
                passedHandler = true;
                continue;
            }
            if (!passedHandler) {
                continue;
            }
            if (className.equals(mockClassName)) {
                continue;
            }
            if (className.startsWith("org.mockito.")
                    || className.startsWith("org.springframework.")
                    || className.startsWith("java.")
                    || className.startsWith("javax.")
                    || className.startsWith("jakarta.")
                    || className.startsWith("jdk.")
                    || className.startsWith("sun.")
                    || className.startsWith("com.sun.")
                    || className.contains("$$")
                    || className.contains("CGLIB")
                    || className.contains("ByteBuddy")) {
                continue;
            }
            if (className.endsWith("Test") || className.endsWith("Tests") || className.endsWith("IT")) {
                return false; // прямой вызов из теста — это настройка заглушки
            }
            return true; // любой другой прикладной код — прод-вызов
        }
        return false;
    }
}
