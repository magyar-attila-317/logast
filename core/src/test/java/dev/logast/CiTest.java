package dev.logast;

import dev.logast.orders.Baseline;
import dev.logast.orders.ScanResult;
import dev.logast.orders.Sarif;
import dev.logast.orders.WorkOrder;
import dev.logast.rules.Rules;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Method;
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
