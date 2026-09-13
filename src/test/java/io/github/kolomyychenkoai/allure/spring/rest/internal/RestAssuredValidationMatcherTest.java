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
import java.util.logging.Level;
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

    /**
     * Носитель, унаследовавший проверку. Мутация: обойти иерархию вверх
     * ({@code t.getSuperClass().asErasure()}) вместо одних объявленных — RED.
     */
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

    /** Только новости потребителю: след «какие именно» идёт на FINE и от соседей зависит. */
    private static List<String> новости(List<LogRecord> records) {
        return records.stream()
                .filter(record -> record.getLevel() == Level.WARNING)
                .map(LogRecord::getMessage)
                .toList();
    }

    @BeforeEach
    void забытьСказанное() {
        DiagnosticsReset.forget();
    }

    @Test
    @DisplayName("настоящий носитель RestAssured покрыт целиком — положительный якорь детектора")
    void настоящийНосительПокрытЦеликом() {
        // Без него всё ниже доказывало бы лишь то, что детектор умеет говорить «нет».
        TypePool pool = TypePool.Default.of(getClass().getClassLoader());
        TypeDescription carrier = pool
                .describe("io.restassured.internal.ValidatableResponseOptionsImpl").resolve();

        assertThat(AllureRestAssuredValidationInstrumentation.uncoveredValidationMethods(carrier))
                .as("перехват проверок RestAssured мёртв уже сейчас — эти имена вплетать не во что")
                .isEmpty();
    }

    @Test
    @DisplayName("метод объявлен в предке — вплетать в этот тип нечего")
    void унаследованныйМетодНеСчитается() {
        // ByteBuddy вплетает ТОЛЬКО в объявителя, поэтому унаследованный метод — это «нет».
        assertThat(AllureRestAssuredValidationInstrumentation
                .uncoveredValidationMethods(TypeDescription.ForLoadedType.of(Inheriting.class)))
                .as("унаследованный метод посчитан за свой — детектор врёт в самую опасную сторону")
                .contains("statusCode");
        assertThat(AllureRestAssuredValidationInstrumentation
                .uncoveredValidationMethods(TypeDescription.ForLoadedType.of(Declaring.class)))
                .as("свой объявленный метод не посчитан — детектор слеп и в обратную сторону")
                .doesNotContain("statusCode");
    }

    @Test
    @DisplayName("остались только исключённые перегрузки — считается за «нечего»")
    void толькоИсключённыеПерегрузкиНеСчитаются() {
        // Имена на месте, а вплетать не во что: 0-арг и boolean — log-варианты, header
        // с матчером-обёрткой матчер исключает сам.
        assertThat(AllureRestAssuredValidationInstrumentation
                .uncoveredValidationMethods(TypeDescription.ForLoadedType.of(OnlyExcluded.class)))
                .as("детектор смотрит на имена, а не на перегрузки — так мёртвый перехват пройдёт")
                .contains("body", "header");
    }

    @Test
    @DisplayName("расхождение по ОДНОМУ имени уже видно: порог не «совпало хоть что-то»")
    void частичноеРасхождениеЗамечено() {
        // Носитель расходится по одному методу, а не целиком. У Declaring объявлен statusCode
        // и больше ничего — то есть девять проверок из десяти вплетать не во что, а порог
        // «совпало хоть что-то» был бы здесь истинным и промолчал.
        //
        // Мутация: вернуть порог «хоть что-то» (одна проверка на весь список) → RED.
        assertThat(AllureRestAssuredValidationInstrumentation
                .uncoveredValidationMethods(TypeDescription.ForLoadedType.of(Declaring.class)))
                .as("пропажа отдельной проверки не замечена — детектор меряет не то разрешение")
                .contains("body", "cookie", "time")
                .hasSize(9);
    }

    @Test
    @DisplayName("пустой носитель: детектор не выдаёт «ничего не измерил» за «всё хорошо»")
    void пустойНосительНеСовпадает() {
        assertThat(AllureRestAssuredValidationInstrumentation
                .uncoveredValidationMethods(TypeDescription.ForLoadedType.of(Empty.class)))
                .hasSize(10);
    }

    @Test
    @DisplayName("вплетать нечего — сказано вслух, и ровно один раз")
    void молчанияНеОстаётся() {
        List<LogRecord> said = LibraryLog.capture(() -> {
            AllureRestAssuredValidationInstrumentation.announceIfSilent(List.of("statusCode"));
            AllureRestAssuredValidationInstrumentation.announceIfSilent(List.of("statusCode"));
        });

        // Фильтр по уровню обязателен: след «какие именно» идёт на FINE, и виден он или нет —
        // зависит от настроек логирования вокруг, то есть от соседних тест-классов.
        assertThat(новости(said))
                .as("раздел проверок обеднел молча — отчёт выглядит полным, и никто не поймёт почему")
                .anyMatch(message -> message.contains("не нашёл, во что вплетаться"));
        assertThat(новости(said))
                .as("сказано дважды — это шум в каждой сборке, ровно то, от чего лечит #74")
                .hasSize(1);
    }

    @Test
    @DisplayName("перехват выключен потребителем — про потерю не говорим")
    void приВыключателеМолчим() {
        // Предупреждать о потере того, что выключили сам, — шум в чужой сборке.
        String was = System.getProperty(AllureInstrumentation.SWITCH);
        System.setProperty(AllureInstrumentation.SWITCH, "off");
        List<LogRecord> приВыключенном;
        try {
            приВыключенном = LibraryLog.capture(
                    () -> AllureRestAssuredValidationInstrumentation.announceIfSilent(List.of("statusCode")));
        } finally {
            if (was == null) {
                System.clearProperty(AllureInstrumentation.SWITCH);
            } else {
                System.setProperty(AllureInstrumentation.SWITCH, was);
            }
        }

        assertThat(новости(приВыключенном))
                .as("сказали про потерю раздела, который потребитель выключил сам")
                .isEmpty();
        // ЯКОРЬ на том же канале: пустой список выше неотличим от сломанной фикстуры перехвата.
        DiagnosticsReset.forget();
        assertThat(новости(LibraryLog.capture(
                () -> AllureRestAssuredValidationInstrumentation.announceIfSilent(List.of("statusCode")))))
                .as("канал молчит и при включённом перехвате — проверка отсутствия ничего не значит")
                .hasSize(1);
    }

    @Test
    @DisplayName("вплетать есть что — молчим")
    void приСовпаденииМолчим() {
        DiagnosticsReset.forget();
        List<LogRecord> said = LibraryLog.capture(
                () -> AllureRestAssuredValidationInstrumentation.announceIfSilent(List.of()));

        assertThat(новости(said)).as("детектор говорит всегда — это шум, а не сигнал").isEmpty();
        // ЯКОРЬ: тот же канал обязан говорить, когда есть о чём.
        DiagnosticsReset.forget();
        assertThat(новости(LibraryLog.capture(
                () -> AllureRestAssuredValidationInstrumentation.announceIfSilent(List.of("body")))))
                .as("канал молчит всегда — проверка отсутствия выше ничего не доказывает")
                .hasSize(1);
    }
}
