package io.github.kolomyychenkoai.allure.spring.unit;

import io.github.kolomyychenkoai.allure.spring.rest.internal.AllureMockMvcResultHandler;
import io.github.kolomyychenkoai.allure.spring.support.InMemoryAllure;
import io.github.kolomyychenkoai.allure.spring.support.WebTestApp;
import io.qameta.allure.model.TestResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Уровень A: детерминированная проверка содержимого отчёта для MockMvc-обработчика.
 * Гоняем обработчик через реальный путь MockMvc (standaloneSetup + alwaysDo), но без
 * Spring-контекста — быстро и надёжно.
 */
class AllureMockMvcResultHandlerTest {

    private InMemoryAllure allure;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        allure = new InMemoryAllure().install();
        mockMvc = MockMvcBuilders
                .standaloneSetup(new WebTestApp.DemoController())
                .alwaysDo(new AllureMockMvcResultHandler())
                .build();
    }

    @AfterEach
    void tearDown() {
        allure.uninstall();
    }

    private TestResult perform(String name, RequestBuilder request) {
        return allure.run(name, () -> {
            try {
                mockMvc.perform(request);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    @Test
    @DisplayName("GET даёт шаг «HTTP GET ... → 200»")
    void attachesGetStep() {
        TestResult result = perform("mockmvc-get", get("/api/hello/{name}", "world"));

        assertThat(allure.hasStep(result, "HTTP GET /api/hello/world → 200")).isTrue();
        assertThat(allure.attachment(result, "HTTP Request").orElseThrow())
                .contains("GET /api/hello/world");
        assertThat(allure.attachment(result, "HTTP Response").orElseThrow())
                .contains("200")
                .contains("hello world");
    }

    @Test
    @DisplayName("POST: тело запроса и ответа попадают во вложения")
    void attachesPostRequestAndResponseBodies() {
        TestResult result = perform("mockmvc-post",
                post("/api/echo")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productName\":\"laptop\"}"));

        assertThat(allure.hasStep(result, "HTTP POST /api/echo → 200")).isTrue();
        assertThat(allure.attachment(result, "HTTP Request").orElseThrow())
                .contains("POST /api/echo")
                .contains("productName");
        assertThat(allure.attachment(result, "HTTP Response").orElseThrow())
                .contains("productName");
    }

    @Test
    @DisplayName("query-string попадает в имя шага и в request-вложение")
    void includesQueryString() {
        TestResult result = perform("mockmvc-search", get("/api/search?q=laptop"));

        assertThat(allure.hasStep(result, "HTTP GET /api/search?q=laptop → 200")).isTrue();
        assertThat(allure.attachment(result, "HTTP Request").orElseThrow())
                .contains("GET /api/search?q=laptop");
    }

    @Test
    @DisplayName("при ошибке контроллера статус и причина видны в отчёте")
    void attachesResolvedException() {
        TestResult result = perform("mockmvc-boom", get("/api/boom"));

        assertThat(allure.hasStep(result, "HTTP GET /api/boom → 418")).isTrue();
        assertThat(allure.attachment(result, "HTTP Exception").orElseThrow())
                .contains("boom for test");
    }

    @Test
    @DisplayName("мультизначный заголовок запроса: показаны ВСЕ значения, не только первое")
    void includesAllHeaderValues() {
        TestResult result = perform("mockmvc-multiheader",
                get("/api/hello/{name}", "world").header("X-Multi", "a").header("X-Multi", "b"));

        assertThat(allure.attachment(result, "HTTP Request").orElseThrow())
                .contains("X-Multi: a")
                .contains("X-Multi: b");
    }

    @Test
    @DisplayName("без активного тест-кейса обработчик НИЧЕГО не пишет в отчёт")
    void noStepWithoutActiveCase() throws Exception {
        // perform идёт мимо allure.run → handle отработает без активного кейса
        mockMvc.perform(get("/api/hello/{name}", "world"));

        // убери гейт активного кейса → Allure.step/addAttachment запишут байты вложения → покраснеет
        assertThat(allure.wroteNothing()).isTrue();
    }
}
