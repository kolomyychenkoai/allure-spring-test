package io.github.kolomyychenkoai.allure.spring.mock.internal;

import io.github.kolomyychenkoai.allure.spring.internal.AllureAdviceSupport;
import io.github.kolomyychenkoai.allure.spring.internal.AllureInstrumentationLogger;
import io.qameta.allure.Allure;
import org.mockito.invocation.Invocation;
import org.mockito.invocation.InvocationContainer;
import org.mockito.invocation.MockHandler;
import org.mockito.mock.MockCreationSettings;

import java.lang.reflect.Method;

/**
 * Обёртка над Mockito {@link MockHandler}: логирует взаимодействия с моками в Allure
 * шагами «Мок-заглушка/Мок-вызов/Мок-проверка: Class.method(args)» с вложениями
 * «Mock Call» (метод+аргументы) и «Mock Result» (результат у реального вызова) — в том же
 * стиле, что WireMock/БД. Фаза определяется ДО {@code delegate.handle(...)}.
 * Логируем только при активном Allure тест-кейсе; всё в try/catch.
 * <p>
 * Различение «заглушка vs вызов» — best-effort по стеку (прямой вызов из теста =
 * настройка заглушки, через прод-класс = вызов); завязано на суффиксы имён тест-классов
 * {@link #TEST_CLASS_SUFFIXES}. «Проверка» определяется надёжно по состоянию Mockito;
 * кратность (ожидали ×N) — best-effort из режима verify, для {@code atLeast/atMost}
 * показывается как точное число (без «≥/≤»). Настроенное возвращаемое значение у заглушки
 * в момент {@code when(...)} ещё не задано (thenReturn идёт после), поэтому показывается у вызова.
 * <p>
 * <b>Хрупкость:</b> определение verify/кратности завязано на внутренние поля Mockito 5.x
 * ({@code ThreadSafeMockingProgress.verificationMode}, {@code Times.wantedCount}); при апгрейде
 * Mockito проверить. Деградация мягкая: при смене внутренностей кратность/детект тихо
 * отключаются (шаг без ×N), тест не падает.
 */
public class AllureMockitoHandler<T> implements MockHandler<T> {

    // Суффиксы имён тест-классов: прямой вызов мока из такого класса = настройка заглушки, не прод-вызов.
    private static final String[] TEST_CLASS_SUFFIXES = {"Test", "Tests", "IT"};

    private final MockHandler<T> delegate;

    public AllureMockitoHandler(MockHandler<T> delegate) {
        this.delegate = delegate;
    }

    @Override
    public Object handle(Invocation invocation) throws Throwable {
        boolean objectMethod = isObjectMethod(invocation.getMethod());
        Object verificationMode = objectMethod ? null : MockitoInternals.verificationMode();
        boolean verify = verificationMode != null;
        boolean productionCall = !objectMethod && !verify
                && isCalledFromProductionCode(invocation.getMock().getClass());

        // Провал verify/вызова бросается ИЗНУТРИ delegate.handle и пробрасывается наверх —
        // тест падает, Allure показывает причину сам; фабриковать FAILED-шаг не нужно.
        Object result = delegate.handle(invocation);

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
            String count = MockitoInternals.wantedCount(verificationMode);
            Allure.step("Мок-проверка: " + signature + (count != null ? " (ожидали " + count + ")" : ""),
                    step -> {
                        Allure.addAttachment("Mock Call", "text/plain", call);
                    });
        } else if (productionCall) {
            final String res = AllureAdviceSupport.safe(result);
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
            sb.append(AllureAdviceSupport.safe(args[i]));
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
                sb.append("\n  [").append(i).append("]: ").append(AllureAdviceSupport.safe(args[i]));
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
            for (String suffix : TEST_CLASS_SUFFIXES) {
                if (className.endsWith(suffix)) {
                    return false; // прямой вызов из теста — это настройка заглушки
                }
            }
            return true; // любой другой прикладной код — прод-вызов
        }
        return false;
    }
}
