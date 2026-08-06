package io.github.kolomyychenkoai.allure.spring.internal;

import net.bytebuddy.ClassFileVersion;

/**
 * Знает ли byte-buddy формат class-файлов ТЕКУЩЕЙ JVM.
 * <p>
 * Самый коварный способ потерять весь байткод-слой. Апгрейд JDK опережает byte-buddy: агент
 * ставится, {@code installed=true}, а трансформация падает на КАЖДОМ типе — и ассерты, и JDBC,
 * и Kafka, и WireMock, и Liquibase, и Mockito молча исчезают из отчёта. Тесты при этом зелёные.
 * <p>
 * Версию byte-buddy потребитель обычно не выбирает — она приходит из BOM Spring Boot, и каждая
 * знает классы только до своей эпохи: 1.14.x — до Java 21…23, 1.15.x — до 24, 1.17.x — до 26.
 * Отсюда объявленный минимум Boot 3.5 на Java 25: с более старым BOM перехват мёртв, а тесты
 * зелёные.
 * <p>
 * <b>Почему отдельный класс.</b> HotSpot разрешает ссылки в телах методов лениво, поэтому
 * «класс не загрузится без byte-buddy» здесь НЕ работает: {@code ActivationDiagnostics} с этим
 * кодом внутри загрузился бы и без библиотеки (в отличие от {@code AllureInstrumentation},
 * где типы byte-buddy стоят в СИГНАТУРАХ — он действительно не грузится).
 * <p>
 * Ленивое разрешение — деталь реализации JVM, а не гарантия спецификации, и достаточно
 * однажды упомянуть {@link ClassFileVersion} в поле или сигнатуре, чтобы диагност перестал
 * грузиться — то есть умер ровно там, где должен был предупредить.
 * Изоляция стоит один класс, а её отсутствие — весь сигнал. То же правило, что у
 * {@link ByteBuddyPresence}; звать отсюда что-либо можно ТОЛЬКО после
 * {@link ByteBuddyPresence#available()}. Инвариант закреплён тестом
 * {@code unit/ByteBuddyPresenceTest#diagnosticsSurviveWithoutByteBuddy}.
 */
final class ByteBuddyClassFormat {

    private ByteBuddyClassFormat() {
    }

    /** {@code true}, если формат классов этой JVM новее известного byte-buddy. */
    static boolean tooNewForByteBuddy() {
        try {
            // Версию берём у самой JVM, БЕЗ фолбэка ofThisVm(...): фолбэк отдаётся, когда версию
            // определить не удалось, и проверка молчала бы ровно тогда, когда ничего не известно.
            return !ClassFileVersion.ofJavaVersion(Runtime.version().feature())
                    .isAtMost(ClassFileVersion.latest());
        } catch (Throwable unknown) {
            return false; // не смогли выяснить — не пугаем; факт перехвата покажут диагностика и отчёт
        }
    }

    /** Версия byte-buddy для сообщения; {@code "?"} если в jar нет Implementation-Version. */
    static String byteBuddyVersion() {
        try {
            String version = ClassFileVersion.class.getPackage().getImplementationVersion();
            return version == null ? "?" : version;
        } catch (Throwable unknown) {
            return "?";
        }
    }
}
