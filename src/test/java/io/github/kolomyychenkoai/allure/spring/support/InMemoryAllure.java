package io.github.kolomyychenkoai.allure.spring.support;

import io.qameta.allure.Allure;
import io.qameta.allure.AllureLifecycle;
import io.qameta.allure.AllureResultsWriter;
import io.qameta.allure.model.Attachment;
import io.qameta.allure.model.StepResult;
import io.qameta.allure.model.TestResult;
import io.qameta.allure.model.TestResultContainer;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-memory реализация Allure {@link AllureResultsWriter}: вместо записи в файлы
 * собирает результаты тестов и содержимое вложений в память. Позволяет
 * детерминированно проверять, что именно листенеры положили в отчёт.
 */
public final class InMemoryAllure implements AllureResultsWriter {

    private final List<TestResult> results = new CopyOnWriteArrayList<>();
    private final Map<String, byte[]> attachmentBytes = new ConcurrentHashMap<>();
    private AllureLifecycle lifecycle;
    private AllureLifecycle previous;

    /** Подменяет глобальный Allure lifecycle на in-memory, запомнив прежний. */
    public InMemoryAllure install() {
        this.previous = Allure.getLifecycle();
        this.lifecycle = new AllureLifecycle(this);
        Allure.setLifecycle(this.lifecycle);
        return this;
    }

    /**
     * Возвращает ИМЕННО прежний lifecycle (а не новый) — это важно: интеграция
     * allure-junit кэширует ссылку, и подмена на свежий экземпляр её бы рассинхронила.
     */
    public void uninstall() {
        if (previous != null) {
            Allure.setLifecycle(previous);
        }
    }

    /**
     * Прогоняет тело внутри полноценного allure-тест-кейса (schedule→start→stop→write)
     * и возвращает записанный {@link TestResult}.
     */
    public TestResult run(String name, Runnable body) {
        String uuid = UUID.randomUUID().toString();
        lifecycle.scheduleTestCase(new TestResult().setUuid(uuid).setName(name));
        lifecycle.startTestCase(uuid);
        try {
            body.run();
        } finally {
            lifecycle.stopTestCase(uuid);
            lifecycle.writeTestCase(uuid);
        }
        return results.stream()
                .filter(r -> uuid.equals(r.getUuid()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("test result not written: " + uuid));
    }

    /** Содержимое вложения по имени (ищет на уровне теста и во всех вложенных шагах). */
    public Optional<String> attachment(TestResult result, String name) {
        return allAttachments(result.getAttachments(), result.getSteps()).stream()
                .filter(a -> name.equals(a.getName()))
                .map(a -> attachmentBytes.get(a.getSource()))
                .filter(Objects::nonNull)
                .map(b -> new String(b, StandardCharsets.UTF_8))
                .findFirst();
    }

    /** Есть ли шаг с таким именем (на верхнем уровне). */
    public boolean hasStep(TestResult result, String name) {
        return result.getSteps().stream().anyMatch(s -> name.equals(s.getName()));
    }

    /**
     * Записано ли в отчёт ХОТЬ ЧТО-ТО (результат теста или байты вложения). Для тестов
     * гейта активного кейса: вызов инструментирования БЕЗ {@code run(...)} не должен писать
     * ничего. Если гейт убрать — {@code Allure.step}/{@code addAttachment} запишут байты
     * вложения через этот writer даже без активного кейса, и проверка покраснеет.
     */
    public boolean wroteNothing() {
        return results.isEmpty() && attachmentBytes.isEmpty();
    }

    private static List<Attachment> allAttachments(List<Attachment> top, List<StepResult> steps) {
        List<Attachment> all = new ArrayList<>(top);
        for (StepResult step : steps) {
            all.addAll(step.getAttachments());
            all.addAll(allAttachments(List.of(), step.getSteps()));
        }
        return all;
    }

    @Override
    public void write(TestResult testResult) {
        results.add(testResult);
    }

    @Override
    public void write(TestResultContainer testResultContainer) {
        // контейнеры нам не нужны
    }

    @Override
    public void write(String source, InputStream attachment) {
        try {
            attachmentBytes.put(source, attachment.readAllBytes());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
