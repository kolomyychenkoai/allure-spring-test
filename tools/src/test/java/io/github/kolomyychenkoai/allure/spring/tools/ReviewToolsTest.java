package io.github.kolomyychenkoai.allure.spring.tools;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Вход в инструменты: выбор подкоманды и договор о кодах возврата. */
class ReviewToolsTest {

    @Test
    @DisplayName("без аргументов — код 2 и список подкоманд")
    void usageWithoutArguments() {
        ToolRun run = ToolRun.of();

        assertThat(run.code()).isEqualTo(2);
        assertThat(run.err()).contains("report-tree").contains("snapshot")
                .contains("attribution").contains("now");
    }

    @Test
    @DisplayName("неизвестная подкоманда названа по имени, а не просто отвергнута")
    void unknownSubcommand() {
        ToolRun run = ToolRun.of("чепуха");

        assertThat(run.code()).isEqualTo(2);
        assertThat(run.err()).contains("неизвестная подкоманда: чепуха");
    }

    @Test
    @DisplayName("now печатает метку времени в миллисекундах")
    void nowPrintsMilliseconds() {
        long before = System.currentTimeMillis();
        ToolRun run = ToolRun.of("now");
        long after = System.currentTimeMillis();

        assertThat(run.code()).isZero();
        // Замер накладных расходов вычитает две такие метки, поэтому важно и то, что это
        // именно миллисекунды, и то, что число не мусорное.
        assertThat(Long.parseLong(run.out().strip())).isBetween(before, after);
    }
}
