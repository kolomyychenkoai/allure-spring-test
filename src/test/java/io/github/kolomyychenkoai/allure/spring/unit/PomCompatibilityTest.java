package io.github.kolomyychenkoai.allure.spring.unit;

import io.qameta.allure.Epic;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Страж договорённостей, которые живут в {@code pom.xml} и которые нечем проверить изнутри JVM.
 * <p>
 * Читается НАСТОЯЩИЙ pom, а не его модель: смысл ровно в том, чтобы правка файла руками
 * не смогла тихо разойтись с тем, что мы обещаем в README и чем закрываем детекторы.
 */
@Epic("Внутренние проверки библиотеки")
class PomCompatibilityTest {

    private static final Path POM = Path.of("pom.xml");

    private static String pom() throws IOException {
        return Files.readString(POM, StandardCharsets.UTF_8);
    }

    /** Кусок pom ДО секции {@code <profiles>} — то есть проектные настройки, а не профильные. */
    private static String outsideProfiles(String pom) {
        int start = pom.indexOf("<profiles>");
        return start < 0 ? pom : pom.substring(0, start);
    }

    @Test
    @DisplayName("профиль второго прогона на месте — иначе ВСЯ вторая половина сетки молчит")
    void inventoryProfileIsWiredUp() throws IOException {
        // Второй прогон surefire несёт самые сильные гейты проекта: сверку с эталоном, гигиену
        // имён и ТЕЛ вложений, InstrumentationFailures, кратность, форму. Всё это выполняется
        // только если профиль активировался — и всё это молча НЕ выполняется, если профиль
        // сломать, удалить или просто гонять `mvn test -Dtest=…`. Сборка при этом зелёная.
        //
        // Проверка статическая: «прогон реально был» изнутри JVM не узнать (инвентарь живёт
        // в ДРУГОЙ JVM и стартует после). Зато она ловит самый вероятный регресс — правку pom.
        String pom = pom();
        int at = pom.indexOf("<id>report-inventory</id>");
        assertThat(at)
                .as("профиль report-inventory исчез — вместе с ним пропали сверка с эталоном, "
                        + "гигиена имён и тел, гейт сбоев перехвата, кратность и форма")
                .isNotNegative();

        String profile = pom.substring(at, Math.min(pom.length(), at + 2000));
        assertThat(profile)
                .as("активация по отсутствию `test`: иначе точечный `-Dtest=…` гонял бы инвентарь "
                        + "на неполных данных, а полный прогон — дважды")
                .contains("<name>!test</name>");
        assertThat(profile)
                .as("execution обязан включать сам детектор — без include профиль пустой")
                .contains("ReportInventoryCheck.java");
        assertThat(profile)
                .as("детектор должен исполняться в фазе test, иначе результатов ещё нет")
                .contains("<phase>test</phase>");
    }

    @Test
    @DisplayName("inventory.compare=off допустим ТОЛЬКО внутри compat-профилей")
    void compareOffOnlyInsideCompatProfiles() throws IOException {
        // Единственный флаг проекта, делающий детектор зеленее. Остальные (update/remove) всегда
        // роняют сборку, и защищать их не нужно — этот же, забытый включённым, превращает
        // инвентарь (143 вида шагов + 87 вложений) в вечно-зелёный. Внешний страж обязателен,
        // потому что изнутри самого детектора «меня выключили» не проверить.
        String pom = pom();
        assertThat(outsideProfiles(pom))
                .as("в проектных <properties> сверка обязана быть включена")
                .contains("<inventory.compare>on</inventory.compare>")
                .doesNotContain("<inventory.compare>off</inventory.compare>");

        // ...а внутри профилей — только у тех, чей id начинается с compat-
        String profiles = pom.substring(Math.max(0, pom.indexOf("<profiles>")));
        Matcher profile = Pattern.compile("<profile>(.*?)</profile>", Pattern.DOTALL).matcher(profiles);
        while (profile.find()) {
            String body = profile.group(1);
            if (!body.contains("<inventory.compare>off</inventory.compare>")) {
                continue;
            }
            Matcher id = Pattern.compile("<id>([^<]+)</id>").matcher(body);
            assertThat(id.find()).as("у профиля с inventory.compare=off должен быть id").isTrue();
            assertThat(id.group(1))
                    .as("выключать сверку с эталоном может только профиль матрицы совместимости")
                    .startsWith("compat-");
        }
    }

