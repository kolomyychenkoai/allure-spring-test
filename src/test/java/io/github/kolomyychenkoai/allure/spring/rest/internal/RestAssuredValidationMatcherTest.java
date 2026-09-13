package io.github.kolomyychenkoai.allure.spring.rest.internal;

import io.github.kolomyychenkoai.allure.spring.internal.AllureInstrumentation;
import io.github.kolomyychenkoai.allure.spring.internal.DiagnosticsReset;
import io.github.kolomyychenkoai.allure.spring.support.LibraryLog;
import io.qameta.allure.Epic;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.pool.TypePool;
import io.restassured.matcher.ResponseAwareMatcher;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.logging.LogRecord;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Уровень A: будет ли вообще что вплетать в проверки RestAssured — и говорим ли мы, когда нет.
 * <p>
 * Ось задачи #55: модуль включается по одному признаку «RestAssured есть на classpath», а это
 * не то же самое, что «внутренний носитель проверок такой, как мы думаем». Разошлось — шаги
 * «Проверка ответа: …» не появятся, HTTP-шаги при этом останутся, и отчёт будет выглядеть
 * полным. Отключение раздела обязано объявлять о себе.
 * <p>
 * Проверяется РЕШЕНИЕ, а не список имён: вплетение идёт только в ОБЪЯВЛЕННЫЕ методы и только
 * в перегрузки, прошедшие матчер. Оба отличия и стерегут тесты ниже.
 */
@Epic("Внутренние проверки библиотеки")
class RestAssuredValidationMatcherTest {

    /** Носитель, у которого проверка объявлена своя, — совпадение есть. */
    @SuppressWarnings("unused")
    public static class Declaring {
        public Object statusCode(int expected) {
            return null;
        }
    }

    /** Носитель, унаследовавший проверку. Мутация: getDeclaredMethods() → getMethods() — RED. */
    public static class Inheriting extends Declaring {
    }

    /** Носитель, у которого остались только исключённые перегрузки: log-вариант и обёртка. */
    @SuppressWarnings("unused")
    public static class OnlyExcluded {
        public Object body() {
            return null;
        }

        public Object body(boolean logOnly) {
            return null;
        }

        public Object header(String name, ResponseAwareMatcher<Response> matcher) {
            return null;
        }
    }

    /** Носитель без единого метода — пустой вход детектора. */
    public static class Empty {
    }

    @BeforeEach
    void забытьСказанное() {
        DiagnosticsReset.forget();
    }

    @Test
    @DisplayName("настоящий носитель RestAssured совпадает — положительный якорь детектора")
    void настоящийНосительСовпадает() {
        // Без него все проверки ниже доказывали бы лишь то, что детектор умеет говорить «нет».
        TypePool pool = TypePool.Default.of(getClass().getClassLoader());
        TypeDescription carrier = pool
                .describe("io.restassured.internal.ValidatableResponseOptionsImpl").resolve();

        assertThat(AllureRestAssuredValidationInstrumentation.matchesValidationMethods(carrier))
                .as("матчер не совпал с настоящим RestAssured — перехват проверок мёртв уже сейчас")
                .isTrue();
    }

    @Test
    @DisplayName("метод объявлен в предке — вплетать в этот тип нечего")
    void унаследованныйМетодНеСчитается() {
        // ByteBuddy вплетает ТОЛЬКО в объявителя. Рефлексивный getMethods() отдал бы
        // унаследованный statusCode и сказал бы «всё хорошо» при мёртвом перехвате.
        assertThat(AllureRestAssuredValidationInstrumentation
                .matchesValidationMethods(TypeDescription.ForLoadedType.of(Inheriting.class)))
                .as("унаследованный метод посчитан за свой — детектор врёт в самую опасную сторону")
                .isFalse();
        assertThat(AllureRestAssuredValidationInstrumentation
                .matchesValidationMethods(TypeDescription.ForLoadedType.of(Declaring.class)))
                .as("свой объявленный метод не посчитан — детектор слеп и в обратную сторону")
                .isTrue();
    }

    @Test
    @DisplayName("остались только исключённые перегрузки — тоже нечего")
    void толькоИсключённыеПерегрузкиНеСчитаются() {
        // Имена на месте, а вплетать не во что: 0-арг и boolean — log-варианты, header
        // с матчером-обёрткой матчер исключает сам.
        assertThat(AllureRestAssuredValidationInstrumentation
                .matchesValidationMethods(TypeDescription.ForLoadedType.of(OnlyExcluded.class)))
                .as("проверка смотрит на имена, а не на перегрузки — так мёртвый перехват пройдёт")
                .isFalse();
    }

    @Test
    @DisplayName("пустой носитель: детектор не выдаёт «ничего не измерил» за «всё хорошо»")
    void пустойНосительНеСовпадает() {
        assertThat(AllureRestAssuredValidationInstrumentation
                .matchesValidationMethods(TypeDescription.ForLoadedType.of(Empty.class)))
                .isFalse();
    }

    @Test
    @DisplayName("вплетать нечего — сказано вслух, и ровно один раз")
    void молчанияНеОстаётся() {
        List<LogRecord> said = LibraryLog.capture(() -> {
            AllureRestAssuredValidationInstrumentation.announceIfSilent(false);
            AllureRestAssuredValidationInstrumentation.announceIfSilent(false);
        });

        assertThat(said)
                .extracting(LogRecord::getMessage)
                .as("раздел проверок исчез молча — отчёт выглядит полным, и никто не поймёт почему")
                .anyMatch(message -> message.contains("шагов «Проверка ответа: …» в отчёте не будет"));
        assertThat(said)
                .as("сказано дважды — это шум в каждой сборке, ровно то, от чего лечит #74")
                .hasSize(1);
    }

    @Test
    @DisplayName("перехват выключен потребителем — про потерю не говорим")
    void приВыключателеМолчим() {
        // Предупреждать о потере того, что выключили сам, — шум в чужой сборке.
        String was = System.getProperty(AllureInstrumentation.SWITCH);
        System.setProperty(AllureInstrumentation.SWITCH, "off");
        try {
            List<LogRecord> said = LibraryLog.capture(
                    () -> AllureRestAssuredValidationInstrumentation.announceIfSilent(false));

            assertThat(said).as("сказали про потерю раздела, который потребитель выключил сам").isEmpty();
        } finally {
            if (was == null) {
                System.clearProperty(AllureInstrumentation.SWITCH);
            } else {
                System.setProperty(AllureInstrumentation.SWITCH, was);
            }
        }
    }

    @Test
    @DisplayName("вплетать есть что — молчим")
    void приСовпаденииМолчим() {
        List<LogRecord> said = LibraryLog.capture(
                () -> AllureRestAssuredValidationInstrumentation.announceIfSilent(true));

        assertThat(said).as("детектор говорит всегда — это шум, а не сигнал").isEmpty();
    }
}
