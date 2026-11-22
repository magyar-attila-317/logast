package dev.logast.cli;

import dev.logast.Analysis;
import dev.logast.CentralVocabulary;
import dev.logast.Logast;
import dev.logast.LogastDir;
import dev.logast.fleet.CompanyPolicy;
import dev.logast.fleet.FleetHome;
import dev.logast.fleet.FleetExtends;
import dev.logast.fleet.FleetLinks;
import dev.logast.fleet.FleetRepos;
import dev.logast.fleet.FleetScaffold;
import dev.logast.fleet.FleetReport;
import dev.logast.fleet.FleetTidy;
import dev.logast.fleet.NamedFleet;
import dev.logast.orders.Ansi;
import dev.logast.orders.OrderJson;
import dev.logast.orders.ScanResult;
import dev.logast.policy.Policy;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;

@Command(name = "fleet", description = "Named fleets (#124): services join a fleet (`logast fleet join <name>`), every scan and "
        + "apply of a member leaves a snapshot in the fleet folder, and `logast fleet <name>` compares them without rescanning: "
        + "scores, trace breaks between services, one outcome under several metric names, vocabulary conflicts. "
        + "`logast fleet add <name> <repos…>` registers repos without touching them (#132): their scans write only to the "
        + "fleet folder. The earlier form `logast fleet <service roots…>` still scans the given roots.",
        subcommands = {FleetCommands.Create.class, FleetCommands.Init.class, FleetCommands.Join.class, FleetCommands.Leave.class,
                FleetCommands.Add.class, FleetCommands.Remove.class, FleetCommands.ListFleets.class, FleetCommands.Tidy.class, FleetRollout.TagPolicy.class, FleetRollout.Run.class})
final class Fleet implements Callable<Integer> {
    @Parameters(description = "A fleet's name; or (the earlier form) service roots, or a text file with one root per line (prefix it with @)")
    List<String> services = new ArrayList<>();
    @Option(names = "--out", description = "Report folder for the HTML page and its JSON snapshots, kept for trends "
            + "(default: logast-fleet; for a named fleet <fleet folder>/report)")
    Path out;
    @Option(names = "--vocabulary", description = "Named fleet: write the fleet's vocabulary worksheet from the members' snapshots "
            + "and regenerate its company.yaml (as `vocabulary --central` did). With service roots: per service, the keys in its "
            + "code that the vocabulary makes aliases, which apply would rename (MDC_KEY_ALIAS)")
    boolean vocabulary;
    @Option(names = "--central", description = "With --vocabulary and service roots: the central vocabulary folder; its company.yaml "
            + "counts as extended by every service, so a change shows before the services take it")
    Path central;
    @Option(names = "--classpath-file", description = "One classpath file for every service (default: each service's .logast/classpath*.txt)")
    Path classpathFile;
    @Option(names = "--no-builds", description = "Named fleet: scan added members without running their Gradle (source only; "
            + "for CI, where a member's build script must not run next to a token for every repo)")
    boolean noBuilds;
    @Option(names = "--kb", description = "Named fleet: also export every member's facts (any repo layout) into <fleet>/kb/<member>/ "
            + "and apply kb/mappings/*.yaml into kb/mapped/: the fleet's knowledge base for logast mcp (#182)")
    boolean kb;
    @Option(names = "--kb-full", description = "Export every member's knowledge base, also those unchanged since their last export, "
            + "with everything (implies --kb and --flyway)")
    boolean kbFull;
    @Option(names = "--threads", defaultValue = "1", description = "With --refresh: repos scanned (and exported) at a time "
            + "(#226; each analysis can take a few GB: raise it as memory allows; default: ${DEFAULT-VALUE})")
    int threads;
    @Option(names = "--kb-threads", defaultValue = "2", description = "With --kb: members exported at a time (one analysis can "
            + "take a few GB; default: ${DEFAULT-VALUE})")
    int kbThreads;
    @Option(names = "--flyway", description = "With --kb: also the tables (and --rows) each member's Flyway migrations create, "
            + "per environment: runs them in a throwaway PostgreSQL (needs Docker)")
    boolean flyway;
    @Option(names = "--rows", split = ",", description = "With --kb --flyway: tables whose rows become facts")
    List<String> rows = new java.util.ArrayList<>();
    @Option(names = "--postgres-image", description = "With --kb --flyway: the PostgreSQL image (default: postgres:16-alpine)")
    String postgresImage;
    @Option(names = "--refresh", description = "Named fleet: rescan every member first, and update the clones of repos.yaml "
            + "(default: the snapshots their last scan or apply left; a member with new commits since its snapshot is rescanned anyway); "
            + "an added member's scan writes only to the fleet folder")
    boolean refresh;
    @Option(names = "--service", description = "Named fleet: only this member. With --refresh/--kb its fetch, scan and "
            + "knowledge base (#223: the others' migrations needing its tables still run again); else what `logast scan` printed for it")
    String service;
    @Option(names = "--html", description = "Named fleet: the report page is written by every overview (<fleet folder>/report/fleet.html, or --out); kept for scripts")
    boolean html;
    @Option(names = "--open", description = "Named fleet: open the report page in the browser")
    boolean openPage;
    @Option(names = "--json", description = "Named fleet: the overview as JSON")
    boolean json;
    @Mixin
    FleetsDir fleetsDir;

    /** {@code --fleets-dir}: the fleets folder for this run, before LOGAST_FLEETS and the user config. */
    static final class FleetsDir {
        @Option(names = "--fleets-dir", description = "Where named fleets live for this run (default: $LOGAST_FLEETS, else fleets.dir "
                + "in ~/.config/logast/config.yaml, else ~/.local/share/logast/fleets)")
        Path dir;

        FleetHome.Env env() {
            if (dir != null) {
                System.setProperty(FleetHome.DIR_PROPERTY, dir.toString());   // the policy loader finds fleets the same way
            }
            return FleetHome.Env.current();
        }
    }

    @Override
    public Integer call() throws Exception {
        if (kbFull && !flyway) {
            flyway = true;   // #191: a full export is everything, the tables the migrations create too (needs Docker for those)
        }
        if (!kb && (kbFull || flyway || !rows.isEmpty() || postgresImage != null)) {
            kb = true;   // #191: they only mean something for the knowledge base; without --kb they were silently ignored
            System.err.println("fleet: --kb-full/--flyway/--rows/--postgres-image export the knowledge base: --kb is implied");
        }
        FleetHome.Env env = fleetsDir.env();
        if (services.size() == 1 && !services.getFirst().startsWith("@") && FleetHome.validName(services.getFirst())) {
            String name = services.getFirst();
            FleetHome.Location at = FleetHome.fleetDir(env, name);
            if (NamedFleet.exists(at.dir())) {
                return named(name, at.dir());
            }
            if (!Files.exists(Path.of(name))) {
                Out.warn("fleet: no fleet named " + name + " (looked in " + at.dir() + ", from " + at.source()
                        + "); `logast fleet list` shows the known ones, `logast fleet create " + name + "` makes it");
                return 2;
            }
        }
        if (services.size() == 1 && NamedFleet.exists(Path.of(services.getFirst()))) {
            // #147: a fleet folder by its path (`logast fleet .` in the fleet's repo, as its workflow runs it)
            Path dir = Path.of(services.getFirst()).toAbsolutePath().normalize();
            String name = FleetRepos.fleetName(dir);
            return named(name != null ? name : NamedFleet.serviceName(dir), dir);
        }
        if (services.isEmpty()) {
            Out.warn("fleet: name a fleet (`logast fleet list`) or give service roots");
            return 2;
        }
        List<Path> roots = roots();
        if (vocabulary) {
            return vocabulary(roots);
        }
        List<FleetReport.Service> result = new ArrayList<>();
        for (Path root : roots) {
            result.add(scan(root.toAbsolutePath().normalize()).report());
        }
        FleetLinks.Result links = FleetLinks.of(result);
        Path html = FleetReport.write(out != null ? out : Path.of("logast-fleet"), result, links);
        result.forEach(s -> System.out.printf("%-28s score %5.1f  high %3d  medium %3d  low %3d%n", s.name(), s.score(),
                s.severity().getOrDefault("high", 0), s.severity().getOrDefault("medium", 0), s.severity().getOrDefault("low", 0)));
        System.out.println();
        System.out.print(FleetLinks.text(links));
        System.out.println();
        System.out.println("report: " + html.toAbsolutePath());
        return 0;
    }