    @Test
    @DisplayName("границы совместимости объявлены и не пусты (их читает канарейка Allure)")
    void compatibilityBoundsDeclared() throws IOException {
        String pom = outsideProfiles(pom());
        for (String property : new String[]{"compat.allure.min", "compat.allure.max", "compat.boot.min"}) {
            assertThat(pom)
                    .as("граница %s должна быть объявлена в <properties>: это единственный источник "
                            + "правды для README и AllureApiCanaryTest", property)
                    .containsPattern("<" + Pattern.quote(property) + ">\\s*\\d+\\.\\d+");
        }
        // Границы обязаны доезжать до тестов, иначе канарейка их не увидит и молча пропустит гейт
        assertThat(pom).contains("<allure.compat.min>${compat.allure.min}</allure.compat.min>");
        assertThat(pom).contains("<allure.compat.max>${compat.allure.max}</allure.compat.max>");
    }

    @Test
    @DisplayName("maven.compiler.release=25 — объявленный минимум Java под стражем")
    void compilerReleaseMatchesDeclaredMinimum() throws IOException {
        // README объявляет минимумом Java 25, и это единственная граница совместимости,
        // у которой не было стража: остальные (compat.*) сверяет тест выше, а release мог
        // уехать незаметно — и молча поднять или опустить пол ВСЕМ потребителям. Компилятор
        // об этом не скажет: сборка станет только зеленее.
        assertThat(outsideProfiles(pom()))
                .as("release задаёт нижнюю границу Java для потребителя — меняется вместе с "
                        + "таблицей поддерживаемых версий в README, а не отдельно")
                .contains("<maven.compiler.release>25</maven.compiler.release>");
    }

    @Test
    @DisplayName("прогон обязан объявлять версию JVM — иначе «собрано под 25» держится на честном слове")
    void runDeclaresExpectedJvm() throws IOException {
        // Раньше это жило в профиле java25, который форкал surefire на другой JDK. Профиля больше
        // нет: Java 25 — условие сборки, а не точка матрицы. Но сама проверка нужнее прежнего.
        // Собраться под release=25 и ПРОГНАТЬСЯ на другой JVM технически можно (JAVA_HOME у maven
        // и <jvm> у surefire — разные вещи), и тогда «проверено на 25» было бы неправдой.
        // Свойство едет в canary/InstrumentationApiCanaryTest, тот сверяет с Runtime.version().
        assertThat(outsideProfiles(pom()))
                .as("expected.java.feature обязан быть в ОБЩЕЙ конфигурации surefire: проверка "
                        + "версии JVM должна идти в каждом прогоне, а не по особому профилю")
                .contains("<expected.java.feature>25</expected.java.feature>");

        assertThat(pom())
                .as("профиль java25 удалён вместе с переходом на release=25 — если он вернулся, "
                        + "значит кто-то снова форкает тесты на чужой JDK, и это надо обсуждать")
                .doesNotContain("<id>java25</id>");
    }

    @Test
    @DisplayName("транзитивно навязываемая зависимость ровно ОДНА — allure-java-commons")
    void singleCompileScopeDependency() throws IOException {
        // Каждая compile-зависимость — это версия, которую мы навязываем чужому дереву. Сейчас
        // такая одна, и конфликт версий у потребителя разбирается одним абзацем README. Вторая
        // сделала бы эту тему нерешаемой, поэтому её появление должно быть предметом ревью,
        // а не следствием невнимательного <dependency> без <scope>. См. docs/adr/0002-*.
        // dependencyManagement вырезаем: там ПИНЫ версий, а не зависимости — у них нет <scope>
        // по определению, и без этой строки страж считал бы транзитивным каждый пин.
        String pom = pom().replaceAll("(?s)<dependencyManagement>.*?</dependencyManagement>", "");
        String dependencies = pom.substring(pom.indexOf("<dependencies>"), pom.indexOf("<build>"));
        Matcher dependency = Pattern.compile("<dependency>(.*?)</dependency>", Pattern.DOTALL)
                .matcher(dependencies);
        StringBuilder compileScoped = new StringBuilder();
        while (dependency.find()) {
            String body = dependency.group(1);
            boolean transitive = !body.contains("<scope>") && !body.contains("<optional>true</optional>");
            if (transitive) {
                Matcher artifact = Pattern.compile("<artifactId>([^<]+)</artifactId>").matcher(body);
                compileScoped.append(artifact.find() ? artifact.group(1) : "?").append(' ');
            }
        }
        assertThat(compileScoped.toString().trim())
                .as("появилась новая compile-зависимость — переведи её в provided/optional либо "
                        + "ОСОЗНАННО обнови README и ADR 0002")
                .isEqualTo("allure-java-commons");
    }

