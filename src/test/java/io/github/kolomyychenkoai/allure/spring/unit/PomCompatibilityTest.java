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
    @DisplayName("maven.compiler.release=21 — объявленный минимум Java под стражем")
    void compilerReleaseMatchesDeclaredMinimum() throws IOException {
        // README объявляет минимумом Java 21, и это единственная граница совместимости,
        // у которой не было стража: остальные (compat.*) сверяет тест выше, а release мог
        // уехать на 25 незаметно — и молча поднять пол ВСЕМ потребителям. Компилятор об этом
        // не скажет: сборка станет только зеленее.
        assertThat(outsideProfiles(pom()))
                .as("release задаёт нижнюю границу Java для потребителя — меняется вместе с "
                        + "таблицей поддерживаемых версий в README, а не отдельно")
                .contains("<maven.compiler.release>21</maven.compiler.release>");
    }

    @Test
    @DisplayName("профиль java25 доказывает, на какой JVM он шёл (иначе граница держится на пути)")
    void java25ProfileDeclaresExpectedJvm() throws IOException {
        // Если java25.home указывает на другой JDK, профиль зеленеет НИЧЕГО не проверив,
        // а строка «проверено до 25» в README врёт. Свойство едет в тест, канарейка сверяет
        // его с Runtime.version() — тем же приёмом, что и границы Allure.
        String pom = pom();
        int profile = pom.indexOf("<id>java25</id>");
        assertThat(profile).as("профиль java25 должен существовать").isNotNegative();
        assertThat(pom.substring(profile, Math.min(pom.length(), profile + 2000)))
                .as("профиль обязан объявлять ожидаемую версию JVM, иначе он ничего не доказывает")
                .contains("<expected.java.feature>25</expected.java.feature>");
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
}
