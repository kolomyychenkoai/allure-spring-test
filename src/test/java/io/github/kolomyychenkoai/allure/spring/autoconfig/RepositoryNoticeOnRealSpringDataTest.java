package io.github.kolomyychenkoai.allure.spring.autoconfig;

import io.github.kolomyychenkoai.allure.spring.data.internal.AllureRepositoryAspect;
import io.github.kolomyychenkoai.allure.spring.internal.ActivationDiagnostics;
import io.github.kolomyychenkoai.allure.spring.internal.AllureInstrumentationLogger;
import io.github.kolomyychenkoai.allure.spring.support.CurrentReport;
import io.github.kolomyychenkoai.allure.spring.support.JpaTestApp;
import io.qameta.allure.Epic;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;


/**
 * Ось блокера круга 2: новость про исчезнувший раздел БД должна дойти до потребителя
 * НАСТОЯЩЕЙ Spring Data, а не только до фикстуры уровня A.
 * <p>
 * Почему уровень B обязателен. Гейт новости спрашивает у фабрики бинов, есть ли у потребителя
 * репозитории. В фазе {@code BeanDefinitionRegistryPostProcessor} репозиторий Spring Data —
 * это ещё определение {@code JpaRepositoryFactoryBean} с неизвестным типом продукта, поэтому
 * запрос по маркеру {@code Repository} отвечает пусто. Собрать такую форму руками уровень A
 * пытался трижды и не воспроизвёл: любая рукотворная фикстура проверяет НАШЕ представление
 * о том, как Spring Data заводит бин, а блокер жил ровно в расхождении представления с фактом.
 * Здесь бины заводит сама Spring Data.
 * <p>
 * Мутации, которые видит этот класс (замерено):
 * <ul>
 *   <li>вернуть {@code @EnableAspectJAutoProxy} на автоконфигурацию → аспект появится,
 *       новости не будет → RED;</li>
 *   <li>спрашивать создатель прокси по имени, а не по типу → инфраструктурный создатель
 *       сойдёт за AspectJ-овский → RED;</li>
 *   <li>гейт по свойству {@code spring.aop.auto} вместо взгляда в реестр → RED;</li>
 *   <li>говорить новость независимо от наличия создателя прокси → краснеет второй тест
 *       класса, {@link #staysSilentWhenProxyCreatorIsThere()}.</li>
 * </ul>
 * ⚠️ Мутация «спросить маркер {@code Repository} вместо фабрики» этот класс НЕ краснит,
 * хотя прежний комментарий это обещал: замерено, что в фазе пост-процессора по маркеру
 * с {@code includeNonSingletons=true} настоящие репозитории ТОЖЕ находятся. Ту мутацию
 * ловят фикстуры уровня A — там самописный DAO начинает считаться репозиторием.
 * <p>
 * ⚠️ Класс поднимает НАСТОЯЩИЙ Spring-контекст и пишет шаги в реальный отчёт, поэтому на нём
 * два обязательства уровня B. {@code @Epic} убирает его из витрины, которую читают ручные QA:
 * подготовка схемы Hibernate даёт шаги «SQL DROP/CREATE», приёмщику ничего не говорящие.
 * Verify — только немым каналом {@link CurrentReport#check}: перехват ассертов превратил бы
 * каждый {@code assertThat} в шаг «Проверка: …» и засорил бы тот самый отчёт.
 */
@Epic("Внутренние проверки библиотеки")
class RepositoryNoticeOnRealSpringDataTest {