    /**
     * Список файлов, которые НЕ компилируются на нижней границе Boot. Каждая строка — это
     * дырка в доказательстве «один jar работает на 3.x и на 4.x», поэтому список зафиксирован
     * здесь: расширить его молча нельзя, только вместе с этим тестом и строкой в compat-matrix.
     * <p>
     * ⛔ Сам профиль сейчас НЕ СОБИРАЕТСЯ (зависимости Boot 4 не управляются BOM 3.2.12 —
     * см. {@code docs/compat-matrix.md}). Список держим замороженным именно поэтому: когда точку
     * будут чинить, он должен остаться ровно тем, что осознанно решили не проверять, а не
     * разрастись за время простоя.
     */
    private static final java.util.List<String> BOOT4_ONLY_TESTS = java.util.List.of(
            "AllureMockMvcAutoConfigurationTest",
            "AllureWebTestClientAutoConfigurationTest",
            "MockMvcReportIT",
            "RestTemplateReportIT",
            "WebTestClientReportIT",
            "AllureRestTemplateInstrumentationTest");

    @Test
    @DisplayName("исключения нижней границы Boot зафиксированы — список не расширить молча")
    void bootMinExclusionsAreFrozen() throws IOException {
        assertThat(bootMinExclusions())
                .as("список исключённых из нижней границы тестов изменился. Каждая строка — "
                        + "непроверенный на Boot 3 кусок: обнови BOOT4_ONLY_TESTS и docs/compat-matrix.md "
                        + "ОСОЗНАННО, а не чтобы позеленело")
                .containsExactlyInAnyOrderElementsOf(BOOT4_ONLY_TESTS);
    }

    @Test
    @DisplayName("резолв переехавших имён проверяется на ОБЕИХ границах — эти тесты не исключены")
    void movedNameResolutionStaysOnBothMajors() throws IOException {
        // Смысл MovedTypeNames в том, что имя резолвится СТРОКОЙ. Если тесты этого механизма
        // попадут в исключения, доказательство кросс-версионности исчезнет незаметно.
        // Сверяем РАЗОБРАННЫЕ записи <testExclude>, а не сырой текст профиля: имена этих тестов
        // упомянуты там же в пояснительном комментарии, и проверка по подстроке краснела бы на нём.
        assertThat(bootMinExclusions())
                .as("эти тесты держат имена СТРОКАМИ и обязаны собираться на обоих мажорах — "
                        + "исключив их, мы потеряли бы доказательство кросс-версионности")
                .doesNotContain("MovedCustomizerRegistrarTest", "ActivationDiagnosticsTest");
    }

    /** Имена тестов из {@code <testExclude>} профиля нижней границы Boot (без пути и {@code .java}). */
    private static java.util.List<String> bootMinExclusions() throws IOException {
        String pom = pom();
        int at = pom.indexOf("<id>compat-boot-min</id>");
        assertThat(at).as("профиль compat-boot-min исчез").isNotNegative();
        String profile = pom.substring(at, pom.indexOf("</profile>", at));
        Matcher exclude = Pattern.compile("<testExclude>([^<]+)</testExclude>").matcher(profile);
        java.util.List<String> names = new java.util.ArrayList<>();
        while (exclude.find()) {
            String path = exclude.group(1);
            names.add(path.substring(path.lastIndexOf('/') + 1).replace(".java", ""));
        }
        return names;
    }
}
