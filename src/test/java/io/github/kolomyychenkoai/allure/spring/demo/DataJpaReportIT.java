package io.github.kolomyychenkoai.allure.spring.demo;

import io.github.kolomyychenkoai.allure.spring.support.CurrentReport;
import io.github.kolomyychenkoai.allure.spring.support.JpaTestApp;
import io.github.kolomyychenkoai.allure.spring.support.jpa.Owner;
import io.github.kolomyychenkoai.allure.spring.support.jpa.OwnerRepository;
import io.github.kolomyychenkoai.allure.spring.support.jpa.Widget;
import io.github.kolomyychenkoai.allure.spring.support.jpa.WidgetRepository;
import io.github.kolomyychenkoai.allure.spring.support.jpa.WidgetService;
import io.qameta.allure.Epic;
import io.qameta.allure.Feature;
import io.qameta.allure.model.Status;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Уровень B: «живой» прогон на H2 через РЕАЛЬНУЮ авто-конфигурацию (аспект репозиториев +
 * datasource-proxy). «DB …» шаги пишутся в настоящий отчёт (showcase); тест читает их через
 * {@link CurrentReport}. Краснеет, если аспект/прокси не подключились или имена шагов съехали.
 * Бизнес-ассерты теста — на AssertJ (они тоже попадают в отчёт, это ок); проверки ОТЧЁТА — через
 * немой канал {@link CurrentReport#check}/{@link CurrentReport#assertStep} (не инструментируется).
 */
@SpringBootTest(classes = JpaTestApp.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Epic("allure-spring-test")
@Feature("База данных (JPA)")
class DataJpaReportIT {

    @Autowired
    private WidgetRepository widgets;

    @Autowired
    private OwnerRepository owners;

    @Autowired
    private WidgetService service;

    @Test
    @DisplayName("findAll(Pageable): в «DB Result» видны сущности страницы, а не toString PageImpl")
    void pagedFindAllShowsEntities() {
        widgets.save(new Widget("paged"));
        widgets.findAll(org.springframework.data.domain.PageRequest.of(0, 1));

        // Page — не Collection, поэтому без отдельной ветки уходит в toString:
        // «Page 1 of N containing … instances» вместо самих сущностей.
        // Имя шага и вложения при этом не менялись — инвентарь такую деградацию не видит by design.
        String dbResult = CurrentReport.attachmentOfStep("DB WidgetRepository.findAll", "DB Result").orElse("");
        CurrentReport.check(dbResult.contains("Widget{"),
                () -> "DB Result без разбора сущностей страницы: " + dbResult);
        CurrentReport.check(!dbResult.contains("containing"),
                () -> "в DB Result просочился toString PageImpl: " + dbResult);
    }

    @Test
    @DisplayName("ленивая связь показана МАРКЕРОМ — прокси не разбужен ради отчёта")
    void lazyAssociationIsNotWokenUp() {
        Widget widget = new Widget("lazy-owner");
        widget.setOwner(owners.save(new Owner("ACME")));
        Widget saved = widgets.save(widget);

        // findById — ОТДЕЛЬНАЯ транзакция: сессия сохранения уже закрыта, поэтому owner
        // приезжает НЕинициализированным прокси. В одной сессии он был бы обычным объектом
        // из кэша первого уровня, и витрина проверяла бы не тот случай.
        widgets.findById(saved.getId());

        // Отчёт не имеет права менять поведение приложения. При ОТКРЫТОЙ сессии обращение
        // к неинициализированному прокси не бросает исключение, а молча идёт в БД: лишний
        // SELECT на каждую связь и N+1 на коллекции у потребителя. Найдено A/B-проверкой
        // на чужом сервисе (docs/consumer-affects.md, находка №1).
        String dbResult = CurrentReport.attachmentOfStep("DB WidgetRepository.findById", "DB Result").orElse("");
        CurrentReport.check(dbResult.contains("owner=<не загружено: ленивая связь>"),
                () -> "ленивая связь не помечена маркером — значит её разбудили: " + dbResult);
    }

    @Test
    @DisplayName("при ОТКРЫТОЙ транзакции ленивая связь не даёт лишнего SELECT'а")
    void lazyAssociationCostsNoExtraQueryInsideTransaction() {
        Widget widget = new Widget("lazy-in-tx");
        widget.setOwner(owners.save(new Owner("ACME-tx")));
        Widget saved = widgets.save(widget);

        service.loadWithinTransaction(saved.getId());

        // Самый опасный случай: сессия ОТКРЫТА, поэтому обращение к прокси не бросает,
        // а молча идёт в БД. Ловим это не по маркеру, а по ОТСУТСТВИЮ запроса —
        // datasource-proxy показал бы «SQL SELECT owner» отдельным шагом.
        List<String> steps = CurrentReport.stepNames();
        CurrentReport.check(steps.stream().noneMatch(n -> n.startsWith("SQL SELECT") && n.contains("owner")),
                () -> "ленивую связь загрузили ради отчёта — лишний запрос в БД: " + steps);
    }

    @Test
    @DisplayName("save/findById/findAll репозитория попадают в отчёт шагами «DB …»")
    void savesAndReadsWidget() {
        Widget saved = widgets.save(new Widget("gadget"));
        assertThat(saved.getId()).isNotNull();
        Optional<Widget> found = widgets.findById(saved.getId());
        assertThat(found).get().extracting(Widget::getName).isEqualTo("gadget");
        // contains, не containsExactly: тест проверяет СВОИ данные, а не «в базе только это» —
        // не зависит от порядка и от строк, оставленных другими тестами в общей H2
        assertThat(widgets.findAll()).extracting(Widget::getName).contains("gadget");
        assertThat(widgets.findById(999_999L)).isEmpty();

        List<String> steps = CurrentReport.stepNames();
        CurrentReport.check(steps.stream().anyMatch(n -> n.startsWith("DB ") && n.contains("WidgetRepository.save")),
                () -> "" + steps);
        CurrentReport.check(steps.stream().anyMatch(n -> n.startsWith("DB ") && n.contains("WidgetRepository.findById")),
                () -> "" + steps);
        CurrentReport.check(steps.stream().anyMatch(n -> n.startsWith("DB ") && n.contains("WidgetRepository.findAll")),
                () -> "" + steps);
        // содержимое вложений (что ушло в БД / что вернулось) через реальную цепочку
        CurrentReport.check(CurrentReport.attachmentContent("DB Result").orElse("").contains("gadget"),
                () -> "DB Result без сущности: " + CurrentReport.attachmentContent("DB Result"));

        // datasource-proxy (отдельный путь регистрации, оборачивает DataSource) — ловит РЕАЛЬНЫЙ SQL.
        // Без этого ассерта поломка регистрации ProxyDataSource в реальном контексте прошла бы мимо B.
        CurrentReport.check(steps.stream().anyMatch(n -> n.startsWith("SQL INSERT") && n.contains("widget")),
                () -> "нет шага SQL INSERT widget: " + steps);
        CurrentReport.check(steps.stream().anyMatch(n -> n.startsWith("SQL SELECT")),
                () -> "нет шага SQL SELECT (findById/findAll): " + steps);
        CurrentReport.check(CurrentReport.attachmentContent("SQL Query").orElse("").toLowerCase().contains("widget"),
                () -> "SQL Query без текста запроса: " + CurrentReport.attachmentContent("SQL Query"));
        // значение параметра ПОДСТАВЛЕНО в текст (а не голый ?) — иначе ручному приёмщику непонятно, что записали
        CurrentReport.check(CurrentReport.attachmentContent("SQL Query").orElse("").contains("'gadget'"),
                () -> "SQL Query без подставленного значения параметра: " + CurrentReport.attachmentContent("SQL Query"));
    }

    @Test
    @DisplayName("UPDATE и DELETE тоже попадают в отчёт")
    void updateAndDeleteAreLogged() {
        Widget saved = widgets.save(new Widget("old"));
        saved.setName("new");
        widgets.save(saved);
        widgets.deleteById(saved.getId());
        assertThat(widgets.findById(saved.getId())).isEmpty();

        List<String> steps = CurrentReport.stepNames();
        CurrentReport.check(steps.stream().anyMatch(n -> n.contains("WidgetRepository.deleteById")), () -> "" + steps);
        // SQL UPDATE и DELETE должны различаться в дереве (а не оба выглядеть как INSERT)
        CurrentReport.check(steps.stream().anyMatch(n -> n.startsWith("SQL UPDATE") && n.contains("widget")),
                () -> "нет шага SQL UPDATE widget: " + steps);
        CurrentReport.check(steps.stream().anyMatch(n -> n.startsWith("SQL DELETE") && n.contains("widget")),
                () -> "нет шага SQL DELETE widget: " + steps);
    }

    @Test
    @DisplayName("ошибка репозитория видна шагом (BROKEN), исключение проброшено")
    void repositoryErrorIsVisibleAsBrokenStep() {
        assertThatThrownBy(() -> widgets.findById(null)).isInstanceOf(RuntimeException.class);

        CurrentReport.check(CurrentReport.steps().stream().anyMatch(s ->
                        s.getName().contains("WidgetRepository.findById") && s.getStatus() == Status.BROKEN),
                () -> "нет BROKEN-шага findById: " + CurrentReport.stepNames());
        // «DB Call» (что ушло в БД) есть, «DB Result» при ошибке НЕ пишем
        CurrentReport.check(CurrentReport.attachmentNames().contains("DB Call"), () -> "нет DB Call");
        CurrentReport.check(!CurrentReport.attachmentNames().contains("DB Result"), () -> "DB Result не должен писаться при ошибке");
    }
}