    interface MemberScan {
        void run() throws IOException;
    }

    /**
     * #136: one member that fails to scan (a repo that is no Java service, a crash on its code) is reported and skipped;
     * the other members are still scanned, and the failed one's snapshot stays as it was. True when it was scanned.
     */
    /** #193: a member's scan; null when it was scanned, else why not (the whole error with LOGAST_DEBUG). */
    static String scanError(MemberScan scan) {
        try {
            scan.run();
            return null;
        } catch (IOException | RuntimeException e) {
            if (System.getenv("LOGAST_DEBUG") != null) {
                e.printStackTrace();
            }
            return String.valueOf(e);
        }
    }

    static boolean skipOnFailure(String service, MemberScan scan) {
        try {
            scan.run();
            return true;
        } catch (IOException | RuntimeException e) {
            Out.warn("fleet: " + service + " skipped, its scan failed: " + e
                    + " (its snapshot stays as it was; `logast scan` in the repo shows the whole error)");
            if (System.getenv("LOGAST_DEBUG") != null) {
                e.printStackTrace();
            }
            return false;
        }
    }

    /** Scans one service as `logast scan` does; the result is what a member's snapshot holds. */
    private NamedFleet.Snapshot scan(Path r) {
        Main.migrate(r);
        Policy policy = Logast.policy(r, null);
        Main.warnOnce(policy);
        if (!Out.Progress.quiet) {
            System.err.println("scanning " + r);
        }
        List<Path> cp = Logast.classpath(r, classpathFile);
        ScanResult scan = Logast.scan(r, cp, policy);
        lastScanned.set(new dev.logast.export.Export.Analysed(Logast.LAST_ANALYSIS.get(), scan, cp));   // #184: the export reuses it
        lastScannedRoot.set(r);
        return NamedFleet.snapshot(r, scan, Logast.LAST_ANALYSIS.get(), policy);
    }

    /** #184: the analysis of the member this thread scanned last, for its export (#226: per thread, scans run in parallel). */
    static final ThreadLocal<dev.logast.export.Export.Analysed> lastScanned = new ThreadLocal<>();
    static final ThreadLocal<Path> lastScannedRoot = new ThreadLocal<>();

    /**
     * #132: scans a member {@code fleet add} registered: the analysis of `logast scan`, with the policy defaults →
     * the fleet's company.yaml → the repo's own logast.yaml if it has one. Every output (classpath, orders.json,
     * coverage, reports) goes to {@code <fleet>/services/<service>/}, laid out like .logast; nothing is written into the
     * repo (no .logast migration, no decision files, no traffic or retry worksheets). The classpath: {@code classpathFile},
     * else the repo's own Gradle run with its output and project cache in the fleet folder.
     */
    static NamedFleet.Snapshot scanAdded(Path fleetDir, String service, Path root, Path classpathFile) throws IOException {
        return scanAdded(fleetDir, service, root, classpathFile, false);
    }

    /** @param noBuilds #147: never run the member's Gradle; the classpath its last build left, if any */
    static NamedFleet.Snapshot scanAdded(Path fleetDir, String service, Path root, Path classpathFile, boolean noBuilds)
            throws IOException {
        Path r = root.toAbsolutePath().normalize();
        Path out = NamedFleet.outputDir(fleetDir, service);
        Policy policy = dev.logast.policy.PolicyLoader.forFleet(r, fleetDir);
        Main.warnOnce(policy);
        List<Path> cp = classpathFile != null ? Logast.classpath(r, classpathFile)
                : noBuilds ? Logast.classpathIn(out) : addedClasspath(service, r, out);
        if (!Out.Progress.quiet) {
            System.err.println("scanning " + r + " (read-only; results in " + out + ")");
        }
        ScanResult scan = Logast.scan(r, cp, policy);
        Analysis a = Logast.LAST_ANALYSIS.get();
        lastScanned.set(new dev.logast.export.Export.Analysed(a, scan, cp));   // #184: the export reuses it
        lastScannedRoot.set(r);
        Path orders = LogastDir.in(out, LogastDir.ORDERS);
        Files.createDirectories(orders.getParent());
        OrderJson.MAPPER.writeValue(orders.toFile(), scan);
        if (policy.packSelected("logging")) {
            a.ledger.writeTo(LogastDir.in(out, LogastDir.COVERAGE), a, scan.orders(), Map.of());
        }
        Reports.writeTo(out, a, dev.logast.rules.Rules.LAST_LIFECYCLE.get());
        return NamedFleet.snapshot(r, scan, a, policy);
    }

    /** The added member's classpath from its own Gradle (reads only), written to {@code out}; the last one if Gradle fails. */
    private static List<Path> addedClasspath(String service, Path r, Path out) throws IOException {
        boolean gradle = Files.exists(r.resolve("gradlew")) || Files.exists(r.resolve("build.gradle"))
                || Files.exists(r.resolve("build.gradle.kts"));
        if (!gradle) {
            Out.warn("fleet: " + service + " has no Gradle build at " + r + ": scanned without a classpath (--classpath-file gives one)");
            return Logast.classpathIn(out);
        }
        Main.Classpath.Result res;
        try (Out.Progress p = new Out.Progress("running Gradle in " + service + " to read the classpath (nothing is compiled)")) {
            res = Main.Classpath.write(r, out);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            res = new Main.Classpath.Result(130, 0, false);
        }
        if (res.printIfFailed().exitCode() != 0) {
            Out.warn("fleet: " + service + ": Gradle failed (exit " + res.exitCode() + "); "
                    + (Logast.classpathIn(out).isEmpty() ? "scanned without a classpath" : "scanned with the classpath of its last scan"));
        }
        return Logast.classpathIn(out);
    }

