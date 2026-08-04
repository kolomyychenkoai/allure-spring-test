package io.github.kolomyychenkoai.allure.spring.assertion;

import io.github.kolomyychenkoai.allure.spring.assertion.internal.AllureAssertJInstrumentation;
import io.github.kolomyychenkoai.allure.spring.assertion.internal.AllureHamcrestInstrumentation;
import io.github.kolomyychenkoai.allure.spring.assertion.internal.AllureJUnitJupiterAssertionsInstrumentation;
import io.github.kolomyychenkoai.allure.spring.assertion.internal.AllureSpringAssertionsInstrumentation;
import io.github.kolomyychenkoai.allure.spring.internal.AllureInstrumentation;
import io.github.kolomyychenkoai.allure.spring.internal.ByteBuddyPresence;
import io.github.kolomyychenkoai.allure.spring.internal.ClassPresence;
import org.springframework.core.Ordered;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.TestExecutionListener;

/**
 * Ставит байткод-инструментирование ассертов один раз (идемпотентно) перед первым
 * тест-классом: Spring AssertionErrors, Hamcrest, AssertJ и JUnit Jupiter Assertions. Регистрируется через
 * {@code META-INF/spring.factories}.
 * <p>
 * Перед установкой проверяется {@link ByteBuddyPresence#available()} — если byte-buddy
 * нет на classpath, листенер тихо ничего не ставит (типы matcher/advice не линкуются).
 * <p>
 * Каждая библиотека ассертов проверяется ОТДЕЛЬНО ({@link ClassPresence}), потому что они
 * подключаются независимо. Три матчера заданы строкой имени и без библиотеки просто ничего не
 * находят, а вот AssertJ-матчер держит {@code AbstractAssert.class} — без AssertJ это
 * {@link NoClassDefFoundError} прямо из хука, то есть КРАСНЫЙ тест-класс у потребителя, а не
 * «модуль выключился». Гейт есть у всех четырёх ради единообразия: следующий матчер тоже
 * может понадобиться типизированный, и правило «сначала спроси, потом трогай» дешевле помнить,
 * чем исключение из него. Закреплено тестом {@code unit/ListenerDegradationTest}.
 */
public class AllureAssertionsListener implements TestExecutionListener, Ordered {

    private static final boolean SPRING_ASSERTIONS_PRESENT =
            ClassPresence.isPresent("org.springframework.test.util.AssertionErrors");
    private static final boolean HAMCREST_PRESENT =
            ClassPresence.isPresent("org.hamcrest.MatcherAssert");
    private static final boolean ASSERTJ_PRESENT =
            ClassPresence.isPresent("org.assertj.core.api.AbstractAssert");
    private static final boolean JUPITER_ASSERTIONS_PRESENT =
            ClassPresence.isPresent("org.junit.jupiter.api.Assertions");

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public void beforeTestClass(TestContext testContext) {
        if (!ByteBuddyPresence.available()) {
            return;
        }
        if (SPRING_ASSERTIONS_PRESENT) {
            AllureSpringAssertionsInstrumentation.install();
        }
        if (HAMCREST_PRESENT) {
            AllureHamcrestInstrumentation.install();
        }
        if (ASSERTJ_PRESENT) {
            AllureAssertJInstrumentation.install();
        }
        if (JUPITER_ASSERTIONS_PRESENT) {
            AllureJUnitJupiterAssertionsInstrumentation.install();
        }
    }
}
