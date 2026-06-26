package io.github.kolomyychenkoai.allure.spring.unit;

import io.github.kolomyychenkoai.allure.spring.rest.internal.AllureWebTestClientLogger;
import io.github.kolomyychenkoai.allure.spring.support.InMemoryAllure;
import io.qameta.allure.model.StepResult;
import io.qameta.allure.model.TestResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.reactive.server.EntityExchangeResult;

import java.net.URI;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/**
 * Уровень A: логика логгера WebTestClient без поднятия Spring-контекста — собираем
 * {@code EntityExchangeResult} (webflux на classpath нет, поэтому мокаем результат обмена) и
 * зовём {@code log(result)} напрямую. Дополняет уровень B ({@code WebTestClientReportIT})
 * детерминированной проверкой шага/вложений и гейта активного кейса.
 */
class AllureWebTestClientLoggerTest {

    private InMemoryAllure allure;

    @BeforeEach
    void setUp() {
        allure = new InMemoryAllure().install();
    }

    @AfterEach
    void tearDown() {
        allure.uninstall();
    }

    private static EntityExchangeResult<byte[]> result() {
        HttpHeaders responseHeaders = new HttpHeaders();
        responseHeaders.add("Content-Type", "application/json");

        @SuppressWarnings("unchecked")
        EntityExchangeResult<byte[]> result = mock(EntityExchangeResult.class);
        lenient().when(result.getMethod()).thenReturn(HttpMethod.GET);
        lenient().when(result.getUrl()).thenReturn(URI.create("http://localhost/api/hello/world"));
        lenient().when(result.getStatus()).thenReturn(HttpStatus.OK);
        lenient().when(result.getRequestHeaders()).thenReturn(new HttpHeaders());
        lenient().when(result.getResponseHeaders()).thenReturn(responseHeaders);
        lenient().when(result.getRequestBodyContent()).thenReturn(new byte[0]);
        lenient().when(result.getResponseBodyContent())
                .thenReturn("{\"greeting\":\"hello world\"}".getBytes(StandardCharsets.UTF_8));
        return result;
    }

    @Test
    @DisplayName("обмен даёт шаг «HTTP GET path → 200» с телом ответа во вложении")
    void logsExchange() {
        EntityExchangeResult<byte[]> result = result();

        TestResult report = allure.run("wtc", () -> AllureWebTestClientLogger.log(result));

        assertThat(report.getSteps().stream().map(s -> s.getName()))
                .anyMatch(n -> n.startsWith("HTTP GET") && n.endsWith("/api/hello/world → 200"));
        assertThat(allure.attachment(report, "HTTP Response").orElseThrow()).contains("hello world");
    }

    @Test
    @DisplayName("без активного тест-кейса логгер в отчёт ничего не пишет")
    void noStepWithoutActiveCase() {
        // вне allure.run(...) активного кейса нет → log должен тихо вернуться
        AllureWebTestClientLogger.log(result());

        assertThat(allure.wroteNothing()).isTrue(); // убери гейт isPresent() → запишет вложения → покраснеет
    }

    @Test
    @DisplayName("дедуп: тот же обмен из consumer'а (с телом) и фильтра даёт РОВНО ОДИН HTTP-шаг")
    void deduplicatesConsumerAndFilterExchange() {
        // общий статический буфер изолируем от чужих записей прогона
        AllureWebTestClientLogger.clear();
        try {
            TestResult report = allure.run("dedup", () -> {
                // consumer-путь (с телом) логирует сразу И помечает обмен в HANDLED
                AllureWebTestClientLogger.log(result());
                // фильтр-путь: ТОТ ЖЕ method+url (GET http://localhost/api/hello/world) — статус-онли
                AllureWebTestClientLogger.capture(HttpMethod.GET,
                        URI.create("http://localhost/api/hello/world"), HttpStatus.OK,
                        new HttpHeaders(), new HttpHeaders());
                // проигрывание буфера должно ВЫЧЕСТЬ обмен по ключу METHOD url, а не задвоить шаг
                AllureWebTestClientLogger.flush();
            });

            // мутация: сломай дедуп по ключу HANDLED во flush — обмен покажется дважды → 2 шага
            long httpSteps = report.getSteps().stream()
                    .map(StepResult::getName)
                    .filter(n -> n.startsWith("HTTP GET") && n.endsWith("/api/hello/world → 200"))
                    .count();
            assertThat(httpSteps).as("обмен показан consumer'ом и фильтром — но в отчёте ровно один HTTP-шаг")
                    .isEqualTo(1);
        } finally {
            AllureWebTestClientLogger.clear();
        }
    }

    @Test
    @DisplayName("flush БЕЗ активного кейса чистит буфер — статус-онли обмен не утекает в следующий тест")
    void bufferedExchangeDoesNotLeakWhenFlushedWithoutActiveCase() {
        // общий статический буфер изолируем от чужих записей прогона
        AllureWebTestClientLogger.clear();
        try {
            // фильтр-путь на реактивном потоке (активного кейса нет) — обмен уходит в буфер, в отчёт пока ничего
            AllureWebTestClientLogger.capture(HttpMethod.DELETE,
                    URI.create("http://localhost/api/leak"), HttpStatus.NO_CONTENT,
                    new HttpHeaders(), new HttpHeaders());
            assertThat(allure.wroteNothing()).isTrue();

            // flush БЕЗ активного кейса ОБЯЗАН очистить буфер (привязать обмен не к чему).
            // Мутация: убери clear() в ветке «нет кейса» во flush — обмен доживёт до след. теста.
            AllureWebTestClientLogger.flush();

            // следующий тест-кейс: буфер уже пуст → ни одного HTTP-шага из утёкшего обмена
            TestResult next = allure.run("next-test", AllureWebTestClientLogger::flush);
            assertThat(next.getSteps().stream().map(StepResult::getName))
                    .as("статус-онли обмен из буфера не должен утечь в следующий тест")
                    .noneMatch(n -> n.contains("/api/leak"));
        } finally {
            AllureWebTestClientLogger.clear();
        }
    }
}
