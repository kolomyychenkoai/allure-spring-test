package io.github.kolomyychenkoai.allure.spring.mock;

import io.github.kolomyychenkoai.allure.spring.internal.AllureInstrumentationLogger;
import io.qameta.allure.Allure;
import io.qameta.allure.AllureLifecycle;
import io.qameta.allure.model.Status;
import io.qameta.allure.model.StepResult;
import org.mockito.internal.progress.ThreadSafeMockingProgress;
import org.mockito.invocation.Invocation;
import org.mockito.invocation.InvocationContainer;
import org.mockito.invocation.MockHandler;
import org.mockito.mock.MockCreationSettings;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.UUID;

/**
 * Обёртка над Mockito {@link MockHandler}: логирует взаимодействия с моками в Allure
 * шагами «Мок-заглушка/Мок-вызов/Мок-проверка: Class.method(args)» с вложениями
 * «Mock Call» (метод+аргументы) и «Mock Result» (результат у реального вызова) — в том же
 * стиле, что WireMock/БД. Фаза определяется ДО {@code delegate.handle(...)}.
 * Логируем только при активном Allure тест-кейсе; всё в try/catch.
 * <p>
 * Различение «заглушка vs вызов» — best-effort по стеку (прямой вызов из теста =
 * настройка заглушки, через прод-класс = вызов); завязано на суффиксы Test/Tests/IT.
 * «Проверка» определяется надёжно по состоянию Mockito; кратность (ожидали ×N) —
 * best-effort из режима verify. Настроенное возвращаемое значение у заглушки в момент
 * {@code when(...)} ещё не задано (thenReturn идёт после), поэтому показывается у вызова.
 */
public class AllureMockitoHandler<T> implements MockHandler<T> {

    private final MockHandler<T> delegate;

    public AllureMockitoHandler(MockHandler<T> delegate) {
        this.delegate = delegate;
    }

    @Override
    public Object handle(Invocation invocation) throws Throwable {
        boolean objectMethod = isObjectMethod(invocation.getMethod());
        Object verificationMode = objectMethod ? null : verificationMode();
        boolean verify = verificationMode != null;
        boolean productionCall = !objectMethod && !verify
                && isCalledFromProductionCode(invocation.getMock().getClass());

        Object result;
        try {
            result = delegate.handle(invocation);
        } catch (Throwable failure) {
            // Провал verify бросается ИЗНУТРИ delegate.handle — покажем FAILED-шаг и пробросим.
            if (verify) {
                try {
                    if (active()) {
                        emitFailedVerify(invocation, verificationMode, failure);
                    }
                } catch (Throwable t) {
                    AllureInstrumentationLogger.warn("Mockito", t);
                }
            }
            throw failure;
        }

        try {
            if (!objectMethod && active()) {
                emit(invocation, result, verify, productionCall, verificationMode);
            }
        } catch (Throwable t) {
            AllureInstrumentationLogger.warn("Mockito", t);
        }
        return result;
    }

    private static boolean active() {
        return Allure.getLifecycle().getCurrentTestCase().isPresent();
    }

    private void emit(Invocation invocation, Object result,
                      boolean verify, boolean productionCall, Object verificationMode) {
        String signature = render(invocation);
        final String call = details(invocation);
        if (verify) {
            String count = wantedCount(verificationMode);
            Allure.step("Мок-проверка: " + signature + (count != null ? " (ожидали " + count + ")" : ""),
                    step -> {
                        Allure.addAttachment("Mock Call", "text/plain", call);
                    });
        } else if (productionCall) {
            final String res = String.valueOf(result);
            Allure.step("Мок-вызов: " + signature + " → " + res, step -> {
                Allure.addAttachment("Mock Call", "text/plain", call);
                Allure.addAttachment("Mock Result", "text/plain", res);
            });
        } else {
            Allure.step("Мок-заглушка: " + signature, step -> {
                Allure.addAttachment("Mock Call", "text/plain", call);
            });
        }
    }

