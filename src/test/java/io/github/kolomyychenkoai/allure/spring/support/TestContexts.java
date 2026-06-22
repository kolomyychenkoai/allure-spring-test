package io.github.kolomyychenkoai.allure.spring.support;

import org.springframework.context.ApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.test.context.TestContext;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Фабрика лёгкого {@link TestContext} для юнит-тестов листенеров — без поднятия
 * Spring-контекста. Хранит атрибуты в обычной map и отдаёт переданный Environment.
 */
public final class TestContexts {

    private TestContexts() {
    }

    public static TestContext withEnvironment(ConfigurableEnvironment env) {
        Map<String, Object> attributes = new ConcurrentHashMap<>();

        ApplicationContext applicationContext = mock(ApplicationContext.class);
        when(applicationContext.getEnvironment()).thenReturn(env);

        TestContext testContext = mock(TestContext.class);
        when(testContext.getApplicationContext()).thenReturn(applicationContext);

        doAnswer(inv -> attributes.put(inv.getArgument(0), inv.getArgument(1)))
                .when(testContext).setAttribute(any(), any());
        when(testContext.getAttribute(any()))
                .thenAnswer(inv -> attributes.get(inv.<String>getArgument(0)));
        when(testContext.removeAttribute(any()))
                .thenAnswer(inv -> attributes.remove(inv.<String>getArgument(0)));

        return testContext;
    }
}
