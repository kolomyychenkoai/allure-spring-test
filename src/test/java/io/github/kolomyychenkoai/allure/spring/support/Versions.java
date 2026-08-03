package io.github.kolomyychenkoai.allure.spring.support;

/**
 * Сравнение версий для гейта объявленных границ совместимости
 * ({@code canary/AllureApiCanaryTest#versionWithinDeclaredRange}).
 * <p>
 * Вынесено отдельно, чтобы иметь СВОЙ тест ({@code unit/VersionCompareTest}): сломанное
 * сравнение не падает — оно всегда отвечает «в пределах», и гейт границ становится
 * вечно-зелёным. Детектор, который не умеет краснеть, хуже отсутствующего.
 */
public final class Versions {

    private Versions() {
    }

    /** Посегментное сравнение: {@code 2.10.0 > 2.9.0} (лексикографическое сказало бы обратное). */
    public static int compare(String left, String right) {
        String[] a = left.split("[.\\-+]");
        String[] b = right.split("[.\\-+]");
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int x = segment(a, i);
            int y = segment(b, i);
            if (x != y) {
                return Integer.compare(x, y);
            }
        }
        return 0;
    }

    /** Нечисловой сегмент (BETA, RC1) считаем нулём: предрелиз ниже релиза, этого достаточно. */
    private static int segment(String[] parts, int index) {
        if (index >= parts.length) {
            return 0;
        }
        try {
            return Integer.parseInt(parts[index]);
        } catch (NumberFormatException notANumber) {
            return 0;
        }
    }
}
