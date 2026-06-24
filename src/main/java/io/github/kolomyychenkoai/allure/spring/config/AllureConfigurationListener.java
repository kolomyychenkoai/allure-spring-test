package io.github.kolomyychenkoai.allure.spring.config;

import io.qameta.allure.Allure;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.Environment;
import org.springframework.core.env.PropertySource;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.TestExecutionListener;

import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Перед каждым тестом снимает все свойства приложения из Spring {@link Environment}
 * и прикрепляет их к Allure-отчёту шагом «Configuration» с вложением «Properties».
 * Системные источники ({@code systemProperties}, {@code systemEnvironment}) исключаются —
 * там JVM-флаги и переменные ОС, а не настройки приложения. Маскирование значений
 * намеренно НЕ делается (данные в тестах фейковые).
 * Активируется автоматически через {@code META-INF/spring.factories}.
 * <p>
 * Потокобезопасен: без изменяемого состояния ({@code SYSTEM_SOURCES} — неизменяемый Set,
 * локальные переменные — на стеке метода).
 */
public class AllureConfigurationListener implements TestExecutionListener, Ordered {

    private static final Set<String> SYSTEM_SOURCES = Set.of("systemProperties", "systemEnvironment");

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public void beforeTestMethod(TestContext testContext) {
        Environment base = environment(testContext);
        if (!(base instanceof ConfigurableEnvironment env)) {
            return;
        }

        Set<String> keys = new TreeSet<>();
        for (PropertySource<?> ps : env.getPropertySources()) {
            if (ps instanceof EnumerablePropertySource<?> eps && !SYSTEM_SOURCES.contains(ps.getName())) {
                for (String key : eps.getPropertyNames()) {
                    keys.add(key);
                }
            }
        }

        final String config = keys.stream()
                .map(k -> k + "=" + safeProperty(env, k))
                .collect(Collectors.joining("\n"));

        Allure.step("Configuration", () -> {
            Allure.addAttachment("Properties", "text/plain",
                    config.isEmpty() ? "No properties" : config);
        });
    }

    private static Environment environment(TestContext testContext) {
        try {
            return testContext.getApplicationContext().getEnvironment();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Безопасно резолвит значение свойства: {@code null} → {@code <unset>}, неразрешимый
     * плейсхолдер {@code ${...}} (бросает) → {@code <unresolved>}.
     */
    private static String safeProperty(Environment env, String key) {
        try {
            String value = env.getProperty(key);
            return value != null ? value : "<unset>";
        } catch (RuntimeException e) {
            return "<unresolved>";
        }
    }
}
