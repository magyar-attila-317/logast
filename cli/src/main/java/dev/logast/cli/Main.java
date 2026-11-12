package dev.logast.cli;

import com.fasterxml.jackson.core.type.TypeReference;
import dev.logast.Analysis;
import dev.logast.Explainer;
import dev.logast.Logast;
import dev.logast.apply.Applier;
import dev.logast.apply.Fill;
import dev.logast.apply.FillValidator;
import dev.logast.orders.CompactFormat;
import dev.logast.orders.OrderJson;
import dev.logast.orders.ScanResult;
import dev.logast.orders.WorkOrder;
import dev.logast.policy.Policy;
import dev.logast.rules.DomainValues;
import dev.logast.rules.Glossary;
import dev.logast.rules.Rules;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;

@Command(name = "logast", mixinStandardHelpOptions = true, versionProvider = Main.Version.class,
        description = "Uniform logging, MDC and tracing for Spring Boot services: finds gaps along call paths from "
                + "entrypoints and emits work orders an LLM completes by writing message text only.",
        subcommands = {Main.Scan.class, Main.Explain.class, Main.Apply.class, Main.Classpath.class, Main.RulesCmd.class,
                Main.PolicyCmd.class, Init.class, Verify.class, Fleet.class, Review.class, Main.TrafficCmd.class})
public final class Main {
    static final class Version implements CommandLine.IVersionProvider {
        @Override
        public String[] getVersion() throws Exception {
            java.util.Properties p = new java.util.Properties();
            try (var in = Main.class.getResourceAsStream("/logast-version.properties")) {
                if (in != null) {
                    p.load(in);
                }
            }
            return new String[]{"logast " + p.getProperty("version", "dev")};
        }
    }

    public static void main(String[] args) {
        System.exit(new CommandLine(new Main()).execute(args));
    }

    static class Common {
        @Parameters(index = "0", description = "Project root", defaultValue = ".")
        Path root;
        @Option(names = "--classpath-file", description = "Classpath file (default: <root>/.logast/classpath*.txt)")
        Path classpathFile;
        @Option(names = "--policy", description = "Policy YAML (default: <root>/logast.yaml)")
        Path policyFile;
        @Option(names = "--pack", split = ",", description = "Rule packs to run: logging, tracing, metrics (default: policy packs)")
        List<String> packs = new ArrayList<>();
        @Option(names = "--traffic", description = "Entrypoint calls/day (YAML 'entrypoints:' or a Prometheus query result; default: <root>/.logast/traffic.*)")
        Path traffic;

        Path root() {
            return root.toAbsolutePath().normalize();
        }

        Policy policy() {
            Policy p = Logast.policy(root(), policyFile);
            return packs.isEmpty() ? p : p.withPacks(packs);
        }

        List<Path> classpath() {
            return Logast.classpath(root(), classpathFile);
        }
    }

    @Command(name = "scan", description = "Analyse the project and write work orders to <root>/.logast/orders.json")
    static class Scan extends Common implements Callable<Integer> {
        @Option(names = "--format", defaultValue = "compact", description = "compact | json | summary | sarif | fills-template")
        String format;
        @Option(names = "--rule", split = ",", description = "Only orders fixing these rules (comma-separated or repeated)")
        List<String> rules = new ArrayList<>();
        @Option(names = "--entrypoint", description = "Only orders on a path from an entrypoint containing this text")
        String entrypoint;
        @Option(names = "--severity", defaultValue = "low", description = "Minimum severity: low | medium | high")
        String severity;
        @Option(names = "--limit", description = "Print at most N orders")
        Integer limit;
        @Option(names = "--out", description = "Full JSON output (default: <root>/.logast/orders.json)")
        Path out;
        @Option(names = "--fail-on", description = "Exit with 2 when an order of this severity or higher exists (CI)")
        String failOn;
        @Option(names = "--baseline", description = "Only report orders not in this baseline (default file: <root>/.logast/baseline.json)",
                arity = "0..1", fallbackValue = "")
        String baseline;
        @Option(names = "--update-baseline", description = "Write the current orders as the new baseline and exit")
        boolean updateBaseline;
        @Option(names = "--since", description = "Only orders on lines changed since this git ref (e.g. origin/main)")
        String since;

