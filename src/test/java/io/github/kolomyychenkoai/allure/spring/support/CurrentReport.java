package io.github.kolomyychenkoai.allure.spring.support;

import io.qameta.allure.Allure;
import io.qameta.allure.model.Attachment;
import io.qameta.allure.model.StepResult;

import java.util.ArrayList;
import java.util.List;

/**
 * Чтение шагов/вложений ИЗ ТЕКУЩЕГО (реального) Allure тест-кейса — для уровня B.
 * В отличие от {@link InMemoryAllure} НЕ подменяет lifecycle: инструментация пишет шаги в
 * настоящий тест-кейс (они попадают в реальный отчёт — showcase сохраняется), а тест тут же
 * читает их и проверяет, что вся цепочка (регистрация + байткод/листенеры + перехват)
 * действительно записала ожидаемое. Так level-B ловит поломку отчёта, не теряя демонстрацию.
 */
public final class CurrentReport {

    private CurrentReport() {
    }

    /** Плоский список шагов текущего тест-кейса (с вложенными). */
    public static List<StepResult> steps() {
        List<StepResult> out = new ArrayList<>();
        Allure.getLifecycle().updateTestCase(tr -> flatten(tr.getSteps(), out));
        return out;
    }

    /** Имена шагов текущего тест-кейса (с вложенными). */
    public static List<String> stepNames() {
        return steps().stream().map(StepResult::getName).toList();
    }

    /** Имена всех вложений (на шагах любого уровня). */
    public static List<String> attachmentNames() {
        return steps().stream().flatMap(s -> s.getAttachments().stream())
                .map(Attachment::getName).toList();
    }

    private static void flatten(List<StepResult> steps, List<StepResult> out) {
        if (steps == null) {
            return;
        }
        for (StepResult s : steps) {
            out.add(s);
            flatten(s.getSteps(), out);
        }
    }
}
