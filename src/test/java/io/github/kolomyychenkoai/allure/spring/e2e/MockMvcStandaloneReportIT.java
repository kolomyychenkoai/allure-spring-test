package io.github.kolomyychenkoai.allure.spring.e2e;

import io.github.kolomyychenkoai.allure.spring.support.CurrentReport;
import io.github.kolomyychenkoai.allure.spring.support.WebTestApp;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Уровень B: собранный РУКАМИ MockMvc ({@code standaloneSetup}, мимо кастомайзера Spring Boot)
 * попадает в отчёт через байткод-перехват {@code perform()}. Этого раньше не было —
 * standalone был «слепой зоной». Тест в Spring-контексте, т.к. установка инструментирования
 * идёт из TestExecutionListener (типичный кейс: @SpringBootTest + ручной MockMvc).
 */
@SpringBootTest(classes = WebTestApp.class)
@Epic("allure-spring-test")
@Feature("HTTP-вызовы (MockMvc standalone)")
class MockMvcStandaloneReportIT {

    @Test
    @DisplayName("standaloneSetup MockMvc даёт ровно один HTTP-шаг (без кастомайзера)")
    void standaloneMockMvcAppearsInReport() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new WebTestApp.DemoController()).build();

        mockMvc.perform(get("/api/hello/{name}", "world")).andExpect(status().isOk());

        List<String> steps = CurrentReport.stepNames();
        long count = steps.stream().filter("HTTP GET /api/hello/world → 200"::equals).count();
        assertEquals(1, count, () -> "ожидали ровно один HTTP-шаг standalone MockMvc: " + steps);

        String resp = CurrentReport.attachmentContent("HTTP Response").orElse("");
        assertTrue(resp.contains("hello world"), () -> "HTTP Response без тела: " + resp);
    }
}
