package io.github.kolomyychenkoai.allure.spring.canary;

import io.github.kolomyychenkoai.allure.spring.support.Versions;
import io.qameta.allure.Allure;
import io.qameta.allure.Epic;
import io.qameta.allure.model.Status;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Канарейка на API САМОГО Allure — библиотеки, чью версию мы навязываем чужому дереву
 * зависимостей.
 * <p>
 * Отдельно от {@link InstrumentationApiCanaryTest} намеренно: там тема — «чужие библиотеки,
 * которые мы ИНСТРУМЕНТИРУЕМ» (матчеры-строки, компилятор их не проверяет). Здесь тема другая:
 * {@code allure-java-commons} — ЕДИНСТВЕННАЯ наша зависимость в scope {@code compile}, то есть
 * единственная версия, которую мы навязываем потребителю транзитивно. До этого теста на неё не
 * было ни одной проверки вообще.
 * <p>
 * Что здесь есть, чего не даёт компилятор:
 * <ul>
 *   <li><b>именованный инвентарь допущений</b> — при апгрейде падает точечно и с указателем
 *       в наш код, а не {@code NoSuchMethodError} посреди чужого прогона;</li>
 *   <li><b>гейт объявленных границ</b> ({@link #versionWithinDeclaredRange}) — компилятор про
 *       границы не знает вовсе;</li>
 *   <li>возможность прогнать одним классом против произвольного jar-а Allure.</li>
 * </ul>
 */
@Epic("Внутренние проверки библиотеки")
@DisplayName("Канарейка API Allure (единственная зависимость, которую мы навязываем)")
class AllureApiCanaryTest {

    /** Немой ассерт: Jupiter Assertions перехвачены, обычный assert сам стал бы шагом в отчёте. */
    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    /**
     * Есть ли метод с ТОЧНОЙ сигнатурой. Именно точной: {@code updateStep} существует в двух
     * арностях, {@code Allure.step} — в четырёх перегрузках, и мы зовём вполне конкретные.
     * Проверка «есть метод с таким именем» пропустила бы ровно тот случай, ради которого всё это.
     */
    private static boolean hasExact(String className, String method, String... paramTypes) {
        try {
            Class<?>[] types = new Class<?>[paramTypes.length];
            for (int i = 0; i < paramTypes.length; i++) {
                types[i] = Class.forName(paramTypes[i]);
            }
            Class.forName(className).getMethod(method, types);
            return true;
        } catch (ReflectiveOperationException e) {
            return false;
        }
    }

    private static boolean classPresent(String className) {
        try {
            Class.forName(className);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    private static final String LIFECYCLE = "io.qameta.allure.AllureLifecycle";
    private static final String ALLURE = "io.qameta.allure.Allure";
    private static final String STEP_RESULT = "io.qameta.allure.model.StepResult";
    private static final String CONSUMER = "java.util.function.Consumer";

    @Test
    @DisplayName("AllureLifecycle: шесть методов, которыми мы правим отчёт вручную")
    void lifecycleMethods() {
        require(hasExact(LIFECYCLE, "getCurrentTestCase"),
                "AllureLifecycle.getCurrentTestCase уехал → на нём стоит гейт «есть активный кейс» "
                        + "во ВСЕХ модулях (AllureAdviceSupport.step и далее): без него шаги полезут "
                        + "в чужой контекст и в лог посыплется «no test case running»");
        require(hasExact(LIFECYCLE, "startStep", "java.lang.String", STEP_RESULT),
                "AllureLifecycle.startStep(String, StepResult) уехал → AllureRepositoryAspect и "
                        + "AllureJdbcInstrumentation открывают шаг вручную (обернуть лямбдой нельзя — advice)");
        require(hasExact(LIFECYCLE, "stopStep", "java.lang.String"),
                "AllureLifecycle.stopStep(String) уехал → парный к startStep, шаги останутся незакрытыми");
        require(hasExact(LIFECYCLE, "updateStep", "java.lang.String", CONSUMER),
                "AllureLifecycle.updateStep(String, Consumer) уехал → им проставляется статус и "
                        + "вложения уже открытого шага");
        require(hasExact(LIFECYCLE, "updateTestCase", CONSUMER),
                "AllureLifecycle.updateTestCase(Consumer) уехал → AllureWireMockSteps вешает готовый "
                        + "StepResult прямо на тест-кейс");
        require(hasExact(LIFECYCLE, "writeAttachment", "java.lang.String", "java.io.InputStream"),
                "AllureLifecycle.writeAttachment уехал → AllureWireMockSteps пишет near-miss байтами напрямую");
    }

    @Test
    @DisplayName("Allure: четыре перегрузки step и трёхарг. addAttachment")
    void staticFacade() {
        // Вложенные типы проверяем ОТДЕЛЬНО и ПЕРВЫМИ: hasExact вернёт false и когда уехал метод,
        // и когда уехал тип параметра — это два разных диагноза, и путать их дорого.
        require(classPresent("io.qameta.allure.Allure$StepContext"),
                "Allure.StepContext уехал → это тип параметра лямбды в step(String, ctx -> …), "
                        + "самой употребительной форме шага у нас");
        String contextRunnable = "io.qameta.allure.Allure$ThrowableContextRunnableVoid";
        String runnable = "io.qameta.allure.Allure$ThrowableRunnableVoid";
        require(classPresent(contextRunnable), "Allure.ThrowableContextRunnableVoid уехал → см. выше");
        require(classPresent(runnable), "Allure.ThrowableRunnableVoid уехал → step(String, () -> …)");

        require(hasExact(ALLURE, "step", "java.lang.String"),
                "Allure.step(String) уехал → AllureWireMockSteps");
        require(hasExact(ALLURE, "step", "java.lang.String", "io.qameta.allure.model.Status"),
                "Allure.step(String, Status) уехал → им пишут шаги ВСЕ advice (готовый результат без тела)");
        require(hasExact(ALLURE, "step", "java.lang.String", runnable),
                "Allure.step(String, ThrowableRunnableVoid) уехал");
        require(hasExact(ALLURE, "step", "java.lang.String", contextRunnable),
                "Allure.step(String, ThrowableContextRunnableVoid) уехал → это САМАЯ МОЛОДАЯ из "
                        + "используемых перегрузок и главный кандидат на несовместимость со старым Allure");
        require(hasExact(ALLURE, "addAttachment", "java.lang.String", "java.lang.String", "java.lang.String"),
                "Allure.addAttachment(имя, тип, содержимое) уехал → через него идут ВСЕ вложения "
                        + "(AllureAdviceSupport.attach/attachBody)");
        require(hasExact(ALLURE, "getLifecycle"),
                "Allure.getLifecycle уехал → точка входа во всё ручное редактирование отчёта");
    }

    @Test
    @DisplayName("Модель: StepResult/TestResult/Status — то, что мы собираем руками")
    void model() {
        require(hasExact(STEP_RESULT, "setName", "java.lang.String"), "StepResult.setName уехал");
        require(hasExact(STEP_RESULT, "setStatus", "io.qameta.allure.model.Status"), "StepResult.setStatus уехал");
        require(hasExact(STEP_RESULT, "getSteps"), "StepResult.getSteps уехал → вложенные шаги");
        require(hasExact("io.qameta.allure.model.TestResult", "getSteps"),
                "TestResult.getSteps уехал → updateTestCase(tr -> tr.getSteps().add(…))");
        // Константы статусов: исчезнувшую в ИСХОДНИКАХ поймал бы компилятор, а исчезнувшую в
        // РАНТАЙМЕ (другой jar на classpath потребителя) — только это.
        for (String name : new String[]{"PASSED", "FAILED", "BROKEN", "SKIPPED"}) {
            boolean present = false;
            for (Status status : Status.values()) {
                present |= status.name().equals(name);
            }
            require(present, "Status." + name + " уехал → статусы шагов проставляются по нему");
        }
        require(hasExact("io.qameta.allure.model.Attachment", "getSource"),
                "Attachment.getSource уехал → по нему инвентарь отчёта находит ФАЙЛ вложения");
    }

    @Test
    @DisplayName("версия Allure в рантайме внутри границ, объявленных в pom (compat.allure.min/max)")
    void versionWithinDeclaredRange() {
        String actual = Allure.class.getPackage().getImplementationVersion();
        require(actual != null,
                "не удалось определить версию allure-java-commons: в jar нет Implementation-Version "
                        + "либо тесты идут против распакованных классов. Ветки «не смог проверить → "
                        + "считаю, что всё хорошо» у детектора быть не должно — чини запуск, а не тест");

        String min = System.getProperty("allure.compat.min");
        String max = System.getProperty("allure.compat.max");
        require(min != null && max != null,
                "границы не пришли из pom (systemPropertyVariables allure.compat.min/max). "
                        + "Держать их второй копией в коде нельзя — разъедутся с README");

        require(compare(actual, min) >= 0, "Allure " + actual + " НИЖЕ объявленного минимума " + min
                + " → либо подними зависимость, либо ОСОЗНАННО опусти границу в pom и README "
                + "(и прогони mvn -Pcompat-allure-min clean test)");
        require(compare(actual, max) <= 0, "Allure " + actual + " ВЫШЕ проверенного потолка " + max
                + " → прогони compat-матрицу и подними compat.allure.max в pom вместе с таблицей "
                + "в README. Красный здесь — правильный триггер: потолок не должен ползти молча");
    }

    /** Сравнение версий живёт в {@link Versions} — у него свой тест {@code unit/VersionCompareTest}. */
    private static int compare(String left, String right) {
        return Versions.compare(left, right);
    }
}
