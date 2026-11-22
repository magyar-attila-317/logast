package dev.logast;

import dev.logast.orders.Baseline;
import dev.logast.orders.ScanResult;
import dev.logast.orders.Sarif;
import dev.logast.orders.WorkOrder;
import dev.logast.policy.Policy;
import dev.logast.rules.Rules;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CiTest {
    @TempDir
    Path tmp;

    @Test
    void baselineHidesKnownAndReportsFixed() {
        ScanResult r = Rules.scan(Fixtures.analysis());
        Path file = tmp.resolve("baseline.json");
        Baseline.of(r).write(file);
        Baseline b = Baseline.read(file);
        assertThat(b.newOnly(r.orders())).isEmpty();
        List<WorkOrder> fewer = r.orders().subList(1, r.orders().size());
        assertThat(b.fixed(fewer)).containsExactly(r.orders().getFirst().id());
    }

    /**
     * An aggregated finding keeps one id however many places it covers (SCHEDULED_WITHOUT_LOCK lists every unlocked job
     * under "scheduled-lock"): a second unlocked job added after the baseline must still count as new (#55).
     */
    @Test
    void baselineSeesAnAggregatedFindingGrow() throws IOException {
        Path app = tmp.resolve("app");
        Path pkg = app.resolve("src/main/java/demo");
        Files.createDirectories(pkg);
        Files.writeString(app.resolve("logast.yaml"), "packs: [resilience]\n");
        Files.writeString(pkg.resolve("Jobs.java"), jobs("nightly"));
        ScanResult before = scan(app);
        assertThat(before.orders()).anySatisfy(o -> assertThat(o.rule()).isEqualTo("SCHEDULED_WITHOUT_LOCK"));
        Path file = tmp.resolve("baseline.json");
        Baseline.of(before).write(file);
        Baseline b = Baseline.read(file);

        Files.writeString(pkg.resolve("Jobs.java"), "// moved down\n\n" + jobs("nightly")); // same finding, other line
        assertThat(b.newOnly(scan(app).orders())).isEmpty();

        Files.writeString(pkg.resolve("Jobs.java"), jobs("nightly", "hourly"));
        assertThat(b.newOnly(scan(app).orders())).extracting(WorkOrder::rule).contains("SCHEDULED_WITHOUT_LOCK");

        Files.writeString(pkg.resolve("Jobs.java"), jobs("nightly"));
        Files.writeString(pkg.resolve("MoreJobs.java"), jobs("weekly").replace("class Jobs", "class MoreJobs"));
        assertThat(b.newOnly(scan(app).orders())).extracting(WorkOrder::rule).contains("SCHEDULED_WITHOUT_LOCK");
    }

    private static String jobs(String... names) {
        StringBuilder sb = new StringBuilder("""
                package demo;

                import org.springframework.scheduling.annotation.Scheduled;
                import org.springframework.stereotype.Component;

                @Component
                class Jobs {
                """);
        for (String n : names) {
            sb.append("    @Scheduled(fixedRate = 60000)\n    void ").append(n).append("() {\n    }\n\n");
        }
        return sb.append("}\n").toString();
    }

    private static ScanResult scan(Path app) throws IOException {
        return Rules.scan(Analysis.run(app, Fixtures.classpath(), Policy.resolve(app, null)));
    }

    @Test
    void gitDiffSelectsChangedLines() throws Exception {
        String diff = """
                diff --git a/svc/src/main/java/A.java b/svc/src/main/java/A.java
                +++ b/svc/src/main/java/A.java
                @@ -10,0 +11,2 @@ class A {
                +  x
                +  y
                +++ b/other/B.java
                @@ -1 +1 @@
                """;
        Method parse = dev.logast.orders.GitDiff.class.getDeclaredMethod("parse", String.class, String.class);
        parse.setAccessible(true);
        var d = (dev.logast.orders.GitDiff) parse.invoke(null, diff, "svc/");
        assertThat(d.touches("src/main/java/A.java", 11)).isTrue();
        assertThat(d.touches("src/main/java/A.java", 12)).isTrue();
        assertThat(d.touches("src/main/java/A.java", 13)).isFalse();
        assertThat(d.touches("other/B.java", 1)).isFalse(); // outside the scanned root
    }

    @Test
    @SuppressWarnings("unchecked")
    void sarifHasRulesResultsAndStableFingerprints() {
        ScanResult r = Rules.scan(Fixtures.analysis());
        Map<String, Object> sarif = Sarif.of(r.orders());
        Map<String, Object> run = ((List<Map<String, Object>>) sarif.get("runs")).getFirst();
        List<Map<String, Object>> results = (List<Map<String, Object>>) run.get("results");
        assertThat(results).hasSize(r.orders().size());
        assertThat(results).allSatisfy(res -> assertThat((Map<String, Object>) res.get("partialFingerprints")).containsKey("logastOrderId"));
        assertThat(new HashMap<>(sarif)).containsEntry("version", "2.1.0");
    }
}