    @Test
    @DisplayName("настоящая Spring Data без AspectJ-создателя: про потерянный раздел БД сказано")
    void noticeReachesConsumerOfRealSpringData() {
        ActivationDiagnostics.forgetForTests();

        List<LogRecord> said = logWhile(() -> {
            try (ConfigurableApplicationContext ctx = new SpringApplicationBuilder(JpaTestApp.class)
                    .web(WebApplicationType.NONE)
                    .bannerMode(org.springframework.boot.Banner.Mode.OFF)
                    .properties(
                            "spring.aop.auto=false",
                            // Своя база: контекст поднимается вне тест-кэша, и делить схему
                            // с остальными тестами ему незачем.
                            "spring.datasource.url=jdbc:h2:mem:notice-axis;DB_CLOSE_DELAY=-1",
                            // Схема не нужна: тест смотрит на РЕГИСТРАЦИЮ бинов и строку в логе, запросов не
                            // делает. При create-drop подготовка схемы давала девять шагов
                            // «SQL DROP/CREATE» в реальном отчёте — служебный шум приёмщику.
                            "spring.jpa.hibernate.ddl-auto=none")
                    .run()) {

                // ЯКОРЬ. Без него ассерт про строку прошёл бы и на контексте, где аспект
                // на месте: тогда новость была бы просто ложью, а не сигналом.
                CurrentReport.check(
                        ctx.getBeanNamesForType(AllureRepositoryAspect.class).length == 0,
                        () -> "авто-проксирование выключено, а аспект всё-таки зарегистрирован");

                // Второй якорь: репозитории у потребителя ЕСТЬ. Иначе молчание было бы
                // правильным поведением («терять нечего»), и тест сторожил бы пустоту.
                CurrentReport.check(
                        ctx.getBeanNamesForType(
                                org.springframework.data.repository.Repository.class).length > 0,
                        () -> "в фикстуре нет ни одного репозитория — ось не воспроизведена");
            }
        });

        CurrentReport.check(noticeLines(said) == 1,
                () -> "потребитель настоящей Spring Data не узнал, что раздел «DB Repo.method» "
                        + "из отчёта исчез: строк новости " + noticeLines(said) + ", ожидалась 1");

        LogRecord notice = said.stream()
                .filter(r -> r.getMessage().contains(NOTICE_MARK)).findFirst().orElseThrow();

        // ⚠️ УРОВЕНЬ — половина обещания «цена названа вслух». У потребителя под Logback
        // умолчание INFO: опусти строку до FINE, и она исчезнет ровно у тех, ради кого написана,
        // а сборка останется зелёной. Мутация: note → trace (или Level.FINE внутри note) → RED.
        CurrentReport.check(notice.getLevel() == Level.WARNING,
                () -> "новость ушла уровнем " + notice.getLevel() + ": под умолчаниями Logback "
                        + "потребитель её не увидит, и тихая потеря раздела БД возвращается");

        // ⚠️ ТЕКСТ дальше первых слов. README обещает потребителю не факт потери, а что делать:
        // как вернуть шаги и как заглушить строку. Без этого ассерта текст можно ужать до
        // «AspectJ-создателя прокси нет», и никто не покраснеет — замерено.
        CurrentReport.check(notice.getMessage().contains("spring.aop.auto")
                        && notice.getMessage().contains("diagnostics=off"),
                () -> "новость говорит о потере, но не говорит, что делать: " + notice.getMessage());
    }