        @Override
        public Integer call() throws IOException {
            Policy policy = policy();
            ScanResult r = Logast.scan(root(), classpath(), policy, traffic);
            dev.logast.Traffic t = Logast.lastAnalysis.traffic;
            if (t.known()) {
                java.util.Set<String> labels = new java.util.TreeSet<>();
                Logast.lastAnalysis.entrypoints.forEach(ep -> labels.add(ep.label()));
                List<String> unmatched = t.labels().stream().filter(l -> !labels.contains(l)).toList();
                long covered = labels.stream().filter(l -> t.perDay(l) != null).count();
                System.err.println("traffic (" + t.source() + "): " + covered + " of " + labels.size() + " entrypoints have rates"
                        + (unmatched.isEmpty() ? "" : "; labels matching no entrypoint (check spelling with `logast explain`): " + unmatched));
            }
            Path target = out != null ? out : root().resolve(".logast/orders.json");
            Files.createDirectories(target.toAbsolutePath().getParent());
            OrderJson.MAPPER.writeValue(target.toFile(), r);

            Path baselineFile = baseline == null || baseline.isEmpty() ? root().resolve(".logast/baseline.json") : Path.of(baseline);
            if (updateBaseline) {
                dev.logast.orders.Baseline.of(r).write(baselineFile);
                System.out.println("baseline: " + r.orders().size() + " orders written to " + baselineFile);
                return 0;
            }
            List<WorkOrder> candidates = r.orders();
            String scope = "";
            if (baseline != null) {
                if (!Files.exists(baselineFile)) {
                    System.err.println("no baseline at " + baselineFile + " (create it with --update-baseline)");
                    return 3;
                }
                dev.logast.orders.Baseline b = dev.logast.orders.Baseline.read(baselineFile);
                candidates = b.newOnly(candidates);
                scope += " new vs baseline (" + b.fixed(r.orders()).size() + " baseline findings fixed since)";
            }
            if (since != null) {
                candidates = dev.logast.orders.GitDiff.since(root(), since).filter(candidates);
                scope += " on lines changed since " + since;
            }
            List<WorkOrder> shown = candidates.stream()
                    .filter(o -> rules.isEmpty() || o.fixes().stream().anyMatch(rules::contains))
                    .filter(o -> entrypoint == null || o.paths().stream().anyMatch(p -> p.contains(entrypoint)))
                    .filter(o -> CompactFormat.atLeast(o, severity))
                    .limit(limit == null ? Long.MAX_VALUE : limit)
                    .toList();
            if (!scope.isEmpty()) {
                System.err.println("scope:" + scope + ": " + shown.size() + " of " + r.orders().size() + " orders");
            }
            switch (format) {
                case "json" -> System.out.println(OrderJson.MAPPER.writeValueAsString(shown));
                case "sarif" -> System.out.println(OrderJson.MAPPER.writeValueAsString(dev.logast.orders.Sarif.of(shown)));
                case "fills-template" -> System.out.println(OrderJson.MAPPER.writeValueAsString(FillsTemplate.of(shown)));
                case "summary" -> {
                    if (r.stats().noClasspath() || r.stats().unresolvedCalls() > 100) {
                        System.err.println("WARNING: " + (r.stats().noClasspath() ? "no classpath; " : "") + r.stats().unresolvedCalls()
                                + " unresolved calls: results are incomplete (run `logast classpath`)");
                    }
                    System.out.print(CompactFormat.summary(shown));
                }
                default -> {
                    System.out.print(CompactFormat.render(r, shown, policy.terms()));
                    if (!policy.overrides().isEmpty()) {
                        System.out.println("\npolicy overrides (" + policy.overrides().size() + "): "
                                + String.join("; ", policy.overrides().stream().limit(8).toList()));
                    }
                }
            }
            if (failOn != null && shown.stream().anyMatch(o -> CompactFormat.atLeast(o, failOn))) {
                return 2;
            }
            return 0;
        }
    }

