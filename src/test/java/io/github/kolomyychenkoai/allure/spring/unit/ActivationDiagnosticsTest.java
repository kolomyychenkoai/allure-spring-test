package io.github.kolomyychenkoai.allure.spring.unit;

import io.github.kolomyychenkoai.allure.spring.internal.ActivationDiagnostics;
import io.qameta.allure.Epic;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Правило жалобы намеренно УЗКОЕ: «фича есть, крючка нет». Если жаловаться шире, WARNING
 * появятся в каждой сборке и их перестанут читать — а тогда сигнал мёртв.
 */
@Epic("Внутренние проверки библиотеки")
class ActivationDiagnosticsTest {

    private static final String MOCK_MVC = "org.springframework.test.web.servlet.MockMvc";
    private static final String HOOK_BOOT3 =
            "org.springframework.boot.test.autoconfigure.web.servlet.MockMvcBuilderCustomizer";
    private static final String HOOK_BOOT4 =
            "org.springframework.boot.webmvc.test.autoconfigure.MockMvcBuilderCustomizer";

    @Test
    @DisplayName("MockMvc есть, крючка нет, байткод мёртв → жалоба говорит «шаги НЕ ПОПАДУТ»")
    void loudWhenBothChannelsDead() {
        List<String> problems = ActivationDiagnostics.problems(Set.of(MOCK_MVC)::contains, false);

        assertThat(problems).singleElement().asString()
                .contains("НЕ ПОПАДУТ").contains("spring-boot-webmvc-test");
    }

    @Test
    @DisplayName("MockMvc есть, крючка нет, но байткод жив → жалоба мягче")
    void softerWhenBytecodeAlive() {
        assertThat(ActivationDiagnostics.problems(Set.of(MOCK_MVC)::contains, true))
                .singleElement().asString().contains("Пока держит байткод-перехват");
    }

    @Test
    @DisplayName("крючок под НОВЫМ именем (Boot 4) — жалоб нет")
    void quietOnBoot4Names() {
        assertThat(ActivationDiagnostics.problems(Set.of(MOCK_MVC, HOOK_BOOT4)::contains, true)).isEmpty();
        assertThat(ActivationDiagnostics.problems(Set.of(MOCK_MVC, HOOK_BOOT3)::contains, true)).isEmpty();
    }

    @Test
    @DisplayName("фичи нет вовсе (Kafka-only потребитель) → молчим, не шумим в каждой сборке")
    void quietWhenFeatureAbsent() {
        assertThat(ActivationDiagnostics.problems(name -> false, true)).isEmpty();
    }

    @Test
    @DisplayName("WebTestClient без крючка — жалоба ЖЁСТКАЯ: у него байткод-фолбэка нет")
    void webTestClientHasNoFallback() {
        List<String> problems = ActivationDiagnostics.problems(
                Set.of("org.springframework.test.web.reactive.server.WebTestClient")::contains, true);

        assertThat(problems).singleElement().asString()
                .contains("НЕ ПОПАДУТ").contains("spring-boot-webtestclient");
    }

    @Test
    @DisplayName("byte-buddy не знает формат классов JVM → жалоба про МОЛЧА выключенный перехват")
    void warnsOnTooOldByteBuddy() {
        // Сценарий «Boot 3.4 на Java 25»: комбинация выглядит рабочей, но весь байткод-слой мёртв.
        // Это и есть та поломка, ради которой в README объявлен минимум Boot 3.5 для Java 25.
        List<String> problems = ActivationDiagnostics.problems(
                name -> false, true, true, "1.15.11");

        assertThat(problems).singleElement().asString()
                .contains("byte-buddy 1.15.11")
                .contains("МОЛЧА выключен")
                .contains("Kafka");
    }

    @Test
    @DisplayName("byte-buddy нет вовсе → про формат классов не жалуемся (нечему быть старым)")
    void quietAboutFormatWhenByteBuddyAbsent() {
        assertThat(ActivationDiagnostics.problems(name -> false, false, true, "1.15.11")).isEmpty();
    }

    @Test
    @DisplayName("на нашем classpath (всё на месте) жалоб нет — гейт заводится в зелёном")
    void quietOnCurrentClasspath() {
        assertThat(ActivationDiagnostics.problems(
                name -> {
                    try {
                        Class.forName(name, false, getClass().getClassLoader());
                        return true;
                    } catch (ClassNotFoundException absent) {
                        return false;
                    }
                }, true)).isEmpty();
    }

}
