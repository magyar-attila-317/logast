package dev.logast.cli;

import com.fasterxml.jackson.core.type.TypeReference;
import dev.logast.Analysis;
import dev.logast.Explainer;
import dev.logast.Logast;
import dev.logast.apply.Applier;
import dev.logast.apply.Fill;
import dev.logast.apply.FillValidator;
import dev.logast.orders.Ansi;
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
                Main.PolicyCmd.class, Init.class, Main.VocabularyCmd.class, Main.MetricsCmd.class, Verify.class, Fleet.class, Review.class, Main.TrafficCmd.class, Main.WhyCmd.class, Main.ShapeCmd.class, Main.LifecycleCmd.class})
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
            return new String[]{"logast " + p.getProperty("version", "dev") + " (" + p.getProperty("commit", "unknown") + ")"};
        }
    }

    public static void main(String[] args) {
        Out.detectColor();
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
            ScanResult r;
            try (Out.Progress p = new Out.Progress("analysing the code (parsing dominates: about 30 s for 2,000 files)")) {
                r = Logast.scan(root(), classpath(), policy, traffic);
            }
            dev.logast.Traffic t = Logast.lastAnalysis.traffic;
            if (t.known()) {
                java.util.Set<String> labels = new java.util.TreeSet<>();
                Logast.lastAnalysis.entrypoints.forEach(ep -> labels.add(ep.label()));
                List<String> unmatched = t.labels().stream().filter(l -> !labels.contains(l)).toList();
                long covered = labels.stream().filter(l -> t.perDay(l) != null).count();
                Out.progress("traffic (" + t.source() + "): " + covered + " of " + labels.size() + " entrypoints have rates"
                        + (unmatched.isEmpty() ? "" : "; labels matching no entrypoint (check spelling with `logast explain`): " + unmatched));
            }
            Path target = out != null ? out : root().resolve(".logast/orders.json");
            Files.createDirectories(target.toAbsolutePath().getParent());
            OrderJson.MAPPER.writeValue(target.toFile(), r);
            if (out == null) {
                var fu = dev.logast.apply.FillsFile.update(root(), r.orders());
                if ((fu.entries() > 0 || fu.written()) && (format.equals("compact") || format.equals("summary"))) {
                    Out.progress(fillsLine(fu));
                }
                var mu = dev.logast.apply.MetricsFile.update(root(), r.orders());
                if (mu.written() && (format.equals("compact") || format.equals("summary"))) {
                    Out.progress(metricsLine(mu));
                }
            }

            Path baselineFile = baseline == null || baseline.isEmpty() ? root().resolve(".logast/baseline.json") : Path.of(baseline);
            if (updateBaseline) {
                dev.logast.orders.Baseline.of(r).write(baselineFile);
                Out.step("baseline", r.orders().size() + " findings recorded in " + baselineFile + "; CI now fails only on new ones");
                return 0;
            }
            List<WorkOrder> candidates = r.orders();
            String scope = "";
            if (baseline != null) {
                if (!Files.exists(baselineFile)) {
                    Out.warn("no baseline at " + baselineFile + " (create it with --update-baseline)");
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
                Out.progress("scope:" + scope + ": " + shown.size() + " of " + r.orders().size() + " orders");
            }
            switch (format) {
                case "json" -> System.out.println(OrderJson.MAPPER.writeValueAsString(shown));
                case "sarif" -> System.out.println(OrderJson.MAPPER.writeValueAsString(dev.logast.orders.Sarif.of(shown)));
                case "fills-template" -> System.out.println(OrderJson.MAPPER.writeValueAsString(FillsTemplate.of(shown)));
                case "summary" -> {
                    if (!r.stats().coverageComplete()) {
                        Out.warn("coverage incomplete: " + r.stats().files() + " of " + r.stats().discoveredFiles()
                                + " source files analysed " + r.stats().parseErrors().stream().limit(5).toList());
                    }
                    if (r.stats().noClasspath() || r.stats().unresolvedCalls() > 100) {
                        Out.warn((r.stats().noClasspath() ? "no classpath; " : "") + r.stats().unresolvedCalls()
                                + " unresolved calls: results are incomplete (run `logast classpath`)");
                    }
                    System.out.print(CompactFormat.summary(shown));
                    if (r.coverage() != null && r.coverage().total() > 0) {
                        System.out.println("log coverage " + r.coverage().line());
                    }
                }
                default -> {
                    System.out.print(CompactFormat.render(r, shown, policy.terms()));
                    if (!policy.overrides().isEmpty()) {
                        System.out.println(Ansi.dim("\npolicy overrides (" + policy.overrides().size() + "): "
                                + String.join("; ", policy.overrides().stream().limit(8).toList())));
                    }
                }
            }
            if (format.equals("compact") || format.equals("summary")) {
                List<String[]> next = new ArrayList<>();
                dev.logast.Todo.of(root(), r, policy, Logast.lastAnalysis, List.of(), false)
                        .forEach(item -> next.add(new String[]{item.action(), item.why()}));
                if (!next.isEmpty()) {
                    Out.next(next);
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
        @Option(names = "--fills", description = "Message texts (default: .logast/fills.json, kept up to date by `logast scan`)")
        Path fills;
        @Option(names = "--auto", description = "Also apply every order that needs no text (including ones that appear after earlier fixes)")
        boolean auto;
        @Option(names = "--only", split = ",", description = "Restrict to these order ids")
        Set<String> only = new LinkedHashSet<>();
        @Option(names = "--until", description = "Stop after this pipeline step: setup, structure, lines, mdc, messages, exceptions, levels, reports (default: all)")
        String until;
        @Option(names = "--write", description = "Write changes (default: print the diff only)")
        boolean write;
        @Option(names = "--format", defaultValue = "text", description = "text | json")
        String format;

        @Override
        public Integer call() throws IOException {
            Map<String, Fill> fillMap = dev.logast.apply.FillsFile.forApply(root(), fills);
            if (!auto && fillMap.isEmpty()) {
                System.err.println("""
                        logast apply: nothing selected. Choose what to apply:
                          --auto     every fix that needs no message text
                          texts      fixes that need a message: write them into .logast/fills.json (`logast scan`
                                     creates it) and they are picked up automatically
                        and add --write to change files; without it you get a preview.
                        e.g.  logast apply . --auto            # preview
                              logast apply . --auto --write    # change files""");
                return 2;
            }
            if (!fillMap.isEmpty() && !format.equals("json")) {
                Out.progress("using " + fillMap.size() + " message text(s) from " + (fills != null ? fills : dev.logast.apply.FillsFile.PATH));
            }
            if (!write && !format.equals("json")) {
                Out.progress("DRY RUN: previewing only, no files are changed (add --write to apply)");
            }
            long t0 = System.nanoTime();
            Policy policy = policy();
            List<Path> cp = classpath();
            dev.logast.RoundScanner scanner = new dev.logast.RoundScanner(cp, root -> packs.isEmpty() ? Logast.policy(root, null)
                    : Logast.policy(root, null).withPacks(packs));
            Rules.Step untilStep = until == null ? null : Rules.Step.valueOf(until.toUpperCase(java.util.Locale.ROOT));
            Applier applier = new Applier(scanner, new FillValidator(policy)).until(untilStep)
                    .onInvariants((work, files) -> scanner.lastAnalysis == null ? List.of() : dev.logast.Invariants.check(scanner.lastAnalysis, files));
            Applier.Result r;
            try (Out.Progress p = new Out.Progress("~starting")) {
                applier.onProgress(p::update);
                r = applier.apply(root(), new Applier.Selection(auto, only, fillMap, fills == null), write);
            }
            if (fills == null) {
                // the automatic file follows the code: created if missing, finished entries out, new ones in
                var fu = dev.logast.apply.FillsFile.update(root(), (write ? r.after() : r.before()).orders());
                if (fu.written() && !format.equals("json")) {
                    Out.progress(fillsLine(fu));
                }
                var mu = dev.logast.apply.MetricsFile.update(root(), (write ? r.after() : r.before()).orders());
                if (mu.written() && !format.equals("json")) {
                    Out.progress(metricsLine(mu));
                }
            }
            // one diff per pipeline step, applicable in order: review or drop a whole kind of change at once
            Path stepsDir = root().resolve(".logast/steps");
            if (Files.exists(stepsDir) && !r.stepDiffs().isEmpty()) {   // a run that changes nothing keeps the last ones
                try (var old = Files.list(stepsDir)) {
                    for (Path p : old.toList()) {
                        Files.delete(p);
                    }
                }
            }
            if (!r.stepDiffs().isEmpty()) {
                Files.createDirectories(stepsDir);
                int i = 0;
                List<String> names = new ArrayList<>();
                for (var e : r.stepDiffs().entrySet()) {
                    String file = String.format("%02d-%s.diff", ++i, e.getKey());
                    Files.writeString(stepsDir.resolve(file), e.getValue());
                    names.add(file);
                }
                if (!format.equals("json")) {
                    Out.progress("per step (apply in order, drop what you don't want): .logast/steps/ " + String.join(" ", names));
                }
            }
            if (format.equals("json")) {
                System.out.println(OrderJson.MAPPER.writeValueAsString(r));
                return r.rejected().isEmpty() ? 0 : 1;
            }
            if (!write && !r.diff().isEmpty()) {
                System.out.print(colorDiff(r.diff()));
                System.out.println();
            }
            System.out.println(Ansi.bold((write ? "applied (" : "would apply (") + r.applied().size() + ")") + ": "
                    + Ansi.dim(String.join(" ", r.applied())));
            System.out.println(Ansi.dim("files (" + r.changedFiles().size() + "): " + String.join(", ", r.changedFiles())
                    + "  ·  " + r.rounds() + " rounds, " + Out.seconds(t0)));
            if (r.applied().isEmpty() && fillMap.isEmpty()) {
                System.out.println("nothing to apply without text: the remaining orders need message text "
                        + "(`logast scan .` lists them; fill them via --fills or `logast review .`)");
            }
            if (!r.resolvedIndirectly().isEmpty()) {
                System.out.println(Ansi.dim("resolved by other changes: " + String.join(" ", r.resolvedIndirectly())));
            }
            r.rejected().forEach((id, why) -> System.out.println(Ansi.red("REJECTED " + id) + ": " + why));
            if (!r.violations().isEmpty()) {
                System.out.println(Ansi.warn("CHECK FAILED (" + r.violations().size() + "): the result breaks a rule every migrated file must follow"));
                r.violations().forEach(v -> System.out.println("  " + v));
            }
            List<String[]> next = new ArrayList<>();
            if (!write && !r.applied().isEmpty()) {
                next.add(new String[]{"logast apply . " + (auto ? "--auto " : "") + (fills != null ? "--fills " + fills + " " : "") + "--write",
                        "make these " + r.applied().size() + " changes"});
            }
            if (write && r.changedFiles().stream().anyMatch(fn -> fn.endsWith("build.gradle") || fn.endsWith("build.gradle.kts"))) {
                // dependencies changed: refresh the classpath right away, so the next scan sees the new types
                Main.Classpath.Result cpr;
                try (Out.Progress p = new Out.Progress("dependencies changed: running Gradle to refresh the classpath")) {
                    cpr = Main.Classpath.write(root());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    cpr = new Main.Classpath.Result(130, 0, false);
                }
                if (cpr.exitCode() == 0) {
                    Out.progress("classpath refreshed (" + cpr.entries() + " entries)");
                } else {
                    next.add(new String[]{"logast classpath .", "dependencies changed and Gradle failed (exit " + cpr.exitCode()
                            + "): fix the build, then refresh the classpath"});
                }
            }
            if (write && !r.changedFiles().isEmpty()) {
                next.add(new String[]{"git diff", "review the changes"});
            }
            if (!r.violations().isEmpty()) {
                next.add(new String[]{"read the CHECK FAILED lines above", r.violations().size() + " places break a migration invariant: "
                        + "a logast bug, please report the shape of the code"});
            }
            if (!r.rejected().isEmpty()) {
                next.add(new String[]{"read the REJECTED lines above", (r.rejected().size() == 1 ? "1 fix" : r.rejected().size() + " fixes")
                        + " could not be applied; each says why"});
            }
            // what is left after this run: the files to fill and the decisions to make
            dev.logast.orders.ScanResult left = write ? r.after() : r.before();
            dev.logast.Todo.of(root(), left, policy, scanner.lastAnalysis, r.applied(), write).stream()
                    .filter(t -> write || !t.action().startsWith("logast apply"))
                    .forEach(t -> next.add(new String[]{t.action(), t.why()}));
            if (!next.isEmpty()) {
                Out.next(next);
            }
            return r.rejected().isEmpty() ? 0 : 1;
        }
    }

    static String metricsLine(dev.logast.apply.FillsFile.Update mu) {
        return dev.logast.apply.MetricsFile.PATH + ": " + mu.entries() + " outcomes not counted, " + mu.filled() + " decided"
                + (mu.added() > 0 ? ", " + mu.added() + " new" : "") + (mu.removed() > 0 ? ", " + mu.removed() + " finished removed" : "")
                + "; set \"count\": counter | tag | skip, then `logast apply . --auto`";
    }

    static String fillsLine(dev.logast.apply.FillsFile.Update fu) {
        List<String> parts = new ArrayList<>();
        if (fu.added() > 0) {
            parts.add(fu.added() + " new");
        }
        if (fu.removed() > 0) {
            parts.add(fu.removed() + " finished removed");
        }
        if (fu.entries() == 0) {
            return dev.logast.apply.FillsFile.PATH + ": nothing needs a message text right now"
                    + (fu.removed() > 0 ? " (" + fu.removed() + " finished removed)" : "");
        }
        return dev.logast.apply.FillsFile.PATH + ": " + fu.entries() + " orders need a message text, " + fu.filled() + " written"
                + (parts.isEmpty() ? "" : " (" + String.join(", ", parts) + ")") + "; `logast apply . --auto` uses the ones you fill in";
    }

    /** Unified diff with the usual colours: file headers bold, hunks cyan, removed red, added green. */
    static String colorDiff(String diff) {
        if (!Ansi.enabled()) {
            return diff;
        }
        StringBuilder sb = new StringBuilder();
        for (String line : diff.split("\n", -1)) {
            String out = line.startsWith("diff --git") || line.startsWith("+++") || line.startsWith("---") ? Ansi.bold(line)
                    : line.startsWith("@@") ? Ansi.cyan(line)
                    : line.startsWith("+") ? Ansi.green(line)
                    : line.startsWith("-") ? Ansi.red(line) : line;
            sb.append(out).append('\n');
        }
        return sb.substring(0, sb.length() - 1);
    }

    @Command(name = "classpath", description = "Run Gradle once to write <root>/.logast/classpath-*.txt (needed for type resolution)")
    static class Classpath implements Callable<Integer> {
        @Parameters(index = "0", defaultValue = ".")
        Path root;

        /** Result of one Gradle run: exit code, classpath entries, whether compiled classes were found. */
        record Result(int exitCode, int entries, boolean compiledClasses) {
        }

        /** Runs Gradle with the classpath init script (compiles nothing); prints only Gradle's own errors. */
        static Result write(Path r) throws IOException, InterruptedException {
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
            List<Path> cp = rc == 0 ? Logast.classpath(r, null) : List.of();
            return new Result(rc, cp.size(), dev.logast.parse.ProjectParser.hasCompiledClasses(cp));
        }

        @Override
        public Integer call() throws IOException, InterruptedException {
            Path r = root.toAbsolutePath().normalize();
            long t0 = System.nanoTime();
            Out.progress("running Gradle to read the classpath (nothing is compiled)…");
            Result res = write(r);
            if (res.exitCode() != 0) {
                Out.failed("classpath", "Gradle failed (exit " + res.exitCode() + ")");
                Out.warn("run `./gradlew --init-script <script> logastClasspath` without -q in " + r + " to see why. "
                        + "Scans without a classpath leave most calls unresolved.");
                return res.exitCode();
            }
            Out.step(".logast/classpath-*.txt", res.entries() + " entries in " + Out.seconds(t0));
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

    @Command(name = "vocabulary", description = "Harvest a draft vocabulary from the code (MDC keys, [k=v] keys in log messages, "
            + "id-like variables): terms the policy does not cover yet, as YAML to reorganise and paste into logast.yaml")
    static class VocabularyCmd extends Common implements Callable<Integer> {
        @Option(names = "--min-declared", defaultValue = "3",
                description = "Identifiers that are never logged or put in MDC need this many declarations (default 3)")
        int minDeclared;
        @Option(names = "--all", description = "Ignore the current policy: show everything, including terms it already defines")
        boolean all;

        @Override
        public Integer call() {
            Policy p = policy();
            dev.logast.VocabularyHarvest.Result h = dev.logast.VocabularyHarvest.run(root(), all ? null : p, p.logging().style(), minDeclared);
            System.out.print(dev.logast.VocabularyHarvest.yaml(h));
            return 0;
        }
    }

    @Command(name = "metrics", description = "What the service measures and what it does not: exporters, service/version "
            + "identity, the metric (and tag values) covering each entrypoint, meters in code, and outcomes the logs report "
            + "that no metric counts")
    static class MetricsCmd extends Common implements Callable<Integer> {
        @Option(names = "--format", defaultValue = "map", description = "map (default) | json (the same, for tools and LLMs) | table (meters only)")
        String format;

        @Override
        public Integer call() throws IOException {
            Analysis a = Logast.analyse(root(), classpath(), policy()).withTraffic(dev.logast.Traffic.load(root(), traffic));
            if (format.equals("table")) {
                System.out.print(dev.logast.metrics.MeterCatalog.table(dev.logast.metrics.MeterCatalog.of(a)));
                return 0;
            }
            dev.logast.rules.MetricsMap.Map map = dev.logast.rules.MetricsMap.of(a);
            System.out.print(format.equals("json") ? OrderJson.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(map) + "\n"
                    : dev.logast.rules.MetricsMap.text(map));
            return 0;
        }
    }

    @Command(name = "why", description = "Why an MDC key is (or is not) scoped where it is: every method that logs it, "
            + "the decision and the reason")
    static class WhyCmd extends Common implements Callable<Integer> {
        @CommandLine.Parameters(index = "1", description = "MDC key, e.g. couponId")
        String key;

        @Override
        public Integer call() throws IOException {
            Analysis a = Logast.analyse(root(), classpath(), policy());
            Rules.scan(a);
            List<dev.logast.rules.RepeatedValueRules.Decision> ds = Rules.lastMdcDecisions.stream()
                    .filter(d -> d.key().equals(key)).distinct().toList();   // one per method and outcome
            // scopes already in the code (e.g. after an apply): where, and whether the key arrives in MDC there anyway
            List<String> existing = new ArrayList<>();
            for (var m : a.index.methods()) {
                for (var op : m.facts().mdc) {
                    if (key.equals(op.key()) && (op.kind() == dev.logast.model.MdcOp.Kind.PUT_CLOSEABLE || op.kind() == dev.logast.model.MdcOp.Kind.PUT)) {
                        boolean onEntry = a.paths.mdcAtEntry(m).contains(key);
                        int callers = (int) a.graph.in(m).stream().map(e -> e.from().key()).distinct().count();
                        String via = a.graph.in(m).stream().map(e -> e.from().label()).distinct().limit(4)
                                .reduce((x, y) -> x + ", " + y).orElse("");
                        existing.add("  " + m.label() + Ansi.dim("  " + m.file().relPath() + ":" + dev.logast.parse.Pos.line(op.call()))
                                + "\n    " + (onEntry ? Ansi.yellow("already in MDC on entry: a nested scope (callers: " + via + ")")
                                : a.entrypoints.stream().anyMatch(ep -> ep.method().key().equals(m.key())) ? "the start of a flow (an entrypoint)"
                                : callers == 0 ? "not in MDC on entry; logast sees no caller of this method (an unresolved call?)"
                                : "not in MDC on entry " + (callers == 1 ? "(caller: " : "on every path (callers: ") + via + ")"));
                    }
                }
            }
            if (!existing.isEmpty()) {
                System.out.println(Ansi.bold("scopes in the code now (" + existing.size() + ")"));
                existing.forEach(System.out::println);
            }
            if (ds.isEmpty()) {
                System.out.println(existing.isEmpty()
                        ? "No method logs or scopes " + key + " (or it is not a vocabulary key). `logast vocabulary .` lists the keys."
                        : "No log line mentions " + key + " any more: nothing left to decide.");
                return 0;
            }
            Map<String, List<dev.logast.rules.RepeatedValueRules.Decision>> byOutcome = new TreeMap<>();
            ds.forEach(d -> byOutcome.computeIfAbsent(d.outcome(), x -> new ArrayList<>()).add(d));
            for (var e : byOutcome.entrySet()) {
                System.out.println(Ansi.bold(e.getKey()) + " (" + e.getValue().size() + ")");
                e.getValue().forEach(d -> System.out.println("  " + d.method() + Ansi.dim("  " + d.location()) + "\n    " + d.reason()));
            }
            return 0;
        }
    }

    @Command(name = "shape", description = "Export the code's shape with its own names and words replaced (to send a "
            + "reproduction without the content): <root>/.logast/shape/, mapping back in <root>/.logast/shape-mapping.txt")
    static class ShapeCmd extends Common implements Callable<Integer> {
        @Override
        public Integer call() throws IOException {
            List<Path> cp = classpath();
            if (cp.isEmpty()) {
                Out.warn("no classpath (.logast/classpath*.txt): run `logast classpath .` first, or the export misses how "
                        + "Lombok and library calls connect your code");
            }
            Analysis a = Logast.analyse(root(), cp, policy());
            var r = dev.logast.ShapeExport.write(a, root().resolve(".logast/shape"), root().resolve(".logast/shape-mapping.txt"));
            System.out.println("wrote " + r.files() + " files to " + root().relativize(r.dir()) + " (" + r.words() + " words replaced)");
            System.out.println("read it before sending it; the mapping back stays here: " + root().relativize(r.mapping()));
            return 0;
        }
    }

    @Command(name = "lifecycle", description = "Every flow's lifecycle lines: kind, which lines exist, who owns the start and "
            + "the outcome, and what logast will add; plus implementation families that log unevenly")
    static class LifecycleCmd extends Common implements Callable<Integer> {
        @Override
        public Integer call() throws IOException {
            Analysis a = Logast.analyse(root(), classpath(), policy());
            Rules.scan(a);
            var plan = Rules.lastLifecycle;
            Map<String, List<dev.logast.rules.LifecyclePlan.Flow>> byKind = new TreeMap<>();
            a.entrypoints.forEach(ep -> {
                var f = plan.flow(ep);
                byKind.computeIfAbsent(f.kind().name().toLowerCase(java.util.Locale.ROOT), k -> new ArrayList<>()).add(f);
            });
            for (var e : byKind.entrySet()) {
                long open = e.getValue().stream().filter(f -> !f.missing().isEmpty()).count();
                System.out.println(Ansi.bold(e.getKey() + " (" + e.getValue().size() + ")") + Ansi.dim(open == 0 ? "" : "   " + open + " missing lines"));
                for (var f : e.getValue()) {
                    System.out.println("  " + f.label() + Ansi.dim("  " + f.location()));
                    System.out.println("    start: " + (f.started() ? "present" : "none") + Ansi.dim(" (" + f.startOwner() + ")")
                            + "   outcome: " + (f.outcome() ? "present" : "none") + Ansi.dim(" (" + f.outcomeOwner() + ")")
                            + (f.missing().isEmpty() ? "" : Ansi.yellow("   adds: " + String.join(", ", f.missing()))));
                }
            }
            var families = plan.families();
            if (!families.isEmpty()) {
                System.out.println(Ansi.bold("implementation families (" + families.size() + ")"));
                for (var fam : families) {
                    System.out.println("  " + a.index.method(fam.base()).label());
                    fam.has().forEach((impl, has) -> System.out.println("    " + a.index.method(impl).label() + ": "
                            + (has.isEmpty() ? "no lifecycle lines" : String.join(", ", has.keySet()))
                            + (fam.missing().containsKey(impl) ? Ansi.yellow("   adds: " + String.join(", ", fam.missing().get(impl))) : "")));
                }
            }
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
