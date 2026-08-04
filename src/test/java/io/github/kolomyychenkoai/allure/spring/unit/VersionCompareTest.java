package io.github.kolomyychenkoai.allure.spring.unit;

import io.github.kolomyychenkoai.allure.spring.support.Versions;
import io.qameta.allure.Epic;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Сравнение версий, на котором стоит гейт объявленных границ Allure
 * ({@code canary/AllureApiCanaryTest#versionWithinDeclaredRange}).
 * <p>
 * Отдельным тестом, потому что сломанное сравнение не падает — оно ВСЕГДА возвращает «в
 * пределах», и гейт становится вечно-зелёным. Правило проекта: детектор, который не умеет
 * краснеть, хуже отсутствующего — он создаёт ложное чувство покрытия.
 */
@Epic("Внутренние проверки библиотеки")
class VersionCompareTest {

    private static int cmp(String a, String b) {
        return Versions.compare(a, b);
    }

    @Test
    @DisplayName("равные версии — ноль, в том числе при разном числе сегментов")
    void equal() {
        assertThat(cmp("2.25.0", "2.25.0")).isZero();
        assertThat(cmp("2.25", "2.25.0")).isZero();
        assertThat(cmp("2", "2.0.0")).isZero();
    }

    @Test
    @DisplayName("сравнение ПОСЕГМЕНТНО, а не лексикографически")
    void numericNotLexicographic() {
        // строковое сравнение сказало бы «2.9 > 2.10» — ровно та ошибка, которая тихо
        // пропускает слишком старый Allure
        assertThat(cmp("2.10.0", "2.9.0")).isPositive();
        assertThat(cmp("2.9.0", "2.10.0")).isNegative();
        assertThat(cmp("2.35.4", "2.35.10")).isNegative();
    }

    @Test
    @DisplayName("границы: наша версия внутри объявленного диапазона")
    void insideRange() {
        assertThat(cmp("2.25.0", "2.20.0")).isPositive();
        assertThat(cmp("2.25.0", "2.35.4")).isNegative();
    }

    @Test
    @DisplayName("предрелиз считается ниже релиза той же версии")
    void prerelease() {
        assertThat(cmp("2.0-BETA22", "2.0.0")).isZero();
        assertThat(cmp("2.0-BETA22", "2.20.0")).isNegative();
    }
}
