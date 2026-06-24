package io.github.kolomyychenkoai.allure.spring.mock;

import io.github.kolomyychenkoai.allure.spring.mock.internal.AllureMockitoHandler;
import org.mockito.internal.creation.bytebuddy.InlineByteBuddyMockMaker;
import org.mockito.invocation.MockHandler;
import org.mockito.mock.MockCreationSettings;
import org.mockito.plugins.MockMaker;

import java.util.Optional;

/**
 * Кастомный Mockito {@link MockMaker}: оборачивает дефолтный inline-maker и подменяет
 * {@link MockHandler} на {@link AllureMockitoHandler}, который логирует взаимодействия с
 * моками в Allure. Активируется через SPI-файл
 * {@code mockito-extensions/org.mockito.plugins.MockMaker} в корне test-classpath —
 * код в тестах не нужен (opt-in, см. README).
 * <p>
 * ВНИМАНИЕ (радиус): SPI-файл делает этот maker глобальным для ВСЕХ моков в JVM
 * потребителя. Поведение моков не меняется (делегируем всё дефолтному maker'у),
 * добавляется только логирование. Выключается удалением SPI-файла (maker — opt-in).
 */
public class AllureMockitoMockMaker implements MockMaker {

    private final InlineByteBuddyMockMaker delegate = new InlineByteBuddyMockMaker();

    @Override
    public <T> T createMock(MockCreationSettings<T> settings, MockHandler handler) {
        return delegate.createMock(settings, new AllureMockitoHandler<>(handler));
    }

    @Override
    public <T> Optional<T> createSpy(MockCreationSettings<T> settings, MockHandler handler, T spiedInstance) {
        return delegate.createSpy(settings, new AllureMockitoHandler<>(handler), spiedInstance);
    }

    @Override
    public MockHandler getHandler(Object mock) {
        return delegate.getHandler(mock);
    }

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void resetMock(Object mock, MockHandler newHandler, MockCreationSettings settings) {
        delegate.resetMock(mock, new AllureMockitoHandler<>(newHandler), settings);
    }

    @Override
    public TypeMockability isTypeMockable(Class<?> type) {
        return delegate.isTypeMockable(type);
    }

    @Override
    public void clearAllCaches() {
        delegate.clearAllCaches();
    }
}