    @Command(name = "explain", description = "Call tree below entrypoints with log / MDC / async markers and order ids")
    static class Explain extends Common implements Callable<Integer> {
        @Option(names = "--entrypoint", description = "Entrypoint label or method filter; omit to list entrypoints")
        String entrypoint;
        @Option(names = "--depth", defaultValue = "12")
        int depth;

        @Override
        public Integer call() {
            Analysis a = Logast.analyse(root(), classpath(), policy()).withTraffic(dev.logast.Traffic.load(root(), traffic));
            if (entrypoint == null) {
                System.out.print(new Explainer(a, Map.of()).listEntrypoints());
                return 0;
            }
            ScanResult r = Rules.scan(a);
            Map<String, List<String>> byMethod = new HashMap<>();
            Map<String, String> keyByLabel = new HashMap<>();
            a.index.methods().forEach(m -> keyByLabel.put(m.label(), m.key()));
            for (WorkOrder o : r.orders()) {
                if (o.method() != null && keyByLabel.containsKey(o.method())) {
                    byMethod.computeIfAbsent(keyByLabel.get(o.method()), k -> new ArrayList<>()).add(o.id());
                }
            }
            System.out.print(new Explainer(a, byMethod).explain(entrypoint, depth));
            return 0;
        }
    }

    @Command(name = "apply", description = "Apply orders (dry run unless --write). Rescans between rounds; ids are stable.")
    static class Apply extends Common implements Callable<Integer> {
        @Option(names = "--fills", description = "JSON {\"<id>\": {\"MSG\": \"text\", \"args\": [\"var\"]}}")
        Path fills;
        @Option(names = "--auto", description = "Also apply every order that needs no text (including ones that appear after earlier fixes)")
        boolean auto;
        @Option(names = "--only", split = ",", description = "Restrict to these order ids")
        Set<String> only = new LinkedHashSet<>();
        @Option(names = "--write", description = "Write changes (default: print the diff only)")
        boolean write;
        @Option(names = "--format", defaultValue = "text", description = "text | json")
        String format;

        @Override
        public Integer call() throws IOException {
            if (!auto && fills == null) {
                System.err.println("""
                        logast apply: nothing selected. Choose what to apply:
                          --auto                apply every fix that needs no message text (mechanical fixes)
                          --fills fills.json    apply fixes that need text, with the text from this file
                                                (skeleton: `logast scan . --format fills-template > fills.json`)
                        and add --write to change files; without it you get a preview diff.
                        e.g.  logast apply . --auto            # preview
                              logast apply . --auto --write    # change files""");
                return 2;
            }
            if (!write && !format.equals("json")) {
                System.err.println("DRY RUN: previewing only, no files are changed (add --write to apply)");
            }
            Policy policy = policy();
            List<Path> cp = classpath();
            Map<String, Fill> fillMap = fills == null ? Map.of()
                    : OrderJson.MAPPER.readValue(fills.toFile(), new TypeReference<LinkedHashMap<String, Fill>>() {
                    });
            Applier applier = new Applier(new dev.logast.IncrementalScanner(cp, root -> packs.isEmpty() ? Logast.policy(root, null)
                    : Logast.policy(root, null).withPacks(packs)),
                    new FillValidator(policy));
            Applier.Result r = applier.apply(root(), new Applier.Selection(auto, only, fillMap), write);
            if (format.equals("json")) {
                System.out.println(OrderJson.MAPPER.writeValueAsString(r));
            } else {
                System.out.println((write ? "applied (" : "would apply (") + r.applied().size() + "): " + String.join(" ", r.applied()));
                if (r.applied().isEmpty() && fills == null) {
                    System.out.println("nothing to apply without text: the remaining orders need message text "
                            + "(`logast scan .` lists them; fill them via --fills or `logast review .`)");
                }
                if (!r.resolvedIndirectly().isEmpty()) {
                    System.out.println("resolved by other changes: " + String.join(" ", r.resolvedIndirectly()));
                }
                r.rejected().forEach((id, why) -> System.out.println("REJECTED " + id + ": " + why));
                System.out.println("files: " + String.join(", ", r.changedFiles()) + "  (rounds: " + r.rounds() + ")");
                if (write && r.applied().stream().anyMatch(id -> id.startsWith("SUP-") || id.startsWith("OTL-"))) {
                    System.out.println("NEXT: run `logast classpath " + root + "`: dependencies changed, the classpath file is stale");
                }
                if (!write) {
                    System.out.println();
                    System.out.print(r.diff());
                    System.out.println("(dry run: add --write to change files)");
                }
            }
            return r.rejected().isEmpty() ? 0 : 1;
        }
    }

