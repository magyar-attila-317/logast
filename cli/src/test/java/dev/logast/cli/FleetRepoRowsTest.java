package dev.logast.cli;

import dev.logast.fleet.FleetRepos;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** #216: a repo of repos.yaml names the seeded tables whose rows become facts; only its own export reads them. */
class FleetRepoRowsTest {
    @TempDir
    Path tmp;

    @Test
    void aRepoAsksForItsOwnSeededRows() throws IOException {
        Files.writeString(tmp.resolve(FleetRepos.FILE), """
                fleet: shop
                repos:
                  - https://github.com/org/orders.git
                  - url: https://github.com/org/scheduler.git
                    rows: [scheduling.pipeline, scheduling.step]
                  - url: https://github.com/org/billing.git
                    rows: billing.plan
                """);
        assertThat(FleetRepos.list(tmp)).extracting(r -> r.name() + " " + r.rows()).containsExactly(
                "orders []", "scheduler [scheduling.pipeline, scheduling.step]", "billing [billing.plan]");

        Fleet f = new Fleet();
        f.repoRows = Map.of("scheduler", List.of("scheduling.pipeline"));
        assertThat(f.flywayOptions("scheduler")).as("its rows run its migrations without --flyway").isNotNull()
                .satisfies(o -> assertThat(o.rows()).containsExactly("scheduling.pipeline"));
        assertThat(f.flywayOptions("orders")).as("the others: as the command line says (no --flyway: none)").isNull();
        f.flyway = true;
        f.rows = List.of("shared.lookup");
        assertThat(f.flywayOptions("scheduler").rows()).containsExactly("shared.lookup", "scheduling.pipeline");
        assertThat(f.flywayOptions("orders").rows()).containsExactly("shared.lookup");
    }
}
