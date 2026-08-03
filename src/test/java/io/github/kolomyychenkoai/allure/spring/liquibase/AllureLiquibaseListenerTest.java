package io.github.kolomyychenkoai.allure.spring.liquibase;

import io.qameta.allure.Epic;

import io.github.kolomyychenkoai.allure.spring.liquibase.internal.AllureLiquibaseInstrumentation;
import io.github.kolomyychenkoai.allure.spring.support.InMemoryAllure;
import io.qameta.allure.model.TestResult;
import liquibase.changelog.ChangeSet;
import liquibase.changelog.DatabaseChangeLog;
import liquibase.integration.spring.SpringLiquibase;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import org.springframework.context.support.StaticApplicationContext;
import org.springframework.test.context.TestContext;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Гейт «снимок стартовой схемы — только там, где БД реально поднималась»: снимок рисуется, лишь
 * если в контексте теста есть бин {@link SpringLiquibase}. Иначе JVM-широкий снимок протекал бы в
 * тесты без Liquibase (Kafka/HTTP-only). Проверяем ДВА уровня:
 * <ul>
 *   <li>предикат {@link AllureLiquibaseListener#contextRanLiquibase} детерминированно (бин есть/нет/null);</li>
 *   <li>ПРОВОДКУ: гоняем {@code listener.beforeTestMethod(ctx)} внутри {@code allure.run(...)} и
 *       проверяем, что снимок появляется ТОЛЬКО при наличии бина — так тест падает, если снять гейт.</li>
 * </ul>
 * Статику модуля (буфер старта + накопленный снимок) сбрасываем рефлексией вокруг каждого теста.
 */
@Epic("Внутренние проверки библиотеки")
class AllureLiquibaseListenerTest {

    private final AllureLiquibaseListener listener = new AllureLiquibaseListener();
    private InMemoryAllure allure;

    @BeforeEach
    void setUp() {
        resetState();
        allure = new InMemoryAllure().install();
    }

    @AfterEach
    void tearDown() {
        allure.uninstall();
        resetState();
    }

    @AfterAll
    static void tearDownClass() {
        resetState(); // чтобы порядок тест-классов в JVM не отравил LiquibaseReportIT
    }

    // --- предикат ---

    @Test
    @DisplayName("контекст с бином SpringLiquibase → миграции реально применялись")
    void detectsLiquibaseBean() {
        StaticApplicationContext ctx = new StaticApplicationContext();
        ctx.refresh();
        assertThat(AllureLiquibaseListener.contextRanLiquibase(ctx)).isFalse();

        ctx.getBeanFactory().registerSingleton("liquibase", new SpringLiquibase());
        assertThat(AllureLiquibaseListener.contextRanLiquibase(ctx)).isTrue();
    }

    @Test
    @DisplayName("null-контекст → снимок не рисуем (не падаем)")
    void nullContextIsFalse() {
        assertThat(AllureLiquibaseListener.contextRanLiquibase(null)).isFalse();
    }

    // --- проводка гейта в beforeTestMethod (падает, если снять гейт) ---

    @Test
    @DisplayName("контекст С Liquibase: beforeTestMethod рисует снимок стартовой схемы")
    void snapshotShownWhenContextRanLiquibase() {
        AllureLiquibaseInstrumentation.onExecute(changeSet("create-account", "allure"), null); // буфер старта

        TestResult result = allure.run("lb-gate-on",
                () -> listener.beforeTestMethod(testContext(true)));

        assertThat(allure.hasStep(result, "🛢️ Liquibase: схема БД (1 changeset)")).isTrue();
    }

    @Test
    @DisplayName("контекст БЕЗ Liquibase: снимок НЕ протекает (гейт держит)")
    void snapshotHiddenWhenNoLiquibaseInContext() {
        AllureLiquibaseInstrumentation.onExecute(changeSet("create-account", "allure"), null); // буфер старта

        TestResult result = allure.run("lb-gate-off",
                () -> listener.beforeTestMethod(testContext(false)));

        assertThat(result.getSteps().stream().map(s -> s.getName()))
                .noneMatch(n -> n.contains("схема БД"));
    }

    // --- helpers ---

    private static ChangeSet changeSet(String id, String author) {
        return new ChangeSet(id, author, false, false,
                "db/changelog/test.xml", null, null, new DatabaseChangeLog());
    }

    /** Лёгкий {@link TestContext}, чей контекст «имеет»/«не имеет» бин {@link SpringLiquibase}. */
    private static TestContext testContext(boolean hasLiquibaseBean) {
        ApplicationContext ctx = mock(ApplicationContext.class);
        when(ctx.getBeanNamesForType(eq(SpringLiquibase.class), anyBoolean(), anyBoolean()))
                .thenReturn(hasLiquibaseBean ? new String[]{"liquibase"} : new String[0]);
        TestContext tc = mock(TestContext.class);
        when(tc.getApplicationContext()).thenReturn(ctx);
        return tc;
    }

    private static void resetState() {
        try {
            Field buffer = AllureLiquibaseInstrumentation.class.getDeclaredField("STARTUP_BUFFER");
            buffer.setAccessible(true);
            ((Queue<?>) buffer.get(null)).clear();
            Field snapshot = AllureLiquibaseInstrumentation.class.getDeclaredField("STARTUP_SNAPSHOT");
            snapshot.setAccessible(true);
            ((List<?>) snapshot.get(null)).clear();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("не удалось сбросить состояние Liquibase-модуля", e);
        }
    }
}
