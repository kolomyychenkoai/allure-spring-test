package io.github.kolomyychenkoai.allure.spring.e2e;

import io.github.kolomyychenkoai.allure.spring.support.CurrentReport;
import io.github.kolomyychenkoai.allure.spring.support.WebTestApp;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Уровень B: «живой» прогон через РЕАЛЬНУЮ авто-конфигурацию (MockMvcBuilderCustomizer.alwaysDo
 * → AllureMockMvcResultHandler). Шаги пишутся в НАСТОЯЩИЙ отчёт (showcase сохраняется), а тест
 * читает их через {@link CurrentReport} и проверяет наличие. Проверки — на JUnit assertTrue
 * (он НЕ инструментируется, поэтому не засоряет отчёт). Краснеет, если кастомайзер не
 * подключился (auto-config снят) или имя HTTP-шага съехало.
 */
@SpringBootTest(classes = WebTestApp.class)
@AutoConfigureMockMvc
@Epic("allure-spring-test")
@Feature("HTTP-вызовы (MockMvc)")
class MockMvcReportIT {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("GET, POST, query и 404 через MockMvc попадают в отчёт шагами")
    void httpCallsAppearInReport() throws Exception {
        mockMvc.perform(get("/api/hello/{name}", "world")).andExpect(status().isOk());
        mockMvc.perform(post("/api/echo").contentType(MediaType.APPLICATION_JSON)
                .content("{\"productName\":\"laptop\"}")).andExpect(status().isOk());
        mockMvc.perform(get("/api/search").queryParam("q", "laptop")).andExpect(status().isOk());
        mockMvc.perform(get("/api/does-not-exist")).andExpect(status().isNotFound());

        List<String> steps = CurrentReport.stepNames();
        // дедуп: авто-MockMvc цепляют ОБА пути (кастомайзер + байткод), но шаг должен быть ОДИН
        assertEquals(1, steps.stream().filter("HTTP GET /api/hello/world → 200"::equals).count(),
                () -> "ожидали ровно один GET-шаг (дедуп кастомайзер+байткод): " + steps);
        assertTrue(steps.contains("HTTP GET /api/hello/world → 200"), () -> "нет GET-шага: " + steps);
        assertTrue(steps.contains("HTTP POST /api/echo → 200"), () -> "нет POST-шага: " + steps);
        assertTrue(steps.contains("HTTP GET /api/search?q=laptop → 200"), () -> "нет query-шага: " + steps);
        assertTrue(steps.contains("HTTP GET /api/does-not-exist → 404"), () -> "нет 404-шага: " + steps);

        // содержимое вложений пришло через реальную цепочку (не только имя шага)
        String req = CurrentReport.attachmentContent("HTTP Request").orElse("");
        assertTrue(req.contains("GET /api/hello/world"), () -> "HTTP Request без метода/пути: " + req);
        String resp = CurrentReport.attachmentContent("HTTP Response").orElse("");
        assertTrue(resp.contains("hello world"), () -> "HTTP Response без тела: " + resp);
    }
}
