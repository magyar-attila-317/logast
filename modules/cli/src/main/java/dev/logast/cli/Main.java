package dev.logast.cli;

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

// scope INHERIT: every subcommand gets -h/--help and -V/--version too (#94); its own description is kept
@Command(name = "logast", mixinStandardHelpOptions = true, versionProvider = Main.Version.class, scope = CommandLine.ScopeType.INHERIT,
        description = "Uniform logging, MDC and tracing for Spring Boot services: finds gaps along call paths from "
                + "entrypoints and emits work orders an LLM completes by writing message text only.",
        // #125: seven in --help; the advanced ones and the old names (each says its new one) are hidden but keep working
        subcommands = {Main.Apply.class, Main.Scan.class, Review.class, Verify.class, Main.Explain.class, Fleet.class, Init.class,
                Main.ShapeCmd.class, Main.Classpath.class, Main.TrafficCmd.class, Main.VocabularyCmd.class,
                Main.MetricsCmd.class, Main.QueriesCmd.class, Main.WhyCmd.class, Main.LifecycleCmd.class, Main.PolicyCmd.class,
                Main.RulesCmd.class, Facts.class, ExportCmd.class, McpCmd.class, SupportCmd.class})
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

    private static final Set<String> WARNED = new HashSet<>();

    /** The policy's warnings ({@code PolicyValidator}), each once per run however often the policy is loaded. */
    static void warnOnce(Policy p) {
        p.warnings().stream().filter(WARNED::add).forEach(w -> Out.warn("policy: " + w));
    }

    /** The packs {@code --pack} can select, for its help text: every pack some rule belongs to. */
    static final class PackNames implements Iterable<String> {
        @Override
        public Iterator<String> iterator() {
            return Rules.packs().iterator();
        }
    }

    /**
     * Moves an earlier .logast layout into the current one (#108) and says so in one line; old files left in place get
     * a warning each. Runs before a command reads anything in .logast.
     */
    static void migrate(Path root) {
        dev.logast.LogastDir.Migration m = dev.logast.LogastDir.migrate(root);
        if (m.line() != null) {
            Out.progress(m.line());
        }
        m.warnings().forEach(Out::warn);
    }

    static class Common {
        @Parameters(index = "0", description = "Project root", defaultValue = ".")
        Path root;
        @Option(names = "--classpath-file", description = "Classpath file (default: <root>/.logast/classpath*.txt)")
        Path classpathFile;
        @Option(names = "--policy", description = "Policy YAML (default: <root>/logast.yaml)")
        Path policyFile;
        @Option(names = "--pack", split = ",", completionCandidates = PackNames.class,
                description = "Rule packs to run: ${COMPLETION-CANDIDATES} (default: policy packs)")
        List<String> packs = new ArrayList<>();
        @Option(names = "--traffic", description = "Entrypoint calls/day (YAML 'entrypoints:' or a Prometheus query result; default: <root>/.logast/decisions/traffic.*)")
        Path traffic;

        private boolean migrated;

        /** The project root; the first call moves an earlier .logast layout into the current one (#108). */
        Path root() {
            Path r = root.toAbsolutePath().normalize();
            if (!migrated) {
                migrated = true;
                migrate(r);
            }
            return r;
        }

        Policy policy() {
            Policy p = Logast.policy(root(), policyFile);
            warnOnce(p);
            return packs.isEmpty() ? p : p.withPacks(packs);
        }

        /** Whether a rule ran in this run: every rule, or with --pack only those of the packs named (#275). */
        java.util.function.Predicate<String> ranRule() {
            return packs.isEmpty() ? rule -> true : rule -> rule != null && Rules.packsOf(rule).stream().anyMatch(packs::contains);
        }

        List<Path> classpath() {
            return Logast.classpath(root(), classpathFile);
        }
    }

    @Command(name = "scan", description = "Analyse the project and write work orders to <root>/.logast/reports/orders.json")
    static class Scan extends Common implements Callable<Integer> {
        @Option(names = "--format", description = "human (default in a terminal: grouped, one line per finding) | compact "
                + "(default otherwise: every order in full, for an LLM) | json | summary | sarif | github (GitHub Actions annotations) | fills-template")
        String format;
        @Option(names = "--rule", split = ",", description = "Only orders fixing these rules (comma-separated or repeated)")
        List<String> rules = new ArrayList<>();
        @Option(names = "--entrypoint", description = "Only orders on a path from an entrypoint containing this text")
        String entrypoint;
        @Option(names = "--severity", defaultValue = "low", description = "Minimum severity: low | medium | high")
        String severity;
        @Option(names = "--limit", description = "Print at most N orders")
        Integer limit;
        @Option(names = "--out", description = "Full JSON output (default: <root>/.logast/reports/orders.json)")
        Path out;
        @Option(names = "--fail-on", description = "Exit with 2 when an order of this severity or higher exists (CI); "
                + "with 4 when the analysis is incomplete (files not parsed, no classpath)")
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
            List<String> unknown = rules.stream().filter(id -> !Rules.all().contains(id)).toList();
            if (!unknown.isEmpty()) {
                unknown.forEach(id -> Out.warn("--rule " + id + ": unknown rule id"
                        + dev.logast.policy.PolicyValidator.didYouMean(id, Rules.all()) + " (`logast explain rules` lists them)"));
                return 2;
            }
            if (format == null) {
                // a person at a terminal gets the grouped view, unless they asked for one rule or path: then every detail (#107)
                format = Out.terminal() && rules.isEmpty() && entrypoint == null ? "human" : "compact";
            }
            Policy policy = policy();
            ScanResult r;
            try (Out.Progress p = new Out.Progress("analysing the code (parsing dominates: about 30 s for 2,000 files)")) {
                r = Logast.scan(root(), classpath(), policy, traffic);
            }
            FleetScans.snapshot(root(), r, Logast.lastAnalysis, policy);   // #124: a fleet member reports to its fleet
            String olderJdk = dev.logast.JavaTarget.warning(root());   // #5
            if (olderJdk != null) {
                Out.warn(olderJdk);
            }
            // #56: names in the policy and ignore comments that match nothing (also: logast explain policy . --check)
            dev.logast.rules.PolicyUsage.warnings(Logast.lastAnalysis).forEach(w -> Out.warn("policy: " + w));
            if (policyFile == null) {   // #113: entries an extended company.yaml now defines
                dev.logast.VocabularyPrune.check(root()).lines(root.toString()).forEach(Out::warn);
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
            List<dev.logast.LogLedger.Entry> ledger = null;
            Path target = out != null ? out : root().resolve(dev.logast.LogastDir.ORDERS);
            Files.createDirectories(target.toAbsolutePath().getParent());
            OrderJson.MAPPER.writeValue(target.toFile(), r);
            dev.logast.apply.FillsFile.Update fu = null;
            dev.logast.apply.FillsFile.Update mu = null;
            if (out == null) {
                // their state is part of the "waiting on you" block (#101)
                fu = dev.logast.apply.FillsFile.update(root(), r.orders(), ranRule(), true);
                mu = dev.logast.apply.MetricsFile.update(root(), r.orders(), ranRule(), true);
                if (policy.packSelected("logging")) {   // the logging pack's account of every log call (#72, as #88)
                    ledger = Logast.lastAnalysis.ledger.write(root(), Logast.lastAnalysis, r.orders(), Map.of());
                }
                dev.logast.apply.FindingComments.update(root(), Logast.lastAnalysis, r.orders());
                Reports.write(root(), Logast.lastAnalysis, Rules.lastLifecycle);   // #125
            }

            Path baselineFile = baseline == null || baseline.isEmpty() ? root().resolve(dev.logast.LogastDir.BASELINE) : Path.of(baseline);
            if (updateBaseline) {
                // what was not analysed is not in the baseline: a later complete scan reports it all as new (#55)
                r.stats().gaps().forEach(g -> Out.warn("analysis incomplete: " + g + "; the baseline does not cover it"));
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
                String running = dev.logast.orders.Baseline.currentVersion();
                if (b.version() != null && !b.version().equals(running)) {
                    Out.warn("baseline recorded by logast " + b.version() + ", this is " + running
                            + ": order ids may differ; re-record it with --update-baseline if findings show up as new");
                }
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
            List<String> gaps = r.stats().gaps();
            if (!gaps.isEmpty() && failOn == null && !format.equals("compact") && !format.equals("summary") && !format.equals("human")) {
                gaps.forEach(g -> Out.warn("analysis incomplete: " + g)); // machine formats print no header of their own
            }
            switch (format) {
                case "json" -> System.out.println(OrderJson.MAPPER.writeValueAsString(shown));
                case "sarif" -> System.out.println(OrderJson.MAPPER.writeValueAsString(dev.logast.orders.Sarif.of(shown, gaps)));
                case "github" -> {   // #148: annotations on every plan; the summary when the runner asks for one
                    System.out.print(dev.logast.orders.GithubAnnotations.of(shown, gaps));
                    String summary = System.getenv("GITHUB_STEP_SUMMARY");
                    if (summary != null && !summary.isBlank()) {
                        Files.writeString(Path.of(summary), dev.logast.orders.GithubAnnotations.summary(shown, gaps),
                                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
                    }
                }
                case "fills-template" -> System.out.print(dev.logast.apply.DecisionYaml.MAPPER.writeValueAsString(FillsTemplate.of(shown)));
                case "summary" -> {
                    if (!r.stats().coverageComplete()) {
                        Out.warn("coverage incomplete: " + r.stats().files() + " of " + r.stats().discoveredFiles()
                                + " source files analysed " + r.stats().parseErrors().stream().limit(5).toList());
                    }
                    if (r.stats().noClasspath() || r.stats().unresolvedCalls() > 100) {
                        Out.warn((r.stats().noClasspath() ? "no classpath; " : "") + r.stats().unresolvedCalls()
                                + " unresolved calls: results are incomplete (run `logast classpath`)");
                    }
                    if (r.stats().logCallsByName() > 0) {
                        Out.warn(loggersByNameLine(r.stats().logCallsByName()));
                    }
                    dev.logast.Todo todo = dev.logast.Todo.of(root(), r, policy, Logast.lastAnalysis, List.of(), false);
                    List<String> places = r.coverage() != null && r.coverage().total() > 0 ? List.of(r.coverage().plainLine()) : List.of();
                    printBlocks(appliedEarlier(root()),
                            todo.lines(new dev.logast.Todo.View(null, "logast scan .", fu, mu)),
                            dev.logast.Todo.fixFirst(shown, 8),
                            ledgerLines(ledger, places),
                            dev.logast.Todo.alsoLines(todo.also()));
                }
                case "human" -> System.out.print(dev.logast.orders.HumanFormat.render(r, shown, Logast.lastAnalysis.appPackage, Out.width()));
                default -> {
                    System.out.print(CompactFormat.render(r, shown, policy.terms()));
                    if (!policy.overrides().isEmpty()) {
                        System.out.println(Ansi.dim("\npolicy overrides (" + policy.overrides().size() + "): "
                                + String.join("; ", policy.overrides().stream().limit(8).toList())));
                    }
                }
            }
            if (format.equals("compact") || format.equals("human")) {
                // the listing above is the detail: the footer says what happens next (#101)
                dev.logast.Todo todo = dev.logast.Todo.of(root(), r, policy, Logast.lastAnalysis, List.of(), false);
                printBlocks(appliedEarlier(root()),
                        todo.lines(new dev.logast.Todo.View(null, format.equals("human") ? "the decisions above"
                                : "the REPORT-ONLY orders above", fu, mu)),
                        ledgerLines(ledger, List.of()),
                        dev.logast.Todo.alsoLines(todo.also()));
            }
            if (out == null && (format.equals("compact") || format.equals("human") || format.equals("summary"))) {
                Reports.printWritten();
            }
            if (failOn != null && !gaps.isEmpty()) {
                // a broken analysis finds less: it must not turn a CI gate green (#55)
                gaps.forEach(g -> Out.warn("analysis incomplete: " + g));
                Out.warn("--fail-on: exit 4, the findings above cannot be trusted to be complete");
                return 4;
            }
            if (failOn != null && shown.stream().anyMatch(o -> CompactFormat.atLeast(o, failOn))) {
                return 2;
            }
            return 0;
        }
    }

    @Command(name = "explain", description = "Call tree below entrypoints with log / MDC / async markers and order ids; "
            + "topics: mdc <key>, lifecycle, metrics, queries, policy, rules",
            subcommands = {ExplainMdc.class, ExplainLifecycle.class, ExplainMetrics.class, ExplainQueries.class,
                    ExplainPolicy.class, ExplainRules.class})
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

    @Command(name = "apply", description = "Change the code (--dry-run previews). Sets itself up on a service without logast.yaml "
            + "or classpath. Rescans between rounds; ids are stable.")
    static class Apply extends Common implements Callable<Integer> {
        @Option(names = "--fills", description = "Message texts (default: .logast/decisions/messages.yaml, kept up to date by `logast scan`)")
        Path fills;
        @Option(names = "--auto", description = "Apply every order that needs no text (including ones that appear after earlier "
                + "fixes). The default; with --only, add it to apply the rest too")
        boolean auto;
        @Option(names = "--only", split = ",", description = "Restrict to these order ids")
        Set<String> only = new LinkedHashSet<>();
        @Option(names = "--until", description = "Stop after this pipeline step: setup, structure, lines, mdc, messages, exceptions, levels, reports (default: all)")
        String until;
        @Option(names = "--skip", split = ",", description = "Leave these pipeline steps out (e.g. lines,levels): their findings stay open, the other steps apply")
        List<String> skip = new ArrayList<>();
        @Option(names = "--path", description = "Apply only to files matching this glob (repeatable), e.g. 'src/main/java/com/example/orders/**': a big service in batches; setup changes always apply")
        List<String> paths = new ArrayList<>();
        @Option(names = "--write", description = "Write changes (the default; kept for scripts)")
        boolean write;
        @Option(names = "--dry-run", description = "Print the diff only, change no file")
        boolean dryRun;
        @Option(names = "--force", description = "Write even though the git working tree has uncommitted changes")
        boolean force;
        @Option(names = "--format", defaultValue = "text", description = "text | json")
        String format;
        @Option(names = "--without-classpath", description = "Apply even though the Gradle build has no classpath file "
                + "(.logast/classpath*.txt): types stay unresolved and the compile gate may reject correct fixes")
        boolean withoutClasspath;
        /** #117: set on the round apply runs itself after refreshing the classpath (once). */
        @Option(names = "--refreshed", hidden = true)
        boolean refreshed;
        @Option(names = "--verbose", description = "Also list the applied order ids and the changed files "
                + "(.logast/reports/last-apply.txt always has them)")
        boolean verbose;
        @CommandLine.Spec
        CommandLine.Model.CommandSpec spec;

        /**
         * #127: the one line when the support classes are copied in only because the build cannot resolve the jar;
         * #126: in the "Set up" header.
         */
        private void supportHint(List<String> header) {
            String hint = format.equals("json") ? null : dev.logast.policy.SupportResolvable.hint(root(), policyFile);
            if (hint != null) {
                header.add(hint);
            }
        }

        /**
         * #126: what the run before a classpath refresh did, handed to the run it starts with --refreshed (in the same
         * JVM), so the one summary at the end counts both runs.
         */
        record Carried(List<String> applied, List<String> files, Map<String, String> rejected, List<String> violations,
                       List<String> header, long t0) {
        }

        static Carried carried;

        @Override
        public Integer call() throws IOException {
            Carried earlier = refreshed ? carried : null;
            carried = null;
            // #126: what this run set up, said once at the top of the summary (of both runs, after a refresh)
            List<String> header = new ArrayList<>(earlier == null ? List.of() : earlier.header());
            // #122: one command on a service with nothing set up; writing is the default, git is the safety net
            write = !dryRun;
            auto = auto || only.isEmpty();   // --only alone is a selection; texts in messages.yaml come with --auto
            Integer stop = setUp(header);
            if (stop != null) {
                return stop;
            }
            Map<String, Fill> fillMap = dev.logast.apply.FillsFile.forApply(root(), fills);
            String olderJdk = dev.logast.JavaTarget.warning(root());   // #5
            if (olderJdk != null) {
                Out.warn(olderJdk);
            }
            if (!fillMap.isEmpty() && earlier == null && !format.equals("json")) {
                Out.progress("using " + fillMap.size() + " message text(s) from " + (fills != null ? fills : dev.logast.apply.FillsFile.PATH));
            }
            if (!write && !format.equals("json")) {
                Out.progress("DRY RUN: previewing only, no files are changed (run without --dry-run to apply)");
            }
            long t0 = earlier != null ? earlier.t0() : System.nanoTime();
            Policy policy = policy();
            List<Path> cp = classpath();
            Path project = root();   // relative extends: resolve from the project, not from apply's scratch copy (#113)
            dev.logast.RoundScanner scanner = new dev.logast.RoundScanner(cp, root -> {
                Policy p = dev.logast.policy.PolicyLoader.load(root, null, List.of(), project);
                return packs.isEmpty() ? p : p.withPacks(packs);
            });
            Rules.Step untilStep = until == null ? null : Rules.Step.valueOf(until.toUpperCase(java.util.Locale.ROOT));
            Set<Rules.Step> skipSteps = EnumSet.noneOf(Rules.Step.class);
            for (String s : skip) {
                try {
                    skipSteps.add(Rules.Step.valueOf(s.strip().toUpperCase(java.util.Locale.ROOT)));
                } catch (IllegalArgumentException e) {
                    System.err.println("--skip: no step '" + s + "' (steps: " + java.util.Arrays.stream(Rules.Step.values())
                            .map(x -> x.name().toLowerCase(java.util.Locale.ROOT)).collect(java.util.stream.Collectors.joining(", ")) + ")");
                    return 2;
                }
            }
            Applier applier = new Applier(scanner, new FillValidator(policy)).until(untilStep).skip(skipSteps).paths(paths).compileWith(cp)
                    .onInvariants((work, files) -> scanner.lastAnalysis == null ? List.of() : dev.logast.Invariants.check(scanner.lastAnalysis, files))
                    .onFinish(work -> {
                        // #125: the reports from the last scan, while the scratch copy it analysed is still there
                        if (write && scanner.lastAnalysis != null) {
                            try {
                                Reports.write(project, traffic == null ? scanner.lastAnalysis
                                        : scanner.lastAnalysis.withTraffic(dev.logast.Traffic.load(project, traffic)), scanner.lastLifecycle);
                            } catch (IOException e) {
                                throw new java.io.UncheckedIOException(e);
                            }
                        }
                    });
            Applier.Result r;
            // #126: one live line on a terminal ("migrating… step 3/8: log lines"); elsewhere no per-round lines
            try (Out.Progress p = new Out.Progress("~migrating…")) {
                // #293: off a terminal (an agent, CI, a log file) each step gets one line, so a long run is not silent
                boolean stepLines = !Out.terminal();
                applier.onProgress(s -> {
                    String line = ApplySummary.progress(s);
                    p.update(stepLines && line.startsWith("migrating… step ") ? line : "~" + line);
                });
                r = applier.apply(root(), new Applier.Selection(auto, only, fillMap, fills == null), write);
            } catch (IllegalStateException busy) {
                System.err.println("apply: " + busy.getMessage());   // #293: another run on this project
                return 2;
            }
            // #126: both runs of a classpath refresh are one apply
            List<String> applied = new ArrayList<>(earlier == null ? List.of() : earlier.applied());
            applied.addAll(r.applied());
            Set<String> changedSet = new LinkedHashSet<>(earlier == null ? List.of() : earlier.files());
            changedSet.addAll(r.changedFiles());
            List<String> changed = new ArrayList<>(changedSet);
            Map<String, String> rejected = new LinkedHashMap<>(earlier == null ? Map.of() : earlier.rejected());
            rejected.putAll(r.rejected());
            List<String> violations = new ArrayList<>(earlier == null ? List.of() : earlier.violations());
            violations.addAll(r.violations());
            if (write) {   // #124: a fleet member reports what the code is now (a dry run changed nothing)
                FleetScans.snapshot(root(), r.after(), scanner.lastAnalysis, policy);
            }
            dev.logast.apply.FillsFile.Update fu = null;
            dev.logast.apply.FillsFile.Update mu = null;
            if (fills == null) {
                // the automatic file follows the code: created if missing, finished entries out, new ones in;
                // their state is part of the "waiting on you" block (#101)
                // a dry run writes nothing, .logast/decisions included; a --pack run leaves other packs' entries (#275)
                fu = dev.logast.apply.FillsFile.update(root(), (write ? r.after() : r.before()).orders(), ranRule(), write);
                mu = dev.logast.apply.MetricsFile.update(root(), (write ? r.after() : r.before()).orders(), ranRule(), write);
            }
            Written written = writeReports(r, applied, changed, scanner, policy, fu, mu);
            Outcome outcome = new Outcome(r, applied, changed, rejected, violations, header, t0);
            if (format.equals("json")) {
                System.out.println(OrderJson.MAPPER.writeValueAsString(r));
                return r.rejected().isEmpty() ? 0 : 1;
            }
            List<dev.logast.Todo.Item> also = new ArrayList<>();
            Integer again = afterBuildChange(outcome, also);
            if (again != null) {
                return again;
            }
            return summary(outcome, scanner, policy, written, also);
        }

        /**
         * #122: one command on a service with nothing set up: the dirty-tree guard, the first logast.yaml (#124), the
         * classpath (#119); an exit code when the run stops here, else null.
         */
        private Integer setUp(List<String> header) throws IOException {
            if (write && !force && !refreshed) {   // the internal re-run (#117) follows this run's own changes
                List<String> dirty = uncommitted(root());
                if (dirty != null && !dirty.isEmpty()) {
                    Out.warn("logast apply: the git working tree has uncommitted changes (" + String.join(", ", dirty.stream().limit(5).toList())
                            + (dirty.size() > 5 ? ", …" : "") + "). Commit or stash them first, so `git diff` shows only what logast "
                            + "changes. Nothing applied; --force writes anyway, --dry-run previews.");
                    return 2;
                }
            }
            // #124: a logast.yaml with only `fleet:` / `extends:` (after `logast fleet join`) is not set up yet either
            if (policyFile == null && dev.logast.ServiceSetup.needsSetup(root())) {
                if (write) {
                    dev.logast.ServiceSetup.Bootstrap set = dev.logast.ServiceSetup.bootstrap(root(), dev.logast.ServiceSetup.Choices.defaults(), false);
                    header.add("logast.yaml written (" + set.summary() + "; edit it and apply again to change them)");
                } else {
                    Out.progress("no logast.yaml yet: previewing with the built-in defaults (apply without --dry-run writes one)");
                }
            }
            // #119: a Gradle build without a classpath file would resolve no library type, and the compile gate would
            // reject correct fixes with errors that look like a bug: fetch it now (#122), stop only when Gradle fails
            boolean gradleBuild = Files.exists(root().resolve("build.gradle")) || Files.exists(root().resolve("build.gradle.kts"));
            // #249: a build file changed since the classpath was read (an apply reverted with git): read it again, or the
            // classpath still has the dependencies the reverted apply added, and apply would not add them again
            String stale = gradleBuild && classpathFile == null && !withoutClasspath && !refreshed ? Logast.classpathStale(root()) : null;
            if (gradleBuild && classpathFile == null && !withoutClasspath && (stale != null || Logast.classpath(root(), null).isEmpty())) {
                Main.Classpath.Result cpr = Main.Classpath.run(root(), stale != null ? "~" + stale + " changed since the classpath was read: reading it again"
                        : "~reading the classpath with Gradle (nothing is compiled)");
                if (cpr.exitCode() != 0) {
                    Out.warn("no classpath (.logast/classpath*.txt): Gradle failed (exit " + cpr.exitCode() + "), its output is above "
                            + "(often a JDK the build's toolchain asks for is not installed). Nothing applied; fix the build and run "
                            + "again, or --without-classpath applies anyway, with library types unresolved.");
                    return 2;
                }
                header.add((stale != null ? "classpath read again with Gradle, " + stale + " changed since it was read (" : "classpath read with Gradle (")
                        + cpr.entries() + (cpr.entries() == 1 ? " entry)" : " entries)"));
                supportHint(header);
            }
            return null;
        }

        /**
         * One apply's result, merged with the run before a classpath refresh (#126: both are one apply): what was applied,
         * changed, rejected and broke an invariant, what the setup did (the header), and when it started.
         */
        private record Outcome(Applier.Result r, List<String> applied, List<String> changed, Map<String, String> rejected,
                               List<String> violations, List<String> header, long t0) {
        }

        /**
         * What an apply wrote besides the code: the log-call ledger (#72), the per-step diffs, and the state of
         * messages.yaml and metrics.yaml (#101; null when --fills names another file).
         */
        private record Written(List<dev.logast.LogLedger.Entry> ledger, List<String> stepFiles, dev.logast.apply.FillsFile.Update fu,
                               dev.logast.apply.FillsFile.Update mu) {
        }

        /** The last-apply record (#101), the applied hashes (#131), the ledger, the round trace and one diff per step. */
        private Written writeReports(Applier.Result r, List<String> applied, List<String> changed, dev.logast.RoundScanner scanner,
                                     Policy policy, dev.logast.apply.FillsFile.Update fu, dev.logast.apply.FillsFile.Update mu)
                throws IOException {
            // what the last apply that changed files did: a later scan starts with its first line ("Applied earlier",
            // #101); the ids and files follow (#126: the summary lists them only with --verbose)
            if (write && !applied.isEmpty() && !changed.isEmpty()) {
                Files.createDirectories(root().resolve(LAST_APPLY).getParent());
                Files.writeString(root().resolve(LAST_APPLY), dev.logast.Todo.plural(applied.size(), "change") + " in "
                        + dev.logast.Todo.plural(changed.size(), "file") + "\n"
                        + "applied (" + applied.size() + "): " + String.join(" ", applied) + "\n"
                        + "files (" + changed.size() + "): " + String.join(", ", changed) + "\n");
            }
            if (write && !changed.isEmpty()) {   // #131: both runs of a refresh; this one hashes what they wrote last
                recordApplied(root(), changed);
            }
            // every log call of the result, with the ones this run fixed (#72); the logging pack's report, as in scan
            List<dev.logast.LogLedger.Entry> ledger = null;
            if (write && scanner.lastAnalysis != null && policy.packSelected("logging")) {
                ledger = scanner.lastAnalysis.ledger.write(root(), scanner.lastAnalysis, r.after().orders(), r.fixed());
            }
            // what each round applied and what its rescan brought up: where extra rounds come from
            if (!r.trace().isEmpty()) {
                Files.createDirectories(root().resolve(dev.logast.LogastDir.REPORTS));
                Files.write(root().resolve(dev.logast.LogastDir.APPLY_TRACE), r.trace());
            }
            // one diff per pipeline step, applicable in order: review or drop a whole kind of change at once
            Path stepsDir = root().resolve(dev.logast.LogastDir.STEPS);
            if (Files.exists(stepsDir) && !r.stepDiffs().isEmpty()) {   // a run that changes nothing keeps the last ones
                try (var old = Files.list(stepsDir)) {
                    for (Path p : old.toList()) {
                        Files.delete(p);
                    }
                }
            }
            List<String> stepFiles = new ArrayList<>();
            if (!r.stepDiffs().isEmpty()) {
                Files.createDirectories(stepsDir);
                int i = 0;
                for (var e : r.stepDiffs().entrySet()) {
                    String file = String.format("%02d-%s.diff", ++i, e.getKey());
                    Files.writeString(stepsDir.resolve(file), e.getValue());
                    stepFiles.add(file);
                }
            }
            return new Written(ledger, stepFiles, fu, mu);
        }

        /**
         * Dependencies changed (a build file, or a script it applies, #80): the classpath is refreshed and (#117) one more
         * run with it settles the setup decisions that depend on the new types; its exit code, else null.
         */
        private Integer afterBuildChange(Outcome o, List<dev.logast.Todo.Item> also) throws IOException {
            Applier.Result r = o.r();
            List<String> applied = o.applied();
            List<String> changed = o.changed();
            Map<String, String> rejected = o.rejected();
            List<String> violations = o.violations();
            List<String> header = o.header();
            long t0 = o.t0();
            boolean buildChanged = write && r.changedFiles().stream().anyMatch(fn -> fn.endsWith(".gradle") || fn.endsWith(".gradle.kts"));
            if (buildChanged && classpathFile != null) {
                // #122: the caller owns the classpath (the Gradle plugin's logastApply runs inside a build): no nested
                // Gradle run here; the caller's next apply resolves the new dependencies
                also.add(new dev.logast.Todo.Item("apply again (./gradlew logastApply)",
                        "dependencies changed; the classpath came from --classpath-file, so the new types are resolved on the next apply"));
            } else if (buildChanged) {
                // a build file, or a script it applies (gradle/dependencies.gradle, #80)
                // dependencies changed: refresh the classpath right away, so the next scan sees the new types
                Main.Classpath.Result cpr = Main.Classpath.run(root(), "~dependencies changed: refreshing the classpath with Gradle");
                if (cpr.exitCode() == 0) {
                    if (!refreshed) {
                        // #117: the new dependencies bring types (OpenTelemetry, Micrometer, …) that setup decisions depend
                        // on; another round with the refreshed classpath settles them now, not on the next apply. #126: it
                        // gets what this run did, and its summary is the one summary of both
                        header.add("classpath refreshed for the new dependencies (" + cpr.entries()
                                + " entries), one more round with the refreshed classpath");
                        List<String> again = new ArrayList<>(spec.commandLine().getParseResult().originalArgs());   // "apply", …
                        again.add("--refreshed");
                        carried = new Carried(applied, changed, rejected, violations, header, t0);
                        int rc = new CommandLine(new Main()).execute(again.toArray(String[]::new));
                        return r.rejected().isEmpty() ? rc : 1;
                    }
                } else {
                    also.add(new dev.logast.Todo.Item("logast classpath .", "dependencies changed and Gradle failed (exit " + cpr.exitCode()
                            + "): fix the build, then refresh the classpath"));
                }
            }
            return null;
        }

        /** The run's summary: what was set up, the change counts, rejections and checks, then what is left (#126). */
        private int summary(Outcome o, dev.logast.RoundScanner scanner, Policy policy, Written written, List<dev.logast.Todo.Item> also) {
            Applier.Result r = o.r();
            List<String> applied = o.applied();
            List<String> changed = o.changed();
            Map<String, String> rejected = o.rejected();
            List<String> violations = o.violations();
            List<String> header = o.header();
            long t0 = o.t0();
            List<String> stepFiles = written.stepFiles();
            List<dev.logast.LogLedger.Entry> ledger = written.ledger();
            dev.logast.apply.FillsFile.Update fu = written.fu();
            dev.logast.apply.FillsFile.Update mu = written.mu();
            if (!header.isEmpty()) {
                System.out.println(Ansi.bold("Set up: ") + String.join("; ", header));
                System.out.println();
            }
            if (!write && !r.diff().isEmpty()) {
                System.out.print(colorDiff(r.diff()));
                System.out.println();
            }
            String counts = dev.logast.Todo.plural(applied.size(), "change") + " in " + dev.logast.Todo.plural(changed.size(), "file");
            if (write && !changed.isEmpty()) {
                System.out.println(Ansi.ok() + " " + Ansi.bold(counts) + Ansi.dim(" (" + Out.seconds(t0) + ")"));
                String support = scanner.lastAnalysis == null || !policy.support().vendor() ? null : scanner.lastAnalysis.supportPackage;
                ApplySummary.kinds(applied, changed, support).forEach(l -> System.out.println("  " + l));
                System.out.println("  review: " + Ansi.cyan("git diff") + Ansi.dim("   (one diff per kind: .logast/reports/steps/)"));
            } else if (write) {
                System.out.println(Ansi.ok() + " nothing to change: the code has everything logast applies itself"
                        + Ansi.dim(" (" + Out.seconds(t0) + ")"));
            } else {
                System.out.println(Ansi.bold("would apply " + counts) + Ansi.dim(" (" + Out.seconds(t0) + ")"));
                ApplySummary.kinds(applied, changed, null).forEach(l -> System.out.println("  " + l));
            }
            if (verbose) {
                System.out.println(Ansi.dim((write ? "applied (" : "would apply (") + applied.size() + "): " + String.join(" ", applied)));
                System.out.println(Ansi.dim("files (" + changed.size() + "): " + String.join(", ", changed) + "  ·  " + r.rounds() + " rounds"));
                if (!r.resolvedIndirectly().isEmpty()) {
                    System.out.println(Ansi.dim("resolved by other changes: " + String.join(" ", r.resolvedIndirectly())));
                }
                if (!stepFiles.isEmpty()) {
                    System.out.println(Ansi.dim("per step (apply in order, drop what you don't want): .logast/reports/steps/ "
                            + String.join(" ", stepFiles)));
                }
            }
            rejected.forEach((id, why) -> System.out.println(Ansi.red("REJECTED " + id) + ": " + why));
            if (r.before() != null && r.before().stats() != null && r.before().stats().logCallsByName() > 0) {
                System.out.println(Ansi.warn(loggersByNameLine(r.before().stats().logCallsByName())));
            }
            if (!violations.isEmpty()) {
                System.out.println(Ansi.warn("CHECK FAILED (" + violations.size() + "): the result breaks a rule every migrated file must follow"));
                violations.forEach(v -> System.out.println("  " + v));
            }
            dev.logast.Todo.Item next = null;
            if (!write && !applied.isEmpty()) {
                next = new dev.logast.Todo.Item("logast apply ." + (fills != null ? " --fills " + fills : "")
                        + (only.isEmpty() ? "" : " --only " + String.join(",", only)),
                        "make these " + applied.size() + " changes");
            }
            if (!violations.isEmpty()) {
                also.add(new dev.logast.Todo.Item("read the CHECK FAILED lines above", violations.size() + " places break a migration invariant: "
                        + "a logast bug, please report the shape of the code"));
            }
            if (!rejected.isEmpty()) {
                also.add(new dev.logast.Todo.Item("read the REJECTED lines above", (rejected.size() == 1 ? "1 fix" : rejected.size() + " fixes")
                        + " could not be applied; each says why"));
            }
            // what is left: optional, in plain words (#126); the follow-up checks one line each
            dev.logast.orders.ScanResult left = write ? r.after() : r.before();
            dev.logast.Todo todo = dev.logast.Todo.of(root(), left, policy, scanner.lastAnalysis, applied, write, write);
            also.addAll(todo.also());
            printBlocks(todo.worthALook(new dev.logast.Todo.View(next, "logast scan .", fu, mu)),
                    ledgerLines(ledger, List.of()), dev.logast.Todo.alsoShort(also));
            if (write) {
                Reports.printWritten();
            }
            return exitCode(rejected, violations);
        }

        /** #288: a run with REJECTED changes or CHECK FAILED results is not a success, whatever it applied. */
        static int exitCode(Map<String, String> rejected, List<String> violations) {
            return rejected.isEmpty() && violations.isEmpty() ? 0 : 1;
        }
    }

    /**
     * #122: the files under {@code root} with uncommitted changes git tracks (staged or not; untracked files, logast's
     * own .logast/ and the files its setup writes, .gitignore and logast.yaml, are left out: init then apply works; #131: so is a file whose content is still what an apply wrote, see {@link #recordApplied}), or null when {@code root} is not in a git work tree or git is missing.
     */
    static List<String> uncommitted(Path root) {
        try {
            Process inside = new ProcessBuilder("git", "rev-parse", "--is-inside-work-tree").directory(root.toFile())
                    .redirectErrorStream(true).start();
            String answer = new String(inside.getInputStream().readAllBytes()).strip();
            if (inside.waitFor() != 0 || !answer.equals("true")) {
                return null;
            }
            // what logast's own setup writes (init and apply's first run: .gitignore, logast.yaml) does not count
            Process status = new ProcessBuilder("git", "status", "--porcelain", "--untracked-files=no", "--", ".", ":(exclude).logast",
                            ":(exclude).gitignore", ":(exclude)logast.yaml", ":(exclude)logast.yml")
                    .directory(root.toFile()).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            List<String> out = new String(status.getInputStream().readAllBytes()).lines()
                    .filter(l -> l.length() > 3).map(l -> l.substring(3)).toList();
            if (status.waitFor() != 0) {
                return null;
            }
            // #131: a file whose content is still what an apply wrote is logast's own change (apply, review, apply again)
            Map<String, String> wrote = appliedState(root);
            if (wrote.isEmpty() || out.isEmpty()) {
                return out;
            }
            Process prefixOf = new ProcessBuilder("git", "rev-parse", "--show-prefix").directory(root.toFile())
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            String prefix = new String(prefixOf.getInputStream().readAllBytes()).strip();   // git's paths start at the top
            if (prefixOf.waitFor() != 0) {
                return out;
            }
            return out.stream().filter(f -> {
                String rel = f.startsWith(prefix) ? f.substring(prefix.length()) : null;
                return rel == null || !wrote.containsKey(rel) || !wrote.get(rel).equals(sha256(root.resolve(rel)));
            }).toList();
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /**
     * #131: records the content each of {@code files} has now (SHA-256, "-" for a file apply deleted), merged into the
     * earlier record: a later apply updates and extends it. {@link #uncommitted} leaves such a file out while it is unchanged.
     */
    static void recordApplied(Path root, java.util.Collection<String> files) throws IOException {
        Map<String, String> state = new java.util.TreeMap<>(appliedState(root));
        for (String f : files) {
            state.put(f, sha256(root.resolve(f)));
        }
        Path file = root.resolve(dev.logast.LogastDir.APPLIED_STATE);
        Files.createDirectories(file.getParent());
        StringBuilder sb = new StringBuilder("# what logast apply wrote (sha-256 of the content after the write); "
                + "while a file still has it, its change is logast's and does not block the next apply\n");
        state.forEach((f, h) -> sb.append(h).append("  ").append(f).append('\n'));
        Files.writeString(file, sb.toString());
    }

    /** #131: file → SHA-256 of the content apply wrote; empty without a record (an earlier logast). */
    static Map<String, String> appliedState(Path root) {
        Path file = root.resolve(dev.logast.LogastDir.APPLIED_STATE);
        Map<String, String> state = new LinkedHashMap<>();
        try {
            if (Files.isRegularFile(file)) {
                for (String line : Files.readAllLines(file)) {
                    int sep = line.indexOf("  ");
                    if (!line.startsWith("#") && sep > 0) {
                        state.put(line.substring(sep + 2), line.substring(0, sep));
                    }
                }
            }
        } catch (IOException e) {
            return Map.of();
        }
        return state;
    }

    /** The SHA-256 of a file's bytes in hex, "-" when it does not exist, "?" when it can't be read. */
    static String sha256(Path p) {
        try {
            if (!Files.isRegularFile(p)) {
                return "-";
            }
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(p)));
        } catch (IOException e) {
            return "?";
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Where apply records what it changed, for the "Applied earlier" line of a later scan (#101). */
    static final String LAST_APPLY = dev.logast.LogastDir.LAST_APPLY;

    /** "Applied earlier: 38 changes in 12 files (git diff to review)" when an apply left its record; else nothing. */
    static List<String> appliedEarlier(Path root) {
        try {
            Path f = root.resolve(LAST_APPLY);
            if (!Files.isRegularFile(f)) {
                return List.of();
            }
            String what = Files.readString(f).lines().findFirst().orElse("").strip();   // #126: ids and files follow
            return what.isEmpty() ? List.of() : List.of(Ansi.bold("Applied earlier: ") + what + Ansi.dim(" (git diff to review)"));
        } catch (IOException e) {
            return List.of();
        }
    }

    /** The one coverage line of a run (#72, #101), and the places-that-should-log line when given; nothing without a ledger. */
    static List<String> ledgerLines(List<dev.logast.LogLedger.Entry> ledger, List<String> places) {
        List<String> out = new ArrayList<>();
        if (ledger != null) {
            out.add(dev.logast.LogLedger.line(ledger));
        }
        out.addAll(places);
        return out;
    }

    /** The footer blocks in order, an empty line before each non-empty one. */
    @SafeVarargs
    static void printBlocks(List<String>... blocks) {
        for (List<String> b : blocks) {
            if (!b.isEmpty()) {
                System.out.println();
                b.forEach(System.out::println);
            }
        }
    }

    static String loggersByNameLine(int n) {
        return n + " log call(s) recognised by name only: the logger's type did not resolve, so types around them are "
                + "missing too. Lombok on the classpath? Run `logast classpath .` (or add the Lombok jar to .logast/classpath*.txt).";
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

    @Command(name = "classpath", hidden = true, description = "Run Gradle once to write <root>/.logast/classpath-*.txt (needed for type resolution)")
    static class Classpath implements Callable<Integer> {
        @Parameters(index = "0", defaultValue = ".")
        Path root;

        /**
         * Result of one Gradle run: exit code, classpath entries, whether compiled classes were found, and what Gradle
         * printed (#126: shown only when it failed; the JVM warnings of a run that worked are noise).
         */
        record Result(int exitCode, int entries, boolean compiledClasses, String output) {
            Result(int exitCode, int entries, boolean compiledClasses) {
                this(exitCode, entries, compiledClasses, "");
            }

            String whatWentWrong() {
                return Classpath.whatWentWrong(output);
            }

            /** Gradle's own output, on stderr, when it failed; nothing when it worked. */
            Result printIfFailed() {
                if (exitCode != 0 && !output.isBlank() && Out.Progress.quiet) {   // #196: a fleet run: one line, not Gradle's dump
                    Out.warn("Gradle could not read the classpath: " + whatWentWrong() + " (scanned from the source alone; "
                            + "`./gradlew` in the repo shows the whole error)");
                    return this;
                }
                if (exitCode != 0 && !output.isBlank()) {
                    System.err.println(output.stripTrailing());
                    if (brokenJar(output)) {
                        Out.warn(BROKEN_JAR);
                    }
                }
                return this;
            }
        }

        /** #196: Gradle's failure in a line: the line after "What went wrong:", else its last line. */
        static String whatWentWrong(String output) {
            List<String> lines = output.lines().map(String::strip).filter(l -> !l.isEmpty()).toList();
            for (int i = 0; i < lines.size() - 1; i++) {
                if (lines.get(i).startsWith("* What went wrong")) {
                    String w = lines.get(i + 1);
                    return w.length() > 200 ? w.substring(0, 199) + "…" : w;
                }
            }
            return lines.isEmpty() ? "" : lines.getLast();
        }

        /** The hint for a Gradle failure on an empty or broken jar in a local cache. */
        static final String BROKEN_JAR = "a jar Gradle uses is empty or broken (an interrupted download?): find ~/.m2/repository "
                + "~/.gradle/caches/modules-2 -name '*.jar' -size 0, delete what it lists, run again";

        /** Gradle failed reading a jar: an empty or truncated file in ~/.m2 or the Gradle cache. */
        static boolean brokenJar(String output) {
            return output.contains("zip file is empty") || output.contains("ZipException") || output.contains("error in opening zip file");
        }

        /** Runs Gradle with the classpath init script (compiles nothing); its output is captured, not printed. */
        static Result write(Path r) throws IOException, InterruptedException {
            return write(r, null);
        }

        /**
         * @param outDir #132: where the classpath files go instead of {@code <r>/.logast} (a fleet's added member: the
         *               fleet folder); Gradle's own project cache goes there too, so the repo stays as it was. Null: .logast
         */
        static Result write(Path r, Path outDir) throws IOException, InterruptedException {
            Path init = Files.createTempFile("logast-classpath", ".init.gradle");
            Files.writeString(init, initScript());
            Path wrapper = r.resolve(System.getProperty("os.name").toLowerCase().contains("win") ? "gradlew.bat" : "gradlew");
            String gradle = Files.exists(wrapper) ? wrapper.toString() : "gradle";
            List<String> command = new ArrayList<>(List.of(gradle, "-q", "--no-configuration-cache", "--init-script", init.toString()));
            if (outDir != null) {
                Files.createDirectories(outDir);
                command.addAll(List.of("--project-cache-dir", outDir.resolve("gradle-cache").toString(),
                        "-Plogast.outputDir=" + outDir.toAbsolutePath().normalize()));
            }
            command.add("logastClasspath");
            Process p = new ProcessBuilder(command)
                    .directory(r.toFile()).redirectErrorStream(true).redirectInput(ProcessBuilder.Redirect.INHERIT).start();
            String output = new String(p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            int rc = p.waitFor();
            Files.deleteIfExists(init);
            List<Path> cp = rc != 0 ? List.of() : outDir != null ? Logast.classpathIn(outDir) : Logast.classpath(r, null);
            return new Result(rc, cp.size(), dev.logast.parse.ProjectParser.hasCompiledClasses(cp), output);
        }

        /** {@link #write} under a progress line ({@code label}); Gradle's output printed only when it failed. */
        static Result run(Path r, String label) throws IOException {
            Result res;
            try (Out.Progress p = new Out.Progress(label)) {
                res = write(r);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                res = new Result(130, 0, false);
            }
            return res.printIfFailed();   // after the progress line is gone
        }

        /** The classpath init script; its support check (#127) looks for the logging-support of this logast's release. */
        static String initScript() throws IOException {
            try (InputStream in = Main.class.getResourceAsStream("/logast-classpath.init.gradle")) {
                return new String(Objects.requireNonNull(in).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
                        .replace("@LOGAST_SUPPORT_VERSION@", Policy.Support.releaseVersion());
            }
        }

        @Override
        public Integer call() throws IOException, InterruptedException {
            Path r = root.toAbsolutePath().normalize();
            long t0 = System.nanoTime();
            Out.progress("running Gradle to read the classpath (nothing is compiled)…");
            Result res = write(r).printIfFailed();
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

    @Command(name = "traffic", hidden = true, description = "Write .logast/decisions/traffic.yaml listing every entrypoint (rates empty to fill in; existing values kept)")
    static class TrafficCmd extends Common implements Callable<Integer> {
        @Override
        public Integer call() throws IOException {
            Analysis a = Logast.analyse(root(), classpath(), policy());
            Path file = traffic != null ? traffic : root().resolve(dev.logast.LogastDir.TRAFFIC);
            dev.logast.Traffic existing = (Files.exists(file) ? dev.logast.Traffic.load(root(), file) : dev.logast.Traffic.none())
                    .withSchedules(a.traffic.schedules());
            Files.createDirectories(file.toAbsolutePath().getParent());
            Files.writeString(file, dev.logast.Traffic.template(a.entrypoints, existing));
            long filled = a.entrypoints.stream().filter(ep -> existing.perDay(ep.label()) != null).count();
            System.out.println("traffic: " + a.entrypoints.size() + " entrypoints in " + file + " (" + filled + " with rates)");
            return 0;
        }
    }

    @Command(name = "vocabulary", hidden = true, description = "Harvest a draft vocabulary from the code (MDC keys, [k=v] keys in log messages, "
            + "id-like variables) the policy does not cover yet: one term per key as written, hints in comments, to organise and "
            + "paste into logast.yaml")
    static class VocabularyCmd extends Common implements Callable<Integer> {
        @Option(names = "--min-declared", defaultValue = "3",
                description = "Identifiers that are never logged or put in MDC need this many declarations (default 3)")
        int minDeclared;
        @Option(names = "--all", description = "Ignore the current policy: show everything, including terms it already defines")
        boolean all;
        @Option(names = "--central", description = "Folder of the central vocabulary (e.g. ~/.logast): harvest every given "
                + "service into <dir>/" + dev.logast.CentralVocabulary.WORKSHEET + " (evidence + your decisions, kept across runs) "
                + "and publish the decided terms to <dir>/" + dev.logast.CentralVocabulary.COMPANY
                + ". For a named fleet: `logast fleet <name> --vocabulary` (no paths, from the members' snapshots)")
        Path central;
        @Option(names = "--prune", description = "Remove from each service's logast.yaml the vocabulary entries an extended "
                + "policy (company.yaml, also a fleet's: `fleet: <name>`) now defines in full")
        boolean prune;
        @Parameters(index = "1..*", description = "More service roots (with --central or --prune)")
        List<Path> more = new ArrayList<>();

        @Override
        public Integer call() {
            if (central != null || prune) {
                List<Path> roots = new ArrayList<>();
                roots.add(root());
                more.forEach(m -> roots.add(m.toAbsolutePath().normalize()));
                return central != null ? central(roots) : prune(roots);
            }
            if (!more.isEmpty()) {
                Out.warn("vocabulary: more than one root needs --central or --prune");
                return 2;
            }
            Policy p = policy();
            dev.logast.VocabularyHarvest.Result h = dev.logast.VocabularyHarvest.run(root(), all ? null : p, p.logging().style(), minDeclared);
            System.out.print(dev.logast.VocabularyHarvest.yaml(h));
            return 0;
        }

        private int central(List<Path> roots) {
            Path dir = Path.of(central.toString().replaceFirst("^~(?=/|$)", System.getProperty("user.home"))).toAbsolutePath().normalize();
            Map<String, Path> services = new java.util.TreeMap<>();
            for (Path r : roots) {
                String name = r.getFileName().toString();
                for (int i = 2; services.containsKey(name); i++) {
                    name = r.getFileName() + "-" + i;
                }
                services.put(name, r);
            }
            dev.logast.CentralVocabulary.Result r = dev.logast.CentralVocabulary.run(dir, services);
            r.summary().forEach(System.out::println);
            System.out.println("worksheet: " + dir.resolve(dev.logast.CentralVocabulary.WORKSHEET) + " (decide there)");
            System.out.println("company:   " + dir.resolve(dev.logast.CentralVocabulary.COMPANY) + " (" + r.terms()
                    + (r.terms() == 1 ? " term" : " terms") + ", generated)");
            return 0;
        }

        private int prune(List<Path> roots) {
            for (Path r : roots) {
                migrate(r);
                List<dev.logast.VocabularyPrune.Covered> removed = dev.logast.VocabularyPrune.prune(r);
                System.out.println(r.getFileName() + ": " + (removed.isEmpty() ? "nothing to prune"
                        : "removed " + String.join(", ", removed.stream().map(c -> c.term().equals(c.by()) ? c.term()
                        : c.term() + " (" + c.by() + ")").toList())));
                dev.logast.VocabularyPrune.check(r).differs().forEach(d -> System.out.println("  kept " + d.term()
                        + ": differs from " + d.by() + " (" + d.what() + ")"));
            }
            return 0;
        }
    }

    /** #125: the one stderr line an old command name prints before it runs; stdout stays as it was. */
    static void moved(String old, String now) {
        Out.progress("`logast " + old + "` is now `logast " + now + "` (this name goes in a later release)");
    }

    @Command(name = "metrics", description = "What the service measures and what it does not: exporters, service/version "
            + "identity, the metric (and tag values) covering each entrypoint, meters in code, and outcomes the logs report "
            + "that no metric counts (also in .logast/reports/metrics.md after every scan and apply)")
    static class ExplainMetrics extends Common implements Callable<Integer> {
        @Option(names = "--format", defaultValue = "map", description = "map (default) | json (the same, for tools and LLMs) | table (meters only)")
        String format;

        @Override
        public Integer call() throws IOException {
            Analysis a = Logast.analyse(root(), classpath(), policy()).withTraffic(dev.logast.Traffic.load(root(), traffic));
            System.out.print(Reports.metrics(a, format));
            return 0;
        }
    }

    @Command(name = "metrics", hidden = true, description = "Now `logast explain metrics`")
    static class MetricsCmd extends ExplainMetrics {
        @Override
        public Integer call() throws IOException {
            moved("metrics", "explain metrics");
            return super.call();
        }
    }

    @Command(name = "queries", description = "Ready-made queries for the service's metrics and logs: PromQL, NRQL and "
            + "Splunk SPL per entrypoint, business outcome and MDC key (also in .logast/reports/queries.md after every scan "
            + "and apply; backends and the Splunk index: queries: in logast.yaml)")
    static class ExplainQueries extends Common implements Callable<Integer> {
        @Override
        public Integer call() {
            Analysis a = Logast.analyse(root(), classpath(), policy());
            dev.logast.queries.QueryWriter.Result r = Reports.queries(a);
            System.out.print(r.text());
            r.hints().forEach(Out::warn);
            return 0;
        }
    }

    @Command(name = "queries", hidden = true, description = "Now `logast explain queries`: writes <root>/.logast/reports/queries.md")
    static class QueriesCmd extends Common implements Callable<Integer> {
        @Option(names = "--stdout", description = "Print the queries instead of writing .logast/reports/queries.md")
        boolean stdout;

        @Override
        public Integer call() throws IOException {
            moved("queries", "explain queries");
            Analysis a = Logast.analyse(root(), classpath(), policy());
            dev.logast.queries.QueryWriter.Result r = Reports.queries(a);
            if (stdout) {
                System.out.print(r.text());
                return 0;
            }
            Path file = root().resolve(dev.logast.LogastDir.QUERIES);
            Files.createDirectories(file.getParent());
            Files.writeString(file, r.text());
            System.out.println("queries: " + r.summary() + " in " + root().relativize(file));
            r.hints().forEach(h -> Out.warn(h));
            return 0;
        }
    }

    @Command(name = "mdc", description = "Why an MDC key is (or is not) scoped where it is: every method that logs it, "
            + "the decision and the reason. `explain mdc <key>` or `explain mdc <root> <key>`")
    static class ExplainMdc extends Common implements Callable<Integer> {
        @CommandLine.Parameters(index = "1", arity = "0..1", description = "MDC key, e.g. pledgeId")
        String key;

        /** One argument is the key, in the current folder; two are root and key. */
        void resolveArguments() {
            if (key == null) {
                key = root.toString();
                root = Path.of(".");
            }
        }

        @Override
        public Integer call() throws IOException {
            resolveArguments();
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

    @Command(name = "why", hidden = true, description = "Now `logast explain mdc`")
    static class WhyCmd extends ExplainMdc {
        @Override
        public Integer call() throws IOException {
            moved("why", "explain mdc");
            return super.call();
        }
    }

    @Command(name = "shape", hidden = true, description = "Export the code's shape with its own names and words replaced (to send a "
            + "reproduction without the content): <root>/.logast/shape/, mapping back in <root>/.logast/shape-mapping.txt")
    static class ShapeCmd extends Common implements Callable<Integer> {
        @Option(names = "--public-group", split = ",", description = "A library group/package whose names may stay as they "
                + "are, besides the known public ones (Spring, SLF4J, Jakarta, ...); every other library is replaced like the code")
        List<String> publicGroups = new ArrayList<>();

        @Override
        public Integer call() throws IOException {
            List<Path> cp = classpath();
            if (cp.isEmpty()) {
                Out.warn("no classpath (.logast/classpath*.txt): run `logast classpath .` first, or the export misses how "
                        + "Lombok and library calls connect your code");
            }
            Analysis a = Logast.analyse(root(), cp, policy());
            var r = dev.logast.ShapeExport.write(a, root().resolve(".logast/shape"), root().resolve(".logast/shape-mapping.txt"),
                    publicGroups);
            System.out.println("wrote " + r.files() + " files to " + root().relativize(r.dir()) + " (" + r.words() + " words replaced)");
            System.out.println("read it before sending it; the mapping back stays here: " + root().relativize(r.mapping()));
            return 0;
        }
    }

    @Command(name = "lifecycle", description = "Every flow's lifecycle lines: kind, which lines exist, who owns the start and "
            + "the outcome, and what logast will add; plus implementation families that log unevenly (also in "
            + ".logast/reports/lifecycle.txt after every scan and apply)")
    static class ExplainLifecycle extends Common implements Callable<Integer> {
        @Override
        public Integer call() {
            Analysis a = Logast.analyse(root(), classpath(), policy());
            Rules.scan(a);
            System.out.print(Reports.lifecycle(a, Rules.lastLifecycle));
            return 0;
        }
    }

    @Command(name = "lifecycle", hidden = true, description = "Now `logast explain lifecycle`")
    static class LifecycleCmd extends ExplainLifecycle {
        @Override
        public Integer call() {
            moved("lifecycle", "explain lifecycle");
            return super.call();
        }
    }

    @Command(name = "rules", description = "List rule ids with their packs")
    static class ExplainRules implements Callable<Integer> {
        @Override
        public Integer call() {
            Rules.all().forEach(r -> System.out.printf("%-26s %s%n", r, String.join(",", new TreeSet<>(Rules.packsOf(r)))));
            return 0;
        }
    }

    @Command(name = "rules", hidden = true, description = "Now `logast explain rules`")
    static class RulesCmd extends ExplainRules {
        @Override
        public Integer call() {
            moved("rules", "explain rules");
            return super.call();
        }
    }

    @Command(name = "policy", description = "Print the effective policy (all layers merged) and its overrides")
    static class ExplainPolicy extends Common implements Callable<Integer> {
        @Option(names = "--check", description = "For CI: check the policy against the code (names that match nothing, "
                + "unknown ids in logast:ignore comments) instead of printing it. Exit 0: fine; 1: warnings; 2: errors")
        boolean check;

        @Override
        public Integer call() throws IOException {
            if (check) {
                Policy p;
                try {
                    p = policy();   // load-time errors (unknown packs, rule ids, regexes, …) throw here
                } catch (IllegalArgumentException e) {
                    Out.warn(e.getMessage());
                    return 2;
                }
                Analysis a = Logast.analyse(root(), classpath(), p);
                List<String> warnings = new ArrayList<>(p.warnings());
                warnings.addAll(dev.logast.rules.PolicyUsage.warnings(a));
                warnings.forEach(w -> System.out.println("warning: " + w));
                System.out.println(warnings.isEmpty() ? "policy ok" : warnings.size() + " warning(s)");
                return warnings.isEmpty() ? 0 : 1;
            }
            System.out.println(new com.fasterxml.jackson.databind.ObjectMapper(new com.fasterxml.jackson.dataformat.yaml.YAMLFactory())
                    .setSerializationInclusion(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_EMPTY)
                    .writeValueAsString(policy()));
            return 0;
        }
    }

    @Command(name = "policy", hidden = true, description = "Now `logast explain policy`")
    static class PolicyCmd extends ExplainPolicy {
        @Override
        public Integer call() throws IOException {
            moved("policy", "explain policy");
            return super.call();
        }
    }
}
