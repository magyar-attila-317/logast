package dev.logast;

import dev.logast.fleet.FleetReport;
import dev.logast.orders.ScanResult;
import dev.logast.rules.Rules;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FleetTest {
    @TempDir
    Path out;

    @Test
    void scoresServicesAndShowsTrendOnSecondRun() throws Exception {
        ScanResult r = Rules.scan(Fixtures.analysis());
        FleetReport.Service s = FleetReport.service("sample-app", r, List.of("x overrides y"), List.of("logging", "tracing"));
        assertThat(s.score()).isBetween(0.0, 100.0);
        assertThat(s.packScore()).containsOnlyKeys("logging", "tracing"); // metrics not selected: no score
        FleetReport.write(out, List.of(s));
        Thread.sleep(5);
        Path html = FleetReport.write(out, List.of(s));
        String page = Files.readString(html);
        assertThat(page).contains("<title>Logging Fleet Report</title>", "sample-app", "±0", "SWALLOWED_EXCEPTION");
        try (var files = Files.list(out)) {
            assertThat(files.filter(p -> p.getFileName().toString().startsWith("fleet-"))).hasSize(2);
        }
    }
}
