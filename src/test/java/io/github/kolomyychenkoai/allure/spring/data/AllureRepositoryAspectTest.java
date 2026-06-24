package io.github.kolomyychenkoai.allure.spring.data;

import io.github.kolomyychenkoai.allure.spring.data.internal.AllureRepositoryAspect;
import io.github.kolomyychenkoai.allure.spring.support.InMemoryAllure;
import io.github.kolomyychenkoai.allure.spring.support.jpa.Widget;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Уровень A: детерминированная проверка содержимого отчёта для аспекта репозиториев.
 * Аспект вызывается напрямую с замоканным ProceedingJoinPoint — без Spring/БД.
 */
class AllureRepositoryAspectTest {

    interface FakeRepo {
    }

    static class FakeRepoImpl implements FakeRepo {
    }

    // прикладной интерфейс ИДЁТ ПОСЛЕ служебного spring-интерфейса — repositoryName
    // обязан пропустить org.springframework.* и взять имя репозитория потребителя.
    static class MixedRepoImpl implements org.springframework.core.Ordered, FakeRepo {
        @Override
        public int getOrder() {
            return 0;
        }
    }

    // только служебный интерфейс — фоллбэк на первый интерфейс (его имя).
    static class OnlySpringImpl implements org.springframework.core.Ordered {
        @Override
        public int getOrder() {
            return 0;
        }
    }

    // вовсе без интерфейсов — фоллбэк на имя самого класса.
    static class NoIfaceImpl {
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
        assertThat(allure.attachment(result, "DB Call").orElseThrow())
                .contains("FakeRepo.save")
                .contains("thing");
        assertThat(allure.attachment(result, "DB Result").orElseThrow())
                .contains("Widget{")
                .contains("name=thing")
                .contains("id=null"); // поле id из BaseEntity (значение, не персистнут) — обход суперклассов
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

        assertThat(allure.attachment(result, "DB Call").orElseThrow()).contains("42");
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

        // шаг создаётся (BROKEN) и показывает, ЧТО ушло в БД (DB Call), но текст исключения
        // НЕ дублируем — его покажет Allure на уровне теста (DB Result при ошибке не пишем)
        assertThat(allure.hasStep(result, "DB FakeRepo.save")).isTrue();
        assertThat(allure.attachment(result, "DB Call").orElseThrow()).contains("bad");
        assertThat(allure.attachment(result, "DB Result")).isEmpty();
    }

    @Test
    @DisplayName("аргументы снимаются ДО вызова: мутация в proceed() не искажает «DB Call»")
    void argumentsCapturedBeforeProceed() throws Throwable {
        StringBuilder arg = new StringBuilder("PENDING");
        Signature sig = mock(Signature.class);
        when(sig.getName()).thenReturn("save");
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        when(pjp.getSignature()).thenReturn(sig);
        when(pjp.getTarget()).thenReturn(new FakeRepoImpl());
        when(pjp.getArgs()).thenReturn(new Object[]{arg});
        // имитируем мутацию аргумента внутри вызова (как save проставляет id сущности)
        when(pjp.proceed()).thenAnswer(inv -> {
            arg.append("-MUTATED");
            return "ok";
        });

        TestResult result = allure.run("db-snapshot", () -> {
            try {
                aspect.logRepositoryCall(pjp);
            } catch (Throwable t) {
                throw new RuntimeException(t);
            }
        });

        assertThat(allure.attachment(result, "DB Call").orElseThrow())
                .contains("PENDING")
                .doesNotContain("PENDING-MUTATED");
    }

    @Test
    @DisplayName("без активного тест-кейса: proceed вызван, шаг не открывается (имя репо даже не запрашивается)")
    void noStepWithoutActiveCase() throws Throwable {
        // setUp установил InMemoryAllure, но allure.run не звали → активного кейса нет
        ProceedingJoinPoint pjp = pjp("save", new Object[]{new Widget("x")}, "ok");

        Object result = aspect.logRepositoryCall(pjp);

        assertThat(result).isEqualTo("ok");
        verify(pjp).proceed();
        verify(pjp, never()).getSignature(); // гейт сработал ДО снятия имени/аргументов
    }

    @Test
    @DisplayName("repositoryName: служебный spring-интерфейс пропускается, берётся репозиторий потребителя")
    void repositoryNameSkipsSpringInterface() throws Throwable {
        Signature sig = mock(Signature.class);
        when(sig.getName()).thenReturn("findAll");
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        when(pjp.getSignature()).thenReturn(sig);
        when(pjp.getTarget()).thenReturn(new MixedRepoImpl());
        when(pjp.getArgs()).thenReturn(new Object[]{});
        when(pjp.proceed()).thenReturn(List.of());

        TestResult result = allure.run("name-mixed", () -> {
            try {
                aspect.logRepositoryCall(pjp);
            } catch (Throwable t) {
                throw new RuntimeException(t);
            }
        });

        assertThat(allure.hasStep(result, "DB FakeRepo.findAll")).isTrue();
    }

    @Test
    @DisplayName("repositoryName: только служебный интерфейс → фоллбэк на его имя")
    void repositoryNameFallsBackToFirstInterface() throws Throwable {
        Signature sig = mock(Signature.class);
        when(sig.getName()).thenReturn("count");
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        when(pjp.getSignature()).thenReturn(sig);
        when(pjp.getTarget()).thenReturn(new OnlySpringImpl());
        when(pjp.getArgs()).thenReturn(new Object[]{});
        when(pjp.proceed()).thenReturn(0L);

        TestResult result = allure.run("name-spring", () -> {
            try {
                aspect.logRepositoryCall(pjp);
            } catch (Throwable t) {
                throw new RuntimeException(t);
            }
        });

        assertThat(allure.hasStep(result, "DB Ordered.count")).isTrue();
    }

    @Test
    @DisplayName("repositoryName: вовсе без интерфейсов → фоллбэк на имя класса")
    void repositoryNameFallsBackToClassName() throws Throwable {
        Signature sig = mock(Signature.class);
        when(sig.getName()).thenReturn("ping");
        ProceedingJoinPoint pjp = mock(ProceedingJoinPoint.class);
        when(pjp.getSignature()).thenReturn(sig);
        when(pjp.getTarget()).thenReturn(new NoIfaceImpl());
        when(pjp.getArgs()).thenReturn(new Object[]{});
        when(pjp.proceed()).thenReturn(null);

        TestResult result = allure.run("name-class", () -> {
            try {
                aspect.logRepositoryCall(pjp);
            } catch (Throwable t) {
                throw new RuntimeException(t);
            }
        });

        assertThat(allure.hasStep(result, "DB NoIfaceImpl.ping")).isTrue();
    }

    @Test
    @DisplayName("большая выборка: показываем первые 20 и «… и ещё N», отчёт не раздуваем")
    void largeCollectionCappedAt20() throws Throwable {
        List<Widget> many = new java.util.ArrayList<>();
        for (int i = 0; i < 25; i++) {
            many.add(new Widget("w" + i));
        }
        ProceedingJoinPoint pjp = pjp("findAll", new Object[]{}, many);

        TestResult result = allure.run("db-big", () -> {
            try {
                aspect.logRepositoryCall(pjp);
            } catch (Throwable t) {
                throw new RuntimeException(t);
            }
        });

        String body = allure.attachment(result, "DB Result").orElseThrow();
        assertThat(body).contains("Collection size: 25")
                .contains("name=w0")
                .contains("name=w19")    // 20-й элемент показан
                .contains("… и ещё 5")
                .doesNotContain("name=w20"); // 21-й и далее обрезаны
    }
}
