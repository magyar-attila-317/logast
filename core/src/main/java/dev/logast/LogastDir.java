package dev.logast;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.logast.apply.DecisionYaml;
import dev.logast.orders.OrderJson;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * The layout of {@code <root>/.logast} (#108): {@code decisions/} holds what a person edits (YAML, in git),
 * {@code reports/} what logast writes for people to read; machine input (classpath-*.txt), the CI baseline and the
 * README stay at the top. {@link #migrate} moves an earlier layout into this one.
 */
public final class LogastDir {
    public static final String DIR = ".logast";
    public static final String DECISIONS = DIR + "/decisions";
    public static final String REPORTS = DIR + "/reports";

    public static final String MESSAGES = DECISIONS + "/messages.yaml";
    public static final String METRICS = DECISIONS + "/metrics.yaml";
    public static final String RETRY = DECISIONS + "/retry.yaml";
    public static final String TRAFFIC = DECISIONS + "/traffic.yaml";

    public static final String ORDERS = REPORTS + "/orders.json";
    public static final String COVERAGE = REPORTS + "/coverage.txt";
    public static final String APPLY_TRACE = REPORTS + "/apply-trace.txt";
    public static final String LAST_APPLY = REPORTS + "/last-apply.txt";
    public static final String STEPS = REPORTS + "/steps";
    public static final String QUERIES = REPORTS + "/queries.md";
    /** #125: written by every scan and apply, printed by {@code logast explain metrics|lifecycle}. */
    public static final String METRICS_REPORT = REPORTS + "/metrics.md";
    public static final String LIFECYCLE = REPORTS + "/lifecycle.txt";
    public static final String VERIFY = REPORTS + "/verify.json";

    public static final String BASELINE = DIR + "/baseline.json";
    /** #131: machine state, the content (SHA-256) of each file apply wrote, so its own changes don't block the next apply. */
    public static final String APPLIED_STATE = DIR + "/applied-state.txt";
    public static final String README = DIR + "/README.md";

    /** Reports of the earlier layout, at the top of .logast, that now live in reports/. */
    private static final List<String> OLD_REPORTS = List.of("orders.json", "coverage.txt", "apply-trace.txt", "last-apply.txt",
            "steps", "queries.md", "verify.json");

    private LogastDir() {
    }

    /**
     * #132: {@code path} (one of the constants above, under {@value #DIR}/) in {@code out}, a folder laid out like
     * {@code <root>/.logast}: a fleet's added member writes its classpath and reports to {@code <fleet>/services/<service>/}.
     */
    public static Path in(Path out, String path) {
        if (!path.startsWith(DIR + "/")) {
            throw new IllegalArgumentException(path + " is not under " + DIR);
        }
        return out.resolve(path.substring(DIR.length() + 1));
    }

    /**
     * What {@link #migrate} did.
     *
     * @param moved    "fills.json → decisions/messages.yaml", one per decision file, then "reports/ (…)" for the reports
     * @param warnings old files left in place because the new one exists already
     */
    public record Migration(List<String> moved, List<String> warnings) {
        public boolean isEmpty() {
            return moved.isEmpty() && warnings.isEmpty();
        }

        /** The one line a command prints, or null when nothing moved. */
        public String line() {
            return moved.isEmpty() ? null : "moved to the new .logast layout: " + String.join(", ", moved);
        }
    }

    /**
     * Moves an earlier layout into this one: fills.json and metrics.json become decisions/messages.yaml and
     * decisions/metrics.yaml (same entries, YAML), retry.yaml and traffic.yaml|yml|json move to decisions/ (traffic
     * as YAML), the reports to reports/. A decision file whose new place is taken is left where it is, with a warning:
     * nothing a person wrote is lost. A report whose new place is taken is stale and removed. The README and a
     * .gitignore logast set up are brought up to date when something moved.
     */
    public static Migration migrate(Path root) {
        Path dir = root.resolve(DIR);
        List<String> moved = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return new Migration(moved, warnings);
        }
        try {
            decision(root, "fills.json", MESSAGES, true, moved, warnings);
            decision(root, "metrics.json", METRICS, true, moved, warnings);
            decision(root, "retry.yaml", RETRY, false, moved, warnings);
            for (String old : List.of("traffic.yaml", "traffic.yml", "traffic.json")) {
                decision(root, old, TRAFFIC, old.endsWith(".json"), moved, warnings);
            }
            List<String> reports = new ArrayList<>();
            for (String name : OLD_REPORTS) {
                Path old = dir.resolve(name);
                if (!Files.exists(old)) {
                    continue;
                }
                Path target = root.resolve(REPORTS).resolve(name);
                if (Files.exists(target)) {
                    delete(old);   // logast wrote both; the one in reports/ is the newer
                } else {
                    Files.createDirectories(target.getParent());
                    Files.move(old, target);
                    reports.add(name);
                }
            }
            if (!reports.isEmpty()) {
                moved.add(String.join(", ", reports) + " → reports/");
            }
            if (!moved.isEmpty()) {
                Path readme = root.resolve(README);
                if (Files.isRegularFile(readme)) {
                    Files.writeString(readme, ServiceSetup.logastReadme(), StandardCharsets.UTF_8);
                }
                Path gitignore = root.resolve(".gitignore");
                if (Files.isRegularFile(gitignore) && Files.readAllLines(gitignore).stream().anyMatch(l -> l.strip().equals(".logast/*"))) {
                    ServiceSetup.updateGitignore(root);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot move .logast to its new layout: " + e.getMessage(), e);
        }
        return new Migration(moved, warnings);
    }

    /** One decision file: moved (or converted from JSON to YAML) when its new place is free, else left with a warning. */
    private static void decision(Path root, String oldName, String target, boolean fromJson, List<String> moved, List<String> warnings)
            throws IOException {
        Path old = root.resolve(DIR).resolve(oldName);
        if (!Files.isRegularFile(old)) {
            return;
        }
        Path to = root.resolve(target);
        String newName = target.substring(DIR.length() + 1);
        if (Files.exists(to)) {
            warnings.add(".logast/" + oldName + " is left in place: " + target + " exists already and is the one logast reads; "
                    + "move what you still need from the old file by hand, then delete it");
            return;
        }
        Files.createDirectories(to.getParent());
        if (fromJson) {
            JsonNode tree = OrderJson.MAPPER.readTree(old.toFile());
            // the earlier "_readme" / "_status" are the header comment now, written by the next scan or apply
            Files.writeString(to, DecisionYaml.text("", tree instanceof ObjectNode o && !oldName.startsWith("traffic")
                    ? DecisionYaml.withoutNotes(o) : tree), StandardCharsets.UTF_8);
            Files.delete(old);
        } else {
            Files.move(old, to);
        }
        moved.add(oldName + " → " + newName);
    }

    private static void delete(Path p) throws IOException {
        if (Files.isDirectory(p)) {
            try (Stream<Path> s = Files.walk(p)) {
                for (Path f : s.sorted(Comparator.reverseOrder()).toList()) {
                    Files.delete(f);
                }
            }
        } else {
            Files.deleteIfExists(p);
        }
    }
}
