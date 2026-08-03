package io.github.kolomyychenkoai.allure.spring.internal;

import net.bytebuddy.ClassFileVersion;

/**
 * Знает ли byte-buddy формат class-файлов ТЕКУЩЕЙ JVM.
 * <p>
 * Самый коварный способ потерять весь байткод-слой. Апгрейд JDK опережает byte-buddy: агент
 * ставится, {@code installed=true}, а трансформация падает на КАЖДОМ типе — и ассерты, и JDBC,
 * и Kafka, и WireMock, и Liquibase, и Mockito молча исчезают из отчёта. Тесты при этом зелёные.
 * <p>
 * Версию byte-buddy потребитель обычно не выбирает — она приходит из BOM Spring Boot: 3.1 даёт
 * 1.14.4 (знает Java 21), 3.4 — 1.15.x (Java 24), 3.5 — 1.17.x (Java 25/26). То есть «Boot 3.4
 * на Java 25» — рабочая с виду комбинация с мёртвым перехватом.
 * <p>
 * <b>Почему отдельный класс.</b> Замерено: сегодня {@code ActivationDiagnostics} с этим кодом
 * внутри загрузился бы и без byte-buddy — HotSpot разрешает ссылки в телах методов лениво
 * (в отличие от {@code AllureInstrumentation}, который не грузится: там типы byte-buddy стоят
 * в СИГНАТУРАХ). То есть «класс не загрузится» здесь не сработало бы, и обещать это в javadoc
 * было бы неправдой.
 * <p>
 * Держим отдельно по другой причине: ленивое разрешение — деталь реализации JVM, а не гарантия
 * спецификации, и достаточно однажды упомянуть {@link ClassFileVersion} в поле или сигнатуре,
 * чтобы диагност перестал грузиться — то есть умер ровно там, где должен был предупредить.
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