    /** #132: {@code logast fleet <fleet> --service <name>}: what `logast scan` printed for the member, from its stored orders.json. */
    private int service(String name, Path dir, String svc, Map<String, Path> members, Map<String, Path> added) throws IOException {
        NamedFleet.Snapshot snap = NamedFleet.snapshots(dir).get(svc);
        if (!members.containsKey(svc) && snap == null) {
            Out.warn("fleet: " + name + " has no member " + svc + " (members: " + String.join(", ", members.keySet()) + ")");
            return 2;
        }
        boolean isAdded = added.containsKey(svc);
        Path root = members.containsKey(svc) ? members.get(svc) : Path.of(snap.root());
        Path orders = isAdded ? LogastDir.in(NamedFleet.outputDir(dir, svc), LogastDir.ORDERS) : root.resolve(LogastDir.ORDERS);
        if (!Files.isRegularFile(orders)) {
            Out.warn("fleet: no stored scan of " + svc + " (" + orders + "): " + (isAdded ? "`logast fleet " + name + " --refresh` scans it"
                    : "run `logast scan` in " + root));
            return 2;
        }
        ScanResult r = OrderJson.MAPPER.copy().disable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .readValue(orders.toFile(), ScanResult.class);
        System.out.println(Ansi.bold(svc) + Ansi.dim("  " + root + "  " + (isAdded ? "added" : "joined")
                + (snap == null ? "" : ", snapshot " + age(snap.created(), Instant.now()) + (snap.commit() == null ? "" : ", " + snap.commit()))));
        System.out.print(dev.logast.orders.HumanFormat.render(r, r.orders(), snap == null ? null : snap.appPackage(), Out.width()));
        Path reports = orders.getParent();
        Main.printBlocks(List.of(Ansi.dim("reports: " + reports + "/ metrics.md queries.md lifecycle.txt coverage.txt")));
        return 0;
    }

    /** #182: every member's facts into {@code <fleet>/kb/<member>/}, then the fleet's mappings into {@code kb/mapped/}. */
    /** #184: members exported during this run's refresh (with the scan's analysis). */
    private final java.util.Set<String> kbDone = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** #205: the tables the members' migrations create, for the migrations of others that need them (stand-ins). */
    private volatile Map<String, List<String>> tablesMade = Map.of();
    /** #227: which member makes each table, to say whose tables a member's migrations ran against. */
    private volatile Map<String, String> tableOwners = Map.of();

    /** #215: the tables the fleet's mappings ({@code kb/mappings/*.yaml}) need the rows of ({@code needs: {rows: […]}}). */
    static List<String> neededRows(Path kb) {
        List<String> out = new java.util.ArrayList<>();
        Path dir = kb.resolve("mappings");
        if (!Files.isDirectory(dir)) {
            return out;
        }
        try (var s = Files.list(dir)) {
            for (Path f : s.filter(p -> p.getFileName().toString().matches(".*\\.ya?ml")).sorted().toList()) {
                try {
                    dev.logast.kb.FactMapping.load(f).neededRows().forEach(t -> {
                        if (!out.contains(t)) {
                            out.add(t);
                        }
                    });
                } catch (IOException | IllegalArgumentException e) {
                    // a broken mapping is reported when the mappings are applied
                }
            }
        } catch (IOException e) {
            return out;
        }
        return out;
    }

    /** #216: the rows each repo of repos.yaml asks for ({@code rows:}): repo name → tables. */
    Map<String, List<String>> repoRows = Map.of();

    /** A member's Flyway options: --flyway's, with the rows repos.yaml asks of it (which run its migrations alone too). */
    dev.logast.export.Export.FlywayOptions flywayOptions(String member) {
        List<String> own = repoRows.getOrDefault(member, List.of());
        if (!flyway && own.isEmpty()) {
            return null;
        }
        java.util.Set<String> all = new java.util.LinkedHashSet<>(rows);
        all.addAll(own);
        return new dev.logast.export.Export.FlywayOptions(List.copyOf(all), postgresImage, tablesMade);
    }

    private String kbOptions(String member) throws IOException {
        return KbExport.optionsKey(McpCmd.build(), flywayOptions(member), classpathFile, noBuilds);   // a new logast build re-exports
    }

    /** One member's facts into {@code kb/<member>/}, unless its commit and the options are those of its last export. */
    /** #193: what a member's export did, for its line. */
    record KbOutcome(FleetLog.Status status, String detail, List<String> notes) {
    }

    private KbOutcome exportMember(Path dir, String name, Path root, dev.logast.export.Export.Analysed analysed, Path analysedRoot) {
        return exportMember(dir, name, root, analysed, analysedRoot, false);
    }

    private KbOutcome exportMember(Path dir, String name, Path root, dev.logast.export.Export.Analysed analysed, Path analysedRoot,
                                   boolean again) {
        // #207: what the export prints (Flyway's notes, warnings) is kept: condensed under the repo's line, in full in a log
        List<String> printed = new java.util.ArrayList<>();
        KbOutcome o;
        try {
            o = Out.Routing.capture(printed, () -> exportMemberQuietly(dir, name, root, analysed, analysedRoot, again));
        } catch (Exception e) {
            o = new KbOutcome(FleetLog.Status.FAILED, "knowledge base not exported: " + e.getMessage(), List.of());
        }
        if (printed.isEmpty()) {
            return o;
        }
        Path log = dir.resolve("kb").resolve(name).resolve("export.log");
        try {
            Files.createDirectories(log.getParent());
            Files.write(log, printed);
        } catch (IOException e) {
            log = null;
        }
        List<String> notes = new java.util.ArrayList<>(o.notes());
        notes.addAll(condense(printed, log == null ? null : dir.relativize(log).toString()));
        return new KbOutcome(o.status(), o.detail(), notes);
    }

    private static final java.util.regex.Pattern MADE_OUTSIDE = java.util.regex.Pattern.compile(
            "^flyway(?: \\S+)?: the migrations expect (schema|role) (\\S+) to exist");

    /**
     * #207: an export's printed lines as a few notes: the schemas and roles its migrations expect from outside in one
     * line; Flyway's left-out and stand-in lines dropped (the repo's line says them, from the facts); the export's own
     * problems dropped (they are the repo's notes already); the rest, the first three, then where the whole log is.
     */
    static List<String> condense(List<String> printed, String log) {
        java.util.Set<String> outside = new java.util.TreeSet<>();
        List<String> rest = new java.util.ArrayList<>();
        for (String l : printed) {
            java.util.regex.Matcher m = MADE_OUTSIDE.matcher(l);
            if (m.find()) {
                outside.add(m.group(1) + " " + m.group(2));
            } else if (!(l.startsWith("flyway") && (l.contains(": left out ") || l.contains("stand-in") || l.contains("ran it against")))
                    && !l.startsWith("export: ") && !l.startsWith("WARNING: ") && !rest.contains(l)) {
                rest.add(l);
            }
        }
        List<String> notes = new java.util.ArrayList<>();
        if (!outside.isEmpty()) {
            notes.add("its migrations expect from outside (created empty for the run): " + String.join(", ", outside));
        }
        rest.stream().limit(3).forEach(notes::add);
        if (rest.size() > 3 && log != null) {
            notes.add("… " + (rest.size() - 3) + " more line(s) in " + log);
        }
        return notes;
    }

