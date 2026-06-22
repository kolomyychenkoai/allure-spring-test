package io.github.kolomyychenkoai.allure.spring.config;

import io.github.kolomyychenkoai.allure.spring.internal.AllureSpringSettings;
import io.qameta.allure.Allure;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.Environment;
import org.springframework.core.env.PropertySource;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.TestExecutionListener;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Перед каждым тестом снимает срез актуальных свойств Spring {@link Environment} и
 * прикрепляет их к Allure-отчёту шагом «Configuration» с вложением «Properties».
 * Маскирование значений намеренно НЕ делается (данные в тестах фейковые); состав
 * среза ограничивается только префиксами {@code allure.spring.config.include-prefixes}.
 * Активируется автоматически через {@code META-INF/spring.factories}.
 */
public class AllureConfigurationListener implements TestExecutionListener, Ordered {

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public void beforeTestMethod(TestContext testContext) {
        Environment base = environment(testContext);
        if (!AllureSpringSettings.enabled(base, AllureSpringSettings.CONFIG_ENABLED)) {
            return;
        }
        if (!(base instanceof ConfigurableEnvironment env)) {
            return;
        }

        List<String> prefixes = Arrays.stream(AllureSpringSettings.includePrefixes(base).split(","))
                .map(String::trim)
                .filter(p -> !p.isEmpty())
                .toList();

        Set<String> keys = new TreeSet<>();
        for (PropertySource<?> ps : env.getPropertySources()) {
            if (ps instanceof EnumerablePropertySource<?> eps) {
                Collections.addAll(keys, eps.getPropertyNames());
            }
        }

        final String config = keys.stream()
                .filter(k -> prefixes.stream().anyMatch(k::startsWith))
                .map(k -> k + "=" + env.getProperty(k))
                .collect(Collectors.joining("\n"));

        Allure.step("Configuration", () ->
                Allure.addAttachment("Properties", "text/plain",
                        config.isEmpty() ? "No relevant properties" : config));
    }

    private static Environment environment(TestContext testContext) {
        try {
            return testContext.getApplicationContext().getEnvironment();
        } catch (Throwable ignored) {
            return null;
        }
    }
}
