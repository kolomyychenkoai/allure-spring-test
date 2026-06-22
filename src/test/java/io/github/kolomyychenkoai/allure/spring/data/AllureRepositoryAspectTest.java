package io.github.kolomyychenkoai.allure.spring.data;

import io.github.kolomyychenkoai.allure.spring.support.InMemoryAllure;
import io.github.kolomyychenkoai.allure.spring.support.jpa.Widget;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.model.TestResult;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.Signature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Уровень A: детерминированная проверка содержимого отчёта для аспекта репозиториев.
 * Аспект вызывается напрямую с замоканным ProceedingJoinPoint — без Spring/БД.
 */
@Epic("allure-spring-test")
@Feature("База данных (JPA)")
class AllureRepositoryAspectTest {

    interface FakeRepo {
    }

    static class FakeRepoImpl implements FakeRepo {
    }

    private final AllureRepositoryAspect aspect = new AllureRepositoryAspect();
    private InMemoryAllure allure;

    @BeforeEach
    void setUp() {
        allure = new InMemoryAllure().install();
    }

    @AfterEach
    void tearDown() {
        allure.uninstall();
    }

    private ProceedingJoinPoint pjp(String method, Object[] args, Object returnValue) throws Throwable {
        Signature sig = mock(Signature.class);
        when(sig.getName()).thenReturn(method);
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        when(pjp.getSignature()).thenReturn(sig);
        when(pjp.getTarget()).thenReturn(new FakeRepoImpl());
        when(pjp.getArgs()).thenReturn(args);
        when(pjp.proceed()).thenReturn(returnValue);
        return pjp;
    }

    @Test
    @DisplayName("save(entity): шаг «DB FakeRepo.save», в результате видны поля сущности и суперкласса")
    void logsSaveWithEntityFields() throws Throwable {
        Widget widget = new Widget("thing");
        ProceedingJoinPoint pjp = pjp("save", new Object[]{widget}, widget);

        TestResult result = allure.run("db-save", () -> {
            try {
                aspect.logRepositoryCall(pjp);
            } catch (Throwable t) {
                throw new RuntimeException(t);
            }
        });

        assertThat(allure.hasStep(result, "DB FakeRepo.save")).isTrue();
        assertThat(allure.attachment(result, "DB Query").orElseThrow())
                .contains("FakeRepo.save")
                .contains("thing");
        assertThat(allure.attachment(result, "DB Result").orElseThrow())
                .contains("Widget{")
                .contains("name=thing")
                .contains("id=");   // поле из BaseEntity — доказывает обход суперклассов
    }

    @Test
    @DisplayName("findAll(): результат-коллекция описывается размером и элементами")
    void logsFindAllCollection() throws Throwable {
        List<Widget> widgets = List.of(new Widget("a"), new Widget("b"));
        ProceedingJoinPoint pjp = pjp("findAll", new Object[]{}, widgets);

        TestResult result = allure.run("db-findall", () -> {
            try {
                aspect.logRepositoryCall(pjp);
            } catch (Throwable t) {
                throw new RuntimeException(t);
            }
        });

        assertThat(allure.hasStep(result, "DB FakeRepo.findAll")).isTrue();
        assertThat(allure.attachment(result, "DB Result").orElseThrow())
                .contains("Collection size: 2")
                .contains("name=a")
                .contains("name=b");
    }

    @Test
    @DisplayName("findById несуществующего: результат «Optional.empty», аргумент виден")
    void logsOptionalEmpty() throws Throwable {
        ProceedingJoinPoint pjp = pjp("findById", new Object[]{42L}, Optional.empty());

        TestResult result = allure.run("db-empty", () -> {
            try {
                aspect.logRepositoryCall(pjp);
            } catch (Throwable t) {
                throw new RuntimeException(t);
            }
        });

        assertThat(allure.attachment(result, "DB Query").orElseThrow()).contains("42");
        assertThat(allure.attachment(result, "DB Result").orElseThrow()).contains("Optional.empty");
    }

    @Test
    @DisplayName("void-метод (deleteAll): результат «void»")
    void logsVoidResult() throws Throwable {
        ProceedingJoinPoint pjp = pjp("deleteAll", new Object[]{}, null);

        TestResult result = allure.run("db-void", () -> {
            try {
                aspect.logRepositoryCall(pjp);
            } catch (Throwable t) {
                throw new RuntimeException(t);
            }
        });

        assertThat(allure.hasStep(result, "DB FakeRepo.deleteAll")).isTrue();
        assertThat(allure.attachment(result, "DB Result").orElseThrow()).isEqualTo("void");
    }

    @Test
    @DisplayName("исключение репозитория: шаг создаётся с пометкой, исключение проброшено")
    void logsStepOnException() throws Throwable {
        Signature sig = mock(Signature.class);
        when(sig.getName()).thenReturn("save");
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        when(pjp.getSignature()).thenReturn(sig);
        when(pjp.getTarget()).thenReturn(new FakeRepoImpl());
        when(pjp.getArgs()).thenReturn(new Object[]{new Widget("bad")});
        when(pjp.proceed()).thenThrow(new IllegalStateException("constraint violation"));

        TestResult result = allure.run("db-exc", () ->
                assertThatThrownBy(() -> aspect.logRepositoryCall(pjp))
                        .isInstanceOf(IllegalStateException.class));

        assertThat(allure.hasStep(result, "DB FakeRepo.save")).isTrue();
        assertThat(allure.attachment(result, "DB Result").orElseThrow())
                .contains("exception")
                .contains("constraint violation");
    }
}