    /** Проваленный verify: FAILED-шаг с вложениями «Mock Call» и «Mock Verify» (текст ошибки Mockito). */
    private void emitFailedVerify(Invocation invocation, Object verificationMode, Throwable failure) {
        String signature = render(invocation);
        String count = wantedCount(verificationMode);
        AllureLifecycle lifecycle = Allure.getLifecycle();
        String stepId = UUID.randomUUID().toString();
        boolean started = false;
        try {
            lifecycle.startStep(stepId, new StepResult()
                    .setName("Мок-проверка не прошла: " + signature
                            + (count != null ? " (ожидали " + count + ")" : ""))
                    .setStatus(Status.FAILED));
            started = true;
            Allure.addAttachment("Mock Call", "text/plain", details(invocation));
            String message = failure.getMessage();
            if (message != null) {
                Allure.addAttachment("Mock Verify", "text/plain", message);
            }
        } finally {
            if (started) {
                try {
                    lifecycle.stopStep(stepId);
                } catch (Throwable ignored) {
                    // шаг гарантированно закрываем
                }
            }
        }
    }

    private static String render(Invocation invocation) {
        return invocation.getMethod().getDeclaringClass().getSimpleName()
                + "." + invocation.getMethod().getName()
                + "(" + joinArgs(invocation.getArguments()) + ")";
    }

    private static String joinArgs(Object[] args) {
        if (args == null || args.length == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < args.length; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(String.valueOf(args[i]));
        }
        return sb.toString();
    }

    private static String details(Invocation invocation) {
        StringBuilder sb = new StringBuilder("Method: ")
                .append(invocation.getMethod().getDeclaringClass().getSimpleName())
                .append('.').append(invocation.getMethod().getName());
        Object[] args = invocation.getArguments();
        if (args != null && args.length > 0) {
            sb.append("\nArguments:");
            for (int i = 0; i < args.length; i++) {
                sb.append("\n  [").append(i).append("]: ").append(String.valueOf(args[i]));
            }
        }
        return sb.toString();
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

    /** Режим verify (или null) — verify(mock) выставляет verificationMode на MockingProgress. */
    private Object verificationMode() {
        try {
            Object progress = ThreadSafeMockingProgress.mockingProgress();
            Field field = progress.getClass().getDeclaredField("verificationMode");
            field.setAccessible(true);
            return field.get(progress);
        } catch (Throwable t) {
            AllureInstrumentationLogger.warn("MockitoVerifyDetection", t);
            return null;
        }
    }

    /**
     * Ожидаемая кратность из режима verify (best-effort). Режим обёрнут в несколько слоёв:
     * Localized&lt;VerificationMode&gt; (поле {@code object}) → MockAwareVerificationMode
     * (поле {@code mode}) → Times/AtLeast… (поле {@code wantedCount}). Разворачиваем слой
     * за слоем, пока не найдём {@code wantedCount}.
     */
    private static String wantedCount(Object verificationMode) {
        Object mode = verificationMode;
        for (int depth = 0; mode != null && depth < 5; depth++) {
            try {
                Field field = mode.getClass().getDeclaredField("wantedCount");
                field.setAccessible(true);
                return "×" + field.getInt(mode);
            } catch (NoSuchFieldException notHere) {
                mode = unwrapMode(mode);
            } catch (Throwable ignored) {
                return null;
            }
        }
        return null;
    }

    /** Снять один слой обёртки режима verify: Localized.object или MockAwareVerificationMode.mode. */
    private static Object unwrapMode(Object wrapper) {
        for (String fieldName : new String[]{"object", "mode"}) {
            try {
                Field field = wrapper.getClass().getDeclaredField(fieldName);
                field.setAccessible(true);
                return field.get(wrapper);
            } catch (Throwable ignored) {
                // пробуем следующее имя поля
            }
        }
        return null;
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