    @Command(name = "classpath", description = "Run Gradle once to write <root>/.logast/classpath-*.txt (needed for type resolution)")
    static class Classpath implements Callable<Integer> {
        @Parameters(index = "0", defaultValue = ".")
        Path root;

        @Override
        public Integer call() throws IOException, InterruptedException {
            Path r = root.toAbsolutePath().normalize();
            Path init = Files.createTempFile("logast-classpath", ".init.gradle");
            try (InputStream in = Main.class.getResourceAsStream("/logast-classpath.init.gradle")) {
                Files.write(init, Objects.requireNonNull(in).readAllBytes());
            }
            Path wrapper = r.resolve(System.getProperty("os.name").toLowerCase().contains("win") ? "gradlew.bat" : "gradlew");
            String gradle = Files.exists(wrapper) ? wrapper.toString() : "gradle";
            Process p = new ProcessBuilder(gradle, "-q", "--no-configuration-cache", "--init-script", init.toString(), "logastClasspath")
                    .directory(r.toFile()).inheritIO().start();
            int rc = p.waitFor();
            Files.deleteIfExists(init);
            if (rc != 0) {
                System.err.println("logast classpath: Gradle failed (exit " + rc + "); run `./gradlew --init-script <script> logastClasspath` "
                        + "without -q in " + r + " to see why. Scans without a classpath leave most calls unresolved.");
                return rc;
            }
            List<Path> cp = Logast.classpath(r, null);
            boolean classes = dev.logast.parse.ProjectParser.hasCompiledClasses(cp);
            System.out.println("classpath: " + cp.size() + " entries in " + r.resolve(".logast")
                    + (classes ? " (incl. compiled classes: apply rounds re-parse only changed files)"
                    : " (no compiled classes found: build the project once so apply rounds can re-parse incrementally)"));
            return 0;
        }
    }

    @Command(name = "traffic", description = "Write .logast/traffic.yaml listing every entrypoint (rates empty to fill in; existing values kept)")
    static class TrafficCmd extends Common implements Callable<Integer> {
        @Override
        public Integer call() throws IOException {
            Analysis a = Logast.analyse(root(), classpath(), policy());
            Path file = traffic != null ? traffic : root().resolve(".logast/traffic.yaml");
            dev.logast.Traffic existing = Files.exists(file) ? dev.logast.Traffic.load(root(), file) : dev.logast.Traffic.none();
            Files.createDirectories(file.toAbsolutePath().getParent());
            Files.writeString(file, dev.logast.Traffic.template(a.entrypoints, existing));
            long filled = a.entrypoints.stream().filter(ep -> existing.perDay(ep.label()) != null).count();
            System.out.println("traffic: " + a.entrypoints.size() + " entrypoints in " + file + " (" + filled + " with rates)");
            return 0;
        }
    }

    @Command(name = "rules", description = "List rule ids with their packs")
    static class RulesCmd implements Callable<Integer> {
        @Override
        public Integer call() {
            Rules.all().forEach(r -> System.out.printf("%-26s %s%n", r, String.join(",", new TreeSet<>(Rules.packsOf(r)))));
            return 0;
        }
    }

    @Command(name = "policy", description = "Print the effective policy (all layers merged) and its overrides")
    static class PolicyCmd extends Common implements Callable<Integer> {
        @Override
        public Integer call() throws IOException {
            System.out.println(new com.fasterxml.jackson.databind.ObjectMapper(new com.fasterxml.jackson.dataformat.yaml.YAMLFactory())
                    .setSerializationInclusion(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_EMPTY)
                    .writeValueAsString(policy()));
            return 0;
        }
    }
}
