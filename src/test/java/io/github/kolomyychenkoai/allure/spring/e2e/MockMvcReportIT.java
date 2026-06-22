package io.github.kolomyychenkoai.allure.spring.e2e;

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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Уровень B: «живой» прогон. Никакой настройки Allure в тесте — обработчик
 * подключается САМ (через auto-configuration) и пишет HTTP-шаги в реальный отчёт.
 * Смотреть: {@code mvn allure:serve}.
 */
@SpringBootTest(classes = WebTestApp.class)
@AutoConfigureMockMvc
@Epic("allure-spring-test")
@Feature("HTTP-вызовы (MockMvc)")
class MockMvcReportIT {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("GET, POST, query и 404 через MockMvc автоматически попадают в отчёт")
    void httpCallsAppearInReport() throws Exception {
        mockMvc.perform(get("/api/hello/{name}", "world"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/echo")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productName\":\"laptop\",\"quantity\":2}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/search").queryParam("q", "laptop"))
                .andExpect(status().isOk());

        // негативный сценарий — в отчёте виден шаг с не-200
        mockMvc.perform(get("/api/does-not-exist"))
                .andExpect(status().isNotFound());
    }
}