    private KbOutcome exportMemberQuietly(Path dir, String name, Path root, dev.logast.export.Export.Analysed analysed, Path analysedRoot,
                                          boolean again) {
        Path out = dir.resolve("kb").resolve(name);
        try {
            String commit = dev.logast.fleet.NamedFleet.commit(root);
            dev.logast.export.Export.FlywayOptions fly = flywayOptions(name);
            String options = kbOptions(name);
            if (!again && !kbFull && KbExport.upToDate(out, commit, options) && !(fly != null && KbExport.needsChanged(out, tablesMade))) {
                return new KbOutcome(FleetLog.Status.SAME, "knowledge base unchanged since its last export (" + commit + "), kept", List.of());
            }
            Path ar = analysedRoot == null ? null : analysedRoot.toAbsolutePath().normalize();
            dev.logast.export.RepoExport.Result r = KbExport.repo(root, name, out, dir.resolve(".cache/kb-classpath").resolve(name),
                    classpathFile, noBuilds, fly, List.of(), List.of(),
                    rootDir -> analysed != null && rootDir.toAbsolutePath().normalize().equals(ar) ? analysed : null);
            String files = "knowledge base: " + r.files().size() + " facts file(s)";
            if (r.problems().isEmpty()) {
                // #205: what its migrations needed from other repos goes into the stamp: a change of that exports it again
                List<KbExport.Need> needs = fly != null ? KbExport.needs(out) : List.of();
                KbExport.stamp(out, commit, options, KbExport.resolved(needs, tablesMade));
                List<KbExport.Need> left = needs.stream().filter(KbExport.Need::leftOut).toList();
                long standIns = needs.stream().filter(n -> !n.leftOut()).count();
                String withStandIns = standIns == 0 ? "" : ", " + standIns + " table(s) of other repos stood in for";
                List<String> against = KbExport.ranAgainst(needs, tablesMade, tableOwners);   // #227: whose tables, said
                String ranAgainst = against.isEmpty() ? null : "ran against other repos' tables: " + String.join(", ", against.stream().limit(8).toList())
                        + (against.size() > 8 ? ", … " + (against.size() - 8) + " more" : "");
                if (left.isEmpty()) {
                    return new KbOutcome(FleetLog.Status.OK, files + withStandIns, ranAgainst == null ? List.of() : List.of(ranAgainst));
                }
                // #207: grouped by what they need, one line each
                Map<String, java.util.Set<String>> byNeed = new java.util.TreeMap<>();
                Map<String, java.util.Set<String>> lostByNeed = new java.util.TreeMap<>();
                for (KbExport.Need n : left) {
                    byNeed.computeIfAbsent(n.asked(), k -> new java.util.TreeSet<>()).add(n.via().replaceFirst("^migration ", "")
                            .replaceFirst("__.*", ""));
                    lostByNeed.computeIfAbsent(n.asked(), k -> new java.util.TreeSet<>()).addAll(n.lost());
                }
                List<String> notes = new java.util.ArrayList<>();
                if (ranAgainst != null) {
                    notes.add(ranAgainst);
                }
                byNeed.entrySet().stream().limit(6).forEach(e -> notes.add("needs " + e.getKey() + " (no repo of the fleet makes it): "
                        + String.join(", ", e.getValue()) + " left out" + (lostByNeed.get(e.getKey()).isEmpty() ? ""
                        : "; missing: " + String.join(", ", lostByNeed.get(e.getKey())))));
                if (byNeed.size() > 6) {
                    notes.add("… and " + (byNeed.size() - 6) + " more (logast mcp: describe service:" + name + ")");
                }
                long migrations = left.stream().map(KbExport.Need::via).distinct().count();
                return new KbOutcome(FleetLog.Status.PROBLEM, files + withStandIns + ", " + migrations + " migration(s) left out (exported "
                        + "again once a repo makes what they need)", notes);
            }
            // #187: a partial export is not done: the next run exports it again, once its cause is fixed
            Files.deleteIfExists(out.resolve(KbExport.STAMP));
            List<String> notes = new java.util.ArrayList<>(r.problems().stream().limit(5)
                    .map(p -> p.length() > 220 ? p.substring(0, 219) + "…" : p).toList());
            if (r.problems().size() > 5) {
                notes.add("… and " + (r.problems().size() - 5) + " more");
            }
            return new KbOutcome(FleetLog.Status.PROBLEM, files + ", " + r.problems().size() + " problem(s), so it is exported again next run",
                    notes);
        } catch (IOException | RuntimeException e) {
            return new KbOutcome(FleetLog.Status.FAILED, "knowledge base not exported: " + e.getMessage(), List.of());
        }
    }

    /**
     * #150, #195: the members to rescan before the overview, with why: commits since their snapshot, or a snapshot another
     * logast made. #223: repos without Java are not scanned.
     */
    private static Map<String, String> stale(Path dir, Map<String, Path> members) {
        Map<String, String> out = new java.util.LinkedHashMap<>();
        Map<String, NamedFleet.Snapshot> before = NamedFleet.snapshots(dir);
        for (var m : members.entrySet()) {
            NamedFleet.Snapshot snap = before.get(m.getKey());
            if (snap == null || snap.commit() == null || !Files.isDirectory(m.getValue())
                    || dev.logast.export.RepoExport.roots(m.getValue()).stream().noneMatch(r -> r.kind().equals("java"))) {
                continue;
            }
            String head = NamedFleet.commit(m.getValue());
            if (head != null && !head.equals(snap.commit())) {
                out.put(m.getKey(), "new commits since its snapshot (" + snap.commit() + " → " + head + ")");
            } else if (!dev.logast.Build.id().equals(snap.logast())) {
                out.put(m.getKey(), "snapshot was made by " + (snap.logast() == null ? "an older logast" : "logast " + snap.logast())
                        + ", this is " + dev.logast.Build.id());
            }
        }
        return out;
    }

    /** What the overview says of a member without a snapshot: #225: a repo without Java never has one. */
    static String noSnapshot(String fleet, Path root, boolean added) {
        boolean noJava = root != null && Files.isDirectory(root)
                && dev.logast.export.RepoExport.roots(root).stream().noneMatch(x -> x.kind().equals("java"));
        return noJava ? "no findings: no Java to scan (its facts come with the knowledge base)"
                : added ? "no snapshot yet: `logast fleet " + fleet + " --refresh` scans it" : "no snapshot yet: run `logast scan` in " + root;
    }

    /** #226: scans one member (and exports its knowledge base with that analysis); what prints its line in the phase. */
    private Runnable scanMember(Path dir, String svc, Path root, Map<String, Path> added, FleetLog.Phase phase, Out.Progress p) {
        long t0 = System.nanoTime();
        lastScanned.remove();
        String error = scanError(() -> NamedFleet.write(dir, added.containsKey(svc) ? scanAdded(dir, svc, root, classpathFile, noBuilds)
                : scan(root)));
        KbOutcome o = null;
        if (kb && lastScanned.get() != null) {   // #184: exported now, with the analysis the scan just made
            if (p != null) {
                p.update("~exporting its knowledge base");
            }
            o = exportMember(dir, svc, root, lastScanned.get(), lastScannedRoot.get());
            kbDone.add(svc);
        }
        lastScanned.remove();
        long took = System.nanoTime() - t0;
        KbOutcome out = o;
        if (error != null) {
            return () -> phase.done(svc, FleetLog.Status.FAILED, took, "scan failed: " + FleetLog.oneLine(error) + " (its snapshot stays as it was; "
                    + "`logast scan` in the repo shows the whole error)", List.of());
        } else if (out == null) {
            return () -> phase.done(svc, FleetLog.Status.OK, took, "scanned", List.of());
        }
        return () -> phase.done(svc, out.status() == FleetLog.Status.SAME ? FleetLog.Status.OK : out.status(), took,
                "scanned · " + out.detail(), out.notes());
    }

    /** #225: a section of the overview, headed as the phases are. */
    private static void section(String title) {
        String head = "━━ " + title + " ";
        System.out.println();
        System.out.println(Ansi.bold(head + "━".repeat(Math.max(3, Math.min(Out.width(), 100) - head.length()))));
    }

