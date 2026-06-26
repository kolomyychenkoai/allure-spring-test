package io.github.kolomyychenkoai.allure.spring.demo;

import io.github.kolomyychenkoai.allure.spring.support.CurrentReport;
import io.github.kolomyychenkoai.allure.spring.support.TestApp;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Уровень B: ожидание Awaitility попадает в отчёт через слушатель, навешенный
 * {@code AllureAwaitilityListener} (официальный SPI). Ожидание идёт на тест-потоке (активный
 * кейс) → шаг виден сразу, без @AfterAll. Раньше Awaitility-ожидания в отчёт не попадали.
 */
@SpringBootTest(classes = TestApp.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Epic("allure-spring-test")
@Feature("Ожидания (Awaitility)")
class AwaitilityReportIT {

    @Test
    @DisplayName("await()...until() даёт шаг «Ожидание: …» в отчёте")
    void awaitAppearsInReport() {
        Awaitility.await("результат готов").atMost(Duration.ofSeconds(2)).until(() -> true);

        List<String> steps = CurrentReport.stepNames();
        // человекочитаемый алиас, без сырого описания Awaitility (лямбда/FQCN)
        assertTrue(steps.stream().anyMatch(n -> n.matches("Ожидание: результат готов — выполнено за \\d+ мс")),
                () -> "нет читаемого шага ожидания Awaitility: " + steps);
        assertTrue(steps.stream().noneMatch(n -> n.contains("Lambda") || n.contains("defined as")),
                () -> "в шаге ожидания просочился техножаргон Awaitility: " + steps);
        // полное описание условия (что ждали) — во вложении, через реальную цепочку
        assertTrue(CurrentReport.attachmentContent("Условие ожидания").orElse("").contains("результат готов"),
                () -> "нет вложения «Условие ожидания»: " + CurrentReport.attachmentNames());
    }
}
