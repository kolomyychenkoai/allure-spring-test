package io.github.kolomyychenkoai.allure.spring.rest.internal;

import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import reactor.core.publisher.Mono;

/**
 * {@code ExchangeFilterFunction} на {@code WebTestClient}: видит КАЖДЫЙ обмен (в т.ч. чисто
 * статусные {@code expectStatus()} без чтения тела), которые consumer результата пропускает.
 * Снимает только метаданные (метод/url/статус/заголовки) через {@code doOnNext} — тело НЕ
 * читает, чтобы не «съесть» его у теста; передаёт ответ дальше без изменений.
 * <p>
 * Срабатывает на реактивном потоке без активного Allure-кейса, поэтому лишь буферизует обмен
 * ({@link AllureWebTestClientLogger#capture}); шаги проигрываются на тест-потоке в
 * {@code afterTestMethod} ({@code AllureWebTestClientListener}). Подключается из
 * {@code AllureWebTestClientAutoConfiguration}, код в тестах не нужен.
 */
public final class AllureWebTestClientFilter implements ExchangeFilterFunction {

    @Override
    public Mono<ClientResponse> filter(ClientRequest request, ExchangeFunction next) {
        return next.exchange(request).doOnNext(response ->
                AllureWebTestClientLogger.capture(request.method(), request.url(),
                        response.statusCode(), request.headers(), response.headers().asHttpHeaders()));
    }
}