    /** #223: the members a run works on: the one --service names (with --refresh/--kb), else all. */
    private Map<String, Path> work(Map<String, Path> members) {
        if (service == null || !(refresh || kb)) {
            return members;
        }
        return members.containsKey(service) ? Map.of(service, members.get(service)) : Map.of();
    }

    private void knowledgeBase(Path dir, Map<String, Path> members, FleetLog log) throws IOException {
        Map<String, Path> all = members;
        members = work(members);
        Path kbDir = dir.resolve("kb");
        Files.createDirectories(kbDir);
        Map<String, Path> todo = new java.util.LinkedHashMap<>();
        List<String> away = new java.util.ArrayList<>();
        for (var m : members.entrySet()) {
            if (kbDone.contains(m.getKey())) {
                continue;
            }
            if (!Files.isDirectory(m.getValue())) {
                away.add(m.getKey());
                continue;
            }
            todo.put(m.getKey(), m.getValue());
        }
        FleetLog.Phase phase = log.phase("knowledge base", todo.isEmpty() && away.isEmpty() ? "every repo was exported with its scan"
                : "the repos not exported with a scan (infra, charts, workflows, unchanged ones kept), " + Math.max(1, kbThreads)
                + " at a time" + (flyway ? "; each environment's migrations run in a throwaway PostgreSQL" : ""), todo.size() + away.size());
        for (String a : away) {
            phase.done(a, FleetLog.Status.SKIPPED, 0, "not on this machine (" + members.get(a) + "); its facts stay as they were", List.of());
        }
        java.util.Set<String> running = java.util.concurrent.ConcurrentHashMap.newKeySet();
        List<java.util.concurrent.Callable<Void>> work = new java.util.ArrayList<>();
        try (Out.Progress live = new Out.Progress("~exporting")) {
            for (var m : todo.entrySet()) {
                work.add(() -> {
                    running.add(m.getKey());
                    live.update("~exporting " + String.join(", ", running));
                    long t0 = System.nanoTime();
                    KbOutcome o = exportMember(dir, m.getKey(), m.getValue(), null, null);
                    running.remove(m.getKey());
                    live.update("~exporting " + String.join(", ", running));
                    phase.done(m.getKey(), o.status(), System.nanoTime() - t0, o.detail(), o.notes());
                    return null;
                });
            }
            var pool = java.util.concurrent.Executors.newFixedThreadPool(Math.max(1, kbThreads));
            try {
                for (var f : pool.invokeAll(work)) {
                    f.get();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (java.util.concurrent.ExecutionException e) {
                Out.warn("fleet --kb: " + e.getCause());
            } finally {
                pool.shutdown();
            }
        }
        phase.end();
        if (flyway) {
            dependencyRounds(dir, all, log);   // #223: every member whose migrations need what was exported
        }
        List<String> mapped = KbExport.mappings(kbDir);
        if (!mapped.isEmpty()) {
            System.err.println("   " + Ansi.dim("kb/mappings:"));
            mapped.forEach(l -> System.err.println("     " + l));
        }
    }

    /**
     * #205: a member's migrations that need another member's tables ran against what the knowledge base had when it
     * started; once every member is exported, the ones whose needs resolve differently now run again, round by round
     * (x needs y, y needs z), until none changes.
     */
    private void dependencyRounds(Path dir, Map<String, Path> members, FleetLog log) {
        for (int round = 1; round <= 4; round++) {
            tablesMade = KbExport.tablesMade(dir.resolve("kb"));
            tableOwners = KbExport.tableOwners(dir.resolve("kb"));
            Map<String, Path> again = new java.util.LinkedHashMap<>();
            for (var m : members.entrySet()) {
                if (Files.isDirectory(m.getValue()) && KbExport.needsChanged(dir.resolve("kb").resolve(m.getKey()), tablesMade)) {
                    again.put(m.getKey(), m.getValue());
                }
            }
            if (again.isEmpty()) {
                return;
            }
            FleetLog.Phase phase = log.phase("dependencies" + (round == 1 ? "" : " " + round), "migrations needing tables other repos "
                    + "make, run again now that those repos are exported", again.size());
            for (var m : again.entrySet()) {
                long t0 = System.nanoTime();
                KbOutcome o;
                try (Out.Progress p = phase.working(m.getKey(), "running its migrations again")) {
                    o = exportMember(dir, m.getKey(), m.getValue(), null, null, true);
                }
                phase.done(m.getKey(), o.status(), System.nanoTime() - t0, o.detail(), o.notes());
            }
            phase.end();
        }
    }

    // ---- logast fleet <name> -----------------------------------------------------------------------------------------

    private int named(String name, Path dir) throws IOException {
        java.io.PrintStream before = System.err;
        if (refresh || kb) {
            System.setErr(new Out.Routing(before));   // #207: an export's lines go under its repo, not between the others
        }
        try {
            return namedRun(name, dir);
        } finally {
            System.setErr(before);
        }
    }

    private int namedRun(String name, Path dir) throws IOException {
        if (refresh && noBuilds) {
            Out.warn("fleet: --no-builds: added members are scanned from their source only (survey quality: calls through "
                    + "library types may stay unresolved)");
        }
        // #193: what the run does, a header per phase, a line per repo, a summary
        FleetLog log = refresh || kb ? new FleetLog() : null;
        rowsToExport(dir);
        List<FleetRepos.Repo> repos = refresh ? FleetRepos.list(dir).stream()
                .filter(r -> service == null || r.name().equals(service)).toList() : List.of();
        if (service != null && (refresh || kb) && !NamedFleet.members(dir).containsKey(service)
                && FleetRepos.list(dir).stream().noneMatch(r -> r.name().equals(service))) {
            Out.warn("fleet: no member " + service + " in " + name + ": " + String.join(", ", NamedFleet.members(dir).keySet()));
            return 2;
        }
        Map<String, String> stale = refresh ? Map.of() : stale(dir, work(NamedFleet.members(dir)));   // #224
        if (log != null) {
            intro(name, dir, repos, stale, log);
        }
        if (refresh) {
            update(dir, repos, log);
        }
        Map<String, Path> members = NamedFleet.members(dir);
        Map<String, Path> added = NamedFleet.added(dir);
        if (kb && flyway) {   // #205: what the last export knew of each member's tables, for the others' migrations
            tablesMade = KbExport.tablesMade(dir.resolve("kb"));
            tableOwners = KbExport.tableOwners(dir.resolve("kb"));
        }
        if (refresh) {
            scanAll(dir, members, added, log);
        }
        if (!stale.isEmpty()) {
            rescan(dir, stale, members, added, log);
        }
        if (kb) {
            knowledgeBase(dir, members, log);
        }
        if (log != null) {
            summary(dir, log);
        }
        if (service != null && !(refresh || kb)) {   // #223: with --refresh/--kb, --service only picks what is worked on
            return service(name, dir, service, members, added);
        }
        Overview ov = overview(name, dir, members);
        return json ? printJson(name, dir, members, added, ov) : printOverview(name, dir, members, added, ov);
    }

    /** What an overview shows, built once for the JSON and the text form. */
    private record Overview(Map<String, NamedFleet.Snapshot> snaps, FleetLinks.Result links, CentralVocabulary.Result vocab,
                            Path company, List<CompanyPolicy.Suggestion> suggestions, String agreed, List<String> next, Path page) {
    }

    /** The tables whose rows become facts: repos.yaml's per repo (#216) and what the fleet's mappings need (#215). */
    private void rowsToExport(Path dir) {
        Map<String, List<String>> asked = new java.util.TreeMap<>();
        for (FleetRepos.Repo r : FleetRepos.list(dir)) {
            if (!r.rows().isEmpty()) {
                asked.put(r.name(), r.rows());
            }
        }
        repoRows = asked;
        for (String t : neededRows(dir.resolve("kb"))) {   // #215: what the fleet's mappings say they need
            if (!rows.contains(t)) {
                rows.add(t);
            }
        }
        if (kb && !flyway && rows.size() > 0 && asked.isEmpty()) {
            Out.warn("fleet: the mappings need rows of " + rows + ": add --flyway, or rows: to the repo that seeds them in repos.yaml");
        }

    }

    /** #193: the run's plan, before its phases. */
    private void intro(String name, Path dir, List<FleetRepos.Repo> repos, Map<String, String> stale, FleetLog log) {
        java.util.Set<String> names = new java.util.TreeSet<>(NamedFleet.members(dir).keySet());
        repos.forEach(r -> names.add(r.name()));
        List<String[]> plan = new java.util.ArrayList<>();
        if (!stale.isEmpty()) {
            plan.add(new String[]{"rescan", "repos changed since their snapshot, or scanned by another logast: findings again"});
        }
        if (refresh) {
            plan.add(new String[]{"update", "fetch each repo of repos.yaml (read-only clones)"});
            plan.add(new String[]{"scan", "analyse each Java repo: findings and score" + (kb ? ", then its knowledge base" : "")
                    + (noBuilds ? " (source only)" : "")});
        }
        if (kb) {
            plan.add(new String[]{"knowledge base", "facts of the other repos into kb/" + (kbFull ? ", all of them"
                    : ", unchanged ones kept") + (flyway ? ", tables from the migrations (Docker)" : "")});
            if (flyway) {
                plan.add(new String[]{"dependencies", "migrations needing another repo's tables run again against them (when any)"});
            }
        }
        plan.add(new String[]{"report", "the overview, report/fleet.html"});
        log.intro("logast fleet " + name + " · " + names.size() + " repos", plan, names.stream().mapToInt(String::length).max().orElse(20));
    }

    /** #147: the members repos.yaml lists by URL: cloned or updated, then added (read-only) like `fleet add`. */
    private void update(Path dir, List<FleetRepos.Repo> repos, FleetLog log) {
        FleetLog.Phase phase = log.phase("update", null, repos.size());
        for (FleetRepos.Repo repo : repos) {
            long t0 = System.nanoTime();
            try (Out.Progress p = phase.working(repo.name(), "fetching " + repo.url())) {
                NamedFleet.add(dir, FleetRepos.sync(dir, repo));
                phase.done(repo.name(), FleetLog.Status.OK, System.nanoTime() - t0, "updated", List.of());
            } catch (RuntimeException e) {
                phase.done(repo.name(), FleetLog.Status.FAILED, System.nanoTime() - t0, "not updated: "
                        + FleetLog.oneLine(e.getMessage()) + "; its snapshot stays as it was", List.of());
            }
        }
        phase.end();
    }

    /** Every member scanned (#226: `--threads` at a time, each repo's own output kept off the screen). */
    private void scanAll(Path dir, Map<String, Path> members, Map<String, Path> added, FleetLog log) {
        FleetLog.Phase phase = log.phase("scan", threads > 1 ? Math.max(1, threads) + " at a time" : null, work(members).size());
        List<java.util.concurrent.Callable<Void>> jobs = new java.util.ArrayList<>();
        java.util.Set<String> running = java.util.concurrent.ConcurrentHashMap.newKeySet();
        Out.Progress[] live = new Out.Progress[1];
        for (var m : work(members).entrySet()) {
            String svc = m.getKey();
            Path root = m.getValue();
            if (!Files.isDirectory(root)) {
                phase.done(svc, FleetLog.Status.SKIPPED, 0, "not on this machine (" + root + "); its snapshot stays as it was", List.of());
                continue;
            }
            if (dev.logast.export.RepoExport.roots(root).stream().noneMatch(r -> r.kind().equals("java"))) {
                phase.done(svc, FleetLog.Status.SKIPPED, 0, "no Java build to scan" + (kb ? ": its facts come with the knowledge base" : ""),
                        List.of());
                continue;
            }
            jobs.add(() -> {
                if (threads <= 1) {
                    Runnable done;
                    try (Out.Progress p = phase.working(svc, "scanning")) {
                        done = scanMember(dir, svc, root, added, phase, p);
                    }
                    done.run();
                } else {   // #226: one line for the repos being scanned; each repo's own output kept off the screen
                    running.add(svc);
                    live[0].update("~scanning " + String.join(", ", running));
                    Runnable done = Out.Routing.capture(new java.util.ArrayList<>(), () -> scanMember(dir, svc, root, added, phase, null));
                    running.remove(svc);
                    live[0].update("~scanning " + String.join(", ", running));
                    done.run();   // its line, outside the capture
                }
                return null;
            });
        }
        if (threads <= 1) {
            for (var j : jobs) {
                try {
                    j.call();
                } catch (Exception e) {
                    Out.warn("fleet --refresh: " + e);
                }
            }
        } else {
            try (Out.Progress p = new Out.Progress("~scanning")) {
                live[0] = p;
                var pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
                try {
                    for (var f : pool.invokeAll(jobs)) {
                        f.get();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (java.util.concurrent.ExecutionException e) {
                    Out.warn("fleet --refresh: " + e.getCause());
                } finally {
                    pool.shutdown();
                }
            }
        }
        phase.end();
    }

    /**
     * #150: a member with commits since its snapshot is rescanned, so the overview never shows an old state of it; #195:
     * so is one another logast scanned (its findings come from that logast's rules); #224: as a phase.
     */
    private void rescan(Path dir, Map<String, String> stale, Map<String, Path> members, Map<String, Path> added, FleetLog log) {
        FleetLog.Phase phase = log == null ? null : log.phase("rescan", null, stale.size());
        for (var e : stale.entrySet()) {
            String svc = e.getKey();
            Path root = members.get(svc);
            if (phase == null) {
                System.err.println("fleet: " + svc + (e.getValue().startsWith("new") ? " has " : "'s ") + e.getValue() + ": rescanning it");
                skipOnFailure(svc, () -> NamedFleet.write(dir, added.containsKey(svc)
                        ? scanAdded(dir, svc, root, classpathFile, noBuilds) : scan(root)));
                continue;
            }
            long t0 = System.nanoTime();
            String error;
            try (Out.Progress p = phase.working(svc, "rescanning")) {
                error = scanError(() -> NamedFleet.write(dir, added.containsKey(svc)
                        ? scanAdded(dir, svc, root, classpathFile, noBuilds) : scan(root)));
            }
            phase.done(svc, error == null ? FleetLog.Status.OK : FleetLog.Status.FAILED, System.nanoTime() - t0,
                    error == null ? "rescanned: " + e.getValue() : "scan failed: " + FleetLog.oneLine(error)
                            + " (its snapshot stays as it was)", List.of());
        }
        if (phase != null) {
            phase.end();
        }
    }

    /** The run's summary, with where the knowledge base is for an MCP client. */
    private void summary(Path dir, FleetLog log) {
        List<String[]> next = new java.util.ArrayList<>();
        if (kb) {
            Path kbDir = dir.resolve("kb").toAbsolutePath().normalize();
            Path cwd = Path.of("").toAbsolutePath();
            String shown = kbDir.startsWith(cwd) ? (cwd.relativize(kbDir).toString().isEmpty() ? "." : cwd.relativize(kbDir).toString())
                    : kbDir.toString();
            next.add(new String[]{"logast mcp config --kb " + shown, "connects an MCP client to the knowledge base"});
        }
        log.summary(next);
    }

    /**
     * The overview's content from the snapshots: links between services, the vocabulary (#139: the worksheet and company.yaml
     * kept current once a worksheet exists), company.yaml's suggestions (#129), the next steps (#149) and the page (#141).
     */
    private Overview overview(String name, Path dir, Map<String, Path> members) throws IOException {
        Map<String, NamedFleet.Snapshot> snaps = NamedFleet.snapshots(dir);
        List<FleetReport.Service> services = snaps.values().stream().map(NamedFleet.Snapshot::report).toList();
        FleetLinks.Result links = FleetLinks.of(services);
        CentralVocabulary.Result vocab = NamedFleet.vocabulary(dir, snaps.values());
        // #139: every overview keeps the worksheet and company.yaml's vocabulary current once a worksheet exists (decisions
        // are kept), so deciding is: edit the worksheet, run `logast fleet <name>`; --vocabulary writes the first one
        if (vocabulary || Files.exists(dir.resolve(CentralVocabulary.WORKSHEET))) {
            Files.writeString(dir.resolve(CentralVocabulary.WORKSHEET), vocab.worksheet());
            CentralVocabulary.writeSchema(dir);
            CentralVocabulary.writeCompany(dir.resolve(CentralVocabulary.COMPANY), vocab.company());
        }
        // #129: what the members agree on, as commented suggestions in company.yaml
        Path company = dir.resolve(CentralVocabulary.COMPANY);
        List<CompanyPolicy.Suggestion> suggestions = CompanyPolicy.refresh(dir, name, snaps.values());
        String agreed = CompanyPolicy.overviewLine(suggestions, company);
        // #149: what to do next, on the page and at the end of the overview
        List<String> next = dev.logast.fleet.FleetNext.steps(name, dir, members, NamedFleet.joined(dir), snaps, suggestions, links, vocab);
        // #141: every overview rewrites the page (--html only says so explicitly)
        Path page = FleetReport.writeDaily(out != null ? out : dir.resolve("report"), services, links,
                suggestions.stream().map(CompanyPolicy.Suggestion::text).toList(), next);
        return new Overview(snaps, links, vocab, company, suggestions, agreed, next, page);
    }

    private int printJson(String name, Path dir, Map<String, Path> members, Map<String, Path> added, Overview ov) throws IOException {
        Map<String, NamedFleet.Snapshot> snaps = ov.snaps();
        FleetLinks.Result links = ov.links();
        CentralVocabulary.Result vocab = ov.vocab();
        Path company = ov.company();
        List<CompanyPolicy.Suggestion> suggestions = ov.suggestions();
        List<String> next = ov.next();
        Path page = ov.page();
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("fleet", name);
        o.put("dir", dir.toString());
        List<Map<String, Object>> rows = new ArrayList<>();
        Set<String> names = new java.util.TreeSet<>(members.keySet());
        names.addAll(snaps.keySet());
        for (String s : names) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("service", s);
            row.put("root", members.containsKey(s) ? members.get(s).toString() : snaps.get(s).root());
            row.put("member", !members.containsKey(s) ? "former" : added.containsKey(s) ? "added" : "joined");   // #132
            NamedFleet.Snapshot snap = snaps.get(s);
            if (snap != null) {
                row.put("score", snap.report().score());
                row.put("open", open(snap.report()));
                row.put("severity", snap.report().severity());
                row.put("snapshot", snap.created());
                row.put("commit", snap.commit());
            }
            rows.add(row);
        }
        o.put("services", rows);
        o.put("links", links);
        o.put("vocabulary", Map.of("summary", vocab.summary(), "conflicts", vocab.conflicts()));
        o.put("company", Map.of("file", company.toString(), "suggestions", suggestions));
        o.put("next", next);
        if (page != null) {
            o.put("report", page.toAbsolutePath().toString());
        }
        System.out.println(OrderJson.MAPPER.writeValueAsString(o));
        return 0;
    }

    private int printOverview(String name, Path dir, Map<String, Path> members, Map<String, Path> added, Overview ov) {
        Map<String, NamedFleet.Snapshot> snaps = ov.snaps();
        FleetLinks.Result links = ov.links();
        CentralVocabulary.Result vocab = ov.vocab();
        Path company = ov.company();
        String agreed = ov.agreed();
        List<String> next = ov.next();
        Path page = ov.page();
        Instant now = Instant.now();
        section("fleet " + name);   // #225: the overview in the phases' style
        System.out.println("   " + Ansi.dim(dir + "  (" + members.size() + (members.size() == 1 ? " member)" : " members)")));
        Set<String> names = new java.util.TreeSet<>(members.keySet());
        names.addAll(snaps.keySet());
        int w = Math.min(36, names.stream().mapToInt(String::length).max().orElse(8)) + 2;
        for (String s : names) {
            NamedFleet.Snapshot snap = snaps.get(s);
            // #132: added (the repo knows nothing of the fleet) or joined (fleet: in its logast.yaml)
            String pad = "  " + FleetLog.fit(s, w) + Ansi.dim(FleetLog.fit(!members.containsKey(s) ? "" : added.containsKey(s) ? "added" : "joined", 8));
            if (snap == null) {
                System.out.println(pad + Ansi.dim(noSnapshot(name, members.get(s), added.containsKey(s))));
                continue;
            }
            FleetReport.Service r = snap.report();
            int high = r.severity().getOrDefault("high", 0);
            String score = String.format("score %5.1f", r.score());
            System.out.println(pad + (r.score() >= 80 ? Ansi.green(score) : r.score() >= 50 ? Ansi.yellow(score) : Ansi.red(score))
                    + String.format("  open %3d (", open(r)) + (high > 0 ? Ansi.red("high " + high) : "high 0")
                    + String.format(", medium %d, low %d)", r.severity().getOrDefault("medium", 0), r.severity().getOrDefault("low", 0))
                    + Ansi.dim(String.format("  snapshot %s%s%s", age(snap.created(), now), snap.commit() == null ? "" : ", " + snap.commit(),
                    members.containsKey(s) ? "" : " (not a member any more)")));
        }
        // #133: what `fleet tidy` would remove from each joined member's logast.yaml (read only)
        List<String> tidy = new ArrayList<>();
        for (var m : NamedFleet.joined(dir).entrySet()) {
            if (Files.isDirectory(m.getValue())) {
                String line = FleetTidy.plan(m.getValue(), company).overviewLine(m.getKey(), name);
                if (line != null) {
                    tidy.add(line);
                }
            }
        }
        if (!tidy.isEmpty()) {
            System.out.println();
            System.out.println("Own settings next to company.yaml:");
            tidy.forEach(l -> System.out.println("  " + l));
        }
        section("across services");
        for (String l : FleetLinks.text(links).split("\n")) {   // #225: the heads bold, what breaks and its fix stand out
            String t = l.strip();
            System.out.println(!l.startsWith(" ") ? Ansi.bold(l) : t.startsWith("breaks at") ? Ansi.yellow(l)
                    : t.startsWith("fix:") ? Ansi.cyan(l) : t.startsWith("call ") || t.startsWith("entry ") ? Ansi.dim(l) : l);
        }
        section("vocabulary");
        Path worksheet = dir.resolve(CentralVocabulary.WORKSHEET);
        System.out.println("Vocabulary: " + vocab.conflicts().size() + (vocab.conflicts().size() == 1 ? " conflict" : " conflicts")
                + " between keys nobody decided yet");
        vocab.conflicts().forEach(c -> System.out.println("  " + c));
        vocab.summary().stream().filter(l -> l.startsWith("to decide:") || l.startsWith("decisions to fix") || l.startsWith("  "))
                .forEach(l -> System.out.println("  " + l.strip()));
        if (vocabulary || Files.exists(worksheet)) {
            System.out.println("worksheet: " + worksheet + " (uncomment a `# decide:` line per key, then run `logast fleet " + name + "`)");
            System.out.println("company:   " + dir.resolve(CentralVocabulary.COMPANY) + " (" + vocab.terms()
                    + (vocab.terms() == 1 ? " term" : " terms") + ", generated; every member extends it)");
        } else {
            System.out.println("  `logast fleet " + name + " --vocabulary` writes the worksheet " + worksheet
                    + " to decide them in; from then on every `logast fleet " + name + "` keeps it and company.yaml current");
        }
        if (agreed != null) {
            System.out.println(agreed);
        }
        section("next");
        if (next.isEmpty()) {
            System.out.println("  nothing left: the members follow the company policy and nothing high is open");
        } else {
            for (int i = 0; i < next.size(); i++) {
                System.out.println("  " + Ansi.cyan((i + 1) + ".") + " " + next.get(i));
            }
        }
        // #149: a file:// URL, clickable in a terminal; --open shows it in the browser
        System.out.println();
        System.out.println("report: " + page.toAbsolutePath().toUri());
        if (openPage) {
            openInBrowser(page);
        }
        return 0;
    }

    /** The system's opener (xdg-open, open, start); a failure is one warning, never the command's. */
    static void openInBrowser(Path page) {
        String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
        List<String> cmd = os.contains("mac") ? List.of("open", page.toString())
                : os.contains("win") ? List.of("cmd", "/c", "start", "", page.toString()) : List.of("xdg-open", page.toString());
        try {
            new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        } catch (IOException e) {
            Out.warn("fleet: could not open the page (" + e.getMessage() + "); open " + page.toUri());
        }
    }

    private static int open(FleetReport.Service s) {
        return s.severity().values().stream().mapToInt(Integer::intValue).sum();
    }

    /** "just now", "12 min ago", "5 h ago", "3 days ago". */
    static String age(String created, Instant now) {
        try {
            Duration d = Duration.between(Instant.parse(created), now);
            if (d.toMinutes() < 1) {
                return "just now";
            }
            if (d.toMinutes() < 60) {
                return d.toMinutes() + " min ago";
            }
            return d.toHours() < 48 ? d.toHours() + " h ago" : d.toDays() + " days ago";
        } catch (RuntimeException e) {
            return created;
        }
    }

    /**
     * #124: a member's scan or apply leaves its snapshot in the fleet folder (and registers the service there when this
     * machine's member list lacks it). Never fails the command: one warning line instead.
     */
    static void snapshot(Path root, ScanResult r, Analysis a, Policy policy) {
        try {   // #133: the comment after `- fleet:<name>` says where it resolves on this machine
            FleetExtends.refresh(dev.logast.policy.PolicyLoader.defaultFile(root));
        } catch (RuntimeException e) {
            Out.warn("fleet: " + e.getMessage());
        }
        String name = NamedFleet.of(root);
        if (name == null || r == null || a == null) {
            return;
        }
        try {
            Path dir = FleetHome.fleetDir(FleetHome.Env.current(), name).dir();
            if (!NamedFleet.exists(dir)) {
                return;   // the policy's warning already says this machine does not know the fleet
            }
            if (!NamedFleet.members(dir).containsKey(NamedFleet.serviceName(root))) {
                NamedFleet.join(dir, name, root);
            }
            NamedFleet.write(dir, NamedFleet.snapshot(root, r, a, policy));
            Map<String, NamedFleet.Snapshot> snaps = NamedFleet.snapshots(dir);
            List<CompanyPolicy.Suggestion> suggestions = CompanyPolicy.refresh(dir, name, snaps.values());   // #129
            // #141: the fleet's page follows every member's snapshot
            List<FleetReport.Service> services = snaps.values().stream().map(NamedFleet.Snapshot::report).toList();
            FleetLinks.Result links = FleetLinks.of(services);
            FleetReport.writeDaily(dir.resolve("report"), services, links, suggestions.stream().map(CompanyPolicy.Suggestion::text).toList(),
                    dev.logast.fleet.FleetNext.steps(name, dir, NamedFleet.members(dir), NamedFleet.joined(dir), snaps, suggestions,
                            links, NamedFleet.vocabulary(dir, snaps.values())));
        } catch (RuntimeException e) {
            Out.warn("fleet " + name + ": no snapshot written (" + e.getMessage() + "); the fleet overview shows the previous one");
        }
    }

    /** #113: what each service's code would get renamed by the vocabulary (with --central: as if it extended company.yaml). */
    private int vocabulary(List<Path> roots) {
        List<String> extra = new ArrayList<>();
        if (central != null) {
            Path company = Path.of(central.toString().replaceFirst("^~(?=/|$)", System.getProperty("user.home")))
                    .resolve(CentralVocabulary.COMPANY).toAbsolutePath().normalize();
            if (!Files.exists(company)) {
                Out.warn("fleet: " + company + " does not exist; run `logast vocabulary --central " + central + " <service>…` first");
                return 2;
            }
            extra.add(company.toString());
        }
        for (Path root : roots) {
            Path r = root.toAbsolutePath().normalize();
            Main.migrate(r);
            Policy policy = dev.logast.policy.PolicyLoader.load(r, null, extra);
            Main.warnOnce(policy);
            String name = r.getFileName().toString();
            List<CentralVocabulary.Rename> renames = CentralVocabulary.renames(CentralVocabulary.uses(r, name), policy);
            System.out.println(name + ": " + (renames.isEmpty() ? "nothing to rename"
                    : renames.size() + (renames.size() == 1 ? " key" : " keys") + " to rename"));
            for (CentralVocabulary.Rename rn : renames) {
                System.out.println("  " + rn.from() + " -> " + rn.to() + " (" + rn.places().size()
                        + (rn.places().size() == 1 ? " place" : " places") + ")");
                rn.places().forEach(u -> System.out.println("    " + u.file() + ":" + u.line() + " " + u.kind()));
            }
        }
        return 0;
    }

    private List<Path> roots() throws IOException {
        List<Path> roots = new ArrayList<>();
        for (String s : services) {
            boolean listFile = s.startsWith("@") || Files.isRegularFile(Path.of(s));   // a file of service roots, one per line
            if (listFile) {
                Files.readAllLines(Path.of(s.startsWith("@") ? s.substring(1) : s)).stream().map(String::trim).filter(l -> !l.isEmpty() && !l.startsWith("#"))
                        .map(Path::of).forEach(roots::add);
            } else {
                roots.add(Path.of(s));
            }
        }
        return roots;
    }

    // ---- subcommands ---------------------------------------------------------------------------------------------------
}
