package io.github.kolomyychenkoai.allure.spring.awaitility.internal;

import org.awaitility.Awaitility;

/**
 * Единственное место, где мы трогаем типы Awaitility из хука жизненного цикла.
 * <p>
 * Вынесено из {@code AllureAwaitilityListener} по той же причине, что и
 * {@code AllureRestAssuredRegistrar}: верификатор байткода проверяет присваиваемость
 * {@link AllureAwaitilityConditionListener} к {@code ConditionEvaluationListener} и грузит
 * этот интерфейс ЕЩЁ ДО выполнения гейта «Awaitility на classpath?». Пока код жил в листенере,
 * весь листенер без Awaitility не линковался, а его гейт был мёртвым.
 * Закреплено тестом {@code unit/ListenerDegradationTest}.
 */
public final class AllureAwaitilityRegistrar {

    private AllureAwaitilityRegistrar() {
    }

    /** Регистрирует наш слушатель условий через официальный SPI Awaitility. */
    public static void registerConditionListener() {
        Awaitility.setDefaultConditionEvaluationListener(new AllureAwaitilityConditionListener());
    }
}