    @Test
    @DisplayName("настоящая Spring Data, проксирование на месте: библиотека молчит")
    void staysSilentWhenProxyCreatorIsThere() {
        // Негатив к тесту выше, и единственный владелец этой оси во всём наборе. На уровне A
        // её держать нечем: там нет настоящей фабрики Spring Data, hasRepositoryBeans ложно
        // при любой правке, и новость не может прозвучать даже под мутацией — замерено.
        // Мутация: говорить новость независимо от наличия создателя прокси → RED.
        ActivationDiagnostics.forgetForTests();

        List<LogRecord> said = logWhile(() -> {
            try (ConfigurableApplicationContext ctx = new SpringApplicationBuilder(JpaTestApp.class)
                    .web(WebApplicationType.NONE)
                    .bannerMode(org.springframework.boot.Banner.Mode.OFF)
                    .properties(
                            // Отличие от теста выше ровно одно: авто-проксирование не выключено.
                            "spring.datasource.url=jdbc:h2:mem:silence-axis;DB_CLOSE_DELAY=-1",
                            // Схема не нужна: тест смотрит на РЕГИСТРАЦИЮ бинов и строку в логе, запросов не
                            // делает. При create-drop подготовка схемы давала девять шагов
                            // «SQL DROP/CREATE» в реальном отчёте — служебный шум приёмщику.
                            "spring.jpa.hibernate.ddl-auto=none")
                    .run()) {

                // ЯКОРЬ: раздел БД действительно на месте. Без него молчание было бы правильным
                // и в контексте, где аспекта нет, — то есть тест сторожил бы пустоту.
                CurrentReport.check(
                        ctx.getBeanNamesForType(AllureRepositoryAspect.class).length > 0,
                        () -> "аспект не зарегистрирован — молчать в такой сборке как раз НЕЛЬЗЯ");
            }
        });

        CurrentReport.check(noticeLines(said) == 0,
                () -> "новость про потерянный раздел сказана там, где раздел на месте "
                        + "(строк " + noticeLines(said) + "): предупреждение в каждой зелёной "
                        + "сборке перестают читать");
    }

    @Test
    @DisplayName("ленивая инициализация не съедает новость")
    void noticeSurvivesLazyInitialization() {
        // Замеренная регрессия живого `ledger`: с spring.main.lazy-initialization=true прежняя
        // редакция (побочный эффект в конструкторе @Configuration) молчала — тихая потеря
        // возвращалась. Ради этой оси конструкция и переехала в пост-процессор реестра.
        // Уровень B обязателен по той же причине, что и у соседей: новость может прозвучать
        // только там, где есть НАСТОЯЩАЯ фабрика Spring Data.
        // Мутация: перенести новость в конструктор бина-конфигурации → RED.
        ActivationDiagnostics.forgetForTests();

        List<LogRecord> said = logWhile(() -> {
            try (ConfigurableApplicationContext ctx = new SpringApplicationBuilder(JpaTestApp.class)
                    .web(WebApplicationType.NONE)
                    .bannerMode(org.springframework.boot.Banner.Mode.OFF)
                    .properties(
                            "spring.aop.auto=false",
                            // Отличие от noticeReachesConsumerOfRealSpringData ровно одно.
                            "spring.main.lazy-initialization=true",
                            "spring.datasource.url=jdbc:h2:mem:lazy-axis;DB_CLOSE_DELAY=-1",
                            "spring.jpa.hibernate.ddl-auto=none")
                    .run()) {

                CurrentReport.check(
                        ctx.getBeanNamesForType(AllureRepositoryAspect.class).length == 0,
                        () -> "авто-проксирование выключено, а аспект всё-таки зарегистрирован");
            }
        });

        CurrentReport.check(noticeLines(said) == 1,
                () -> "под ленивой инициализацией новость пропала: строк " + noticeLines(said)
                        + ", ожидалась 1. Ровно та тихая потеря, что была на живом `ledger`");
    }

    /** Опознавательный кусок новости — по нему её отличаем от прочих строк логгера. */
    private static final String NOTICE_MARK = "AspectJ-создателя прокси";

    /** Сколько раз прозвучала новость про отсутствующий AspectJ-создатель. */
    private static long noticeLines(List<LogRecord> said) {
        return said.stream().filter(r -> r.getMessage().contains(NOTICE_MARK)).count();
    }

    /** Что библиотека сказала в свой логгер, пока поднимался контекст. */
    private static List<LogRecord> logWhile(Runnable action) {
        List<LogRecord> records = new ArrayList<>();
        Handler collector = new Handler() {
            @Override
            public void publish(LogRecord record) {
                records.add(record);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        Logger logger = AllureInstrumentationLogger.logger();
        logger.addHandler(collector);
        try {
            action.run();
        } finally {
            logger.removeHandler(collector);
        }
        return records;
    }
}
