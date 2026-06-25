package io.github.kolomyychenkoai.allure.spring.data;

import io.github.kolomyychenkoai.allure.spring.data.internal.AllureJdbcInstrumentation;
import io.github.kolomyychenkoai.allure.spring.internal.AllureInstrumentation;
import io.github.kolomyychenkoai.allure.spring.internal.ClassPresence;
import org.springframework.core.Ordered;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.TestExecutionListener;

/**
 * Ставит байткод-перехват {@code JdbcTemplate}/{@code NamedParameterJdbcTemplate} один раз
 * перед первым тест-классом — чтобы прямые JDBC-вызовы (минуя репозитории) попадали в отчёт.
 * Регистрируется через {@code META-INF/spring.factories}.
 * <p>
 * Гейты: нет {@code JdbcTemplate} на classpath — нечего инструментировать; нет byte-buddy —
 * тихий no-op (типы matcher/advice не линкуются).
 */
public class AllureJdbcListener implements TestExecutionListener, Ordered {

    private static final boolean JDBC_PRESENT =
            ClassPresence.isPresent("org.springframework.jdbc.core.JdbcTemplate");

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public void beforeTestClass(TestContext testContext) {
        if (!JDBC_PRESENT || !AllureInstrumentation.available()) {
            return;
        }
        AllureJdbcInstrumentation.install();
    }
}
