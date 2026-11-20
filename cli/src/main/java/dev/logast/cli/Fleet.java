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
import dev.logast.fleet.ServiceSurface;
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
        subcommands = {Fleet.Create.class, Fleet.Init.class, Fleet.Join.class, Fleet.Leave.class, Fleet.Add.class, Fleet.Remove.class, Fleet.ListFleets.class,
                Fleet.Tidy.class})
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
    @Option(names = "--kb-full", description = "With --kb: export every member, also those unchanged since their last export (#184)")
    boolean kbFull;
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
    @Option(names = "--service", description = "Named fleet: what `logast scan` printed for this member, from its stored results")
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
        System.err.println("scanning " + r);
        List<Path> cp = Logast.classpath(r, classpathFile);
        ScanResult scan = Logast.scan(r, cp, policy);
        lastScanned = new dev.logast.export.Export.Analysed(Logast.lastAnalysis, scan, cp);   // #184: the export reuses it
        lastScannedRoot = r;
        return NamedFleet.snapshot(r, scan, Logast.lastAnalysis, policy);
    }

    /** #184: the analysis of the member scanned last (the refresh goes one member at a time), for its export. */
    static dev.logast.export.Export.Analysed lastScanned;
    static Path lastScannedRoot;

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
        System.err.println("scanning " + r + " (read-only; results in " + out + ")");
        ScanResult scan = Logast.scan(r, cp, policy);
        Analysis a = Logast.lastAnalysis;
        lastScanned = new dev.logast.export.Export.Analysed(a, scan, cp);   // #184: the export reuses it
        lastScannedRoot = r;
        Path orders = LogastDir.in(out, LogastDir.ORDERS);
        Files.createDirectories(orders.getParent());
        OrderJson.MAPPER.writeValue(orders.toFile(), scan);
        if (policy.packSelected("logging")) {
            a.ledger.writeTo(LogastDir.in(out, LogastDir.COVERAGE), a, scan.orders(), Map.of());
        }
        Reports.writeTo(out, a, dev.logast.rules.Rules.lastLifecycle);
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

    private dev.logast.export.Export.FlywayOptions flywayOptions() {
        return flyway ? new dev.logast.export.Export.FlywayOptions(rows, postgresImage) : null;
    }

    private String kbOptions() throws IOException {
        return KbExport.optionsKey(McpCmd.build(), flywayOptions(), classpathFile, noBuilds);   // a new logast build re-exports
    }

    /** One member's facts into {@code kb/<member>/}, unless its commit and the options are those of its last export. */
    private void exportMember(Path dir, String name, Path root, dev.logast.export.Export.Analysed analysed, Path analysedRoot) {
        Path out = dir.resolve("kb").resolve(name);
        try {
            String commit = dev.logast.fleet.NamedFleet.commit(root);
            String options = kbOptions();
            if (!kbFull && KbExport.upToDate(out, commit, options)) {
                System.err.println("fleet --kb: " + name + ": unchanged since its last export (" + commit + "), kept");
                return;
            }
            Path ar = analysedRoot == null ? null : analysedRoot.toAbsolutePath().normalize();
            dev.logast.export.RepoExport.Result r = KbExport.repo(root, name, out, dir.resolve(".cache/kb-classpath").resolve(name),
                    classpathFile, noBuilds, flywayOptions(), List.of(), List.of(),
                    rootDir -> analysed != null && rootDir.toAbsolutePath().normalize().equals(ar) ? analysed : null);
            System.err.println("fleet --kb: " + name + ": " + r.files().size() + " facts file(s) in " + out);
            r.problems().forEach(p2 -> System.err.println("fleet --kb: " + name + ": " + p2));
            if (r.problems().isEmpty()) {
                KbExport.stamp(out, commit, options);
            } else {   // #187: a partial export is not done: the next run exports it again, once its cause is fixed
                Files.deleteIfExists(out.resolve(KbExport.STAMP));
                System.err.println("fleet --kb: " + name + ": " + r.problems().size() + " problem(s), so it is exported again next run");
            }
        } catch (IOException | RuntimeException e) {
            Out.warn("fleet --kb: " + name + " not exported (" + e.getMessage() + ")");
        }
    }

    private void knowledgeBase(Path dir, Map<String, Path> members) throws IOException {
        Path kbDir = dir.resolve("kb");
        Files.createDirectories(kbDir);
        List<java.util.concurrent.Callable<Void>> work = new java.util.ArrayList<>();
        for (var m : members.entrySet()) {
            if (kbDone.contains(m.getKey())) {
                continue;
            }
            if (!Files.isDirectory(m.getValue())) {
                Out.warn("fleet --kb: " + m.getKey() + " is not at " + m.getValue() + " on this machine; its facts stay as they were");
                continue;
            }
            work.add(() -> {
                System.err.println("fleet --kb: exporting " + m.getKey());
                exportMember(dir, m.getKey(), m.getValue(), null, null);
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
        KbExport.mappings(kbDir).forEach(l -> System.err.println("fleet --kb: " + l));
        System.err.println("fleet --kb: knowledge base in " + kbDir + "; `logast mcp config --kb " + kbDir + "` connects an MCP client");
    }

    // ---- logast fleet <name> -----------------------------------------------------------------------------------------

    private int named(String name, Path dir) throws IOException {
        if (refresh && noBuilds) {
            Out.warn("fleet: --no-builds: added members are scanned from their source only (survey quality: calls through "
                    + "library types may stay unresolved)");
        }
        if (refresh) {
            // #147: the members repos.yaml lists by URL: cloned or updated, then added (read-only) like `fleet add`
            for (FleetRepos.Repo repo : FleetRepos.list(dir)) {
                try (Out.Progress p = new Out.Progress("updating " + repo.name() + " from " + repo.url())) {
                    NamedFleet.add(dir, FleetRepos.sync(dir, repo));
                } catch (RuntimeException e) {
                    Out.warn("fleet: " + repo.name() + " not updated (" + e.getMessage() + "); its snapshot stays as it was");
                }
            }
        }
        Map<String, Path> members = NamedFleet.members(dir);
        Map<String, Path> added = NamedFleet.added(dir);
        if (refresh) {
            for (var m : members.entrySet()) {
                if (!Files.isDirectory(m.getValue())) {
                    Out.warn("fleet: " + m.getKey() + " is not at " + m.getValue() + " on this machine; its snapshot stays as it was");
                    continue;
                }
                String svc = m.getKey();
                Path root = m.getValue();
                lastScanned = null;
                skipOnFailure(svc, () -> NamedFleet.write(dir, added.containsKey(svc) ? scanAdded(dir, svc, root, classpathFile, noBuilds)
                        : scan(root)));
                if (kb && lastScanned != null) {   // #184: exported now, with the analysis the scan just made
                    exportMember(dir, svc, root, lastScanned, lastScannedRoot);
                    kbDone.add(svc);
                }
                lastScanned = null;
            }
        }
        if (!refresh) {
            // #150: a member with commits since its snapshot is rescanned, so the overview never shows an old state of it
            Map<String, NamedFleet.Snapshot> before = NamedFleet.snapshots(dir);
            for (var m : members.entrySet()) {
                NamedFleet.Snapshot snap = before.get(m.getKey());
                if (snap == null || snap.commit() == null || !Files.isDirectory(m.getValue())) {
                    continue;
                }
                String head = NamedFleet.commit(m.getValue());
                if (head != null && !head.equals(snap.commit())) {
                    String svc = m.getKey();
                    Path root = m.getValue();
                    System.err.println("fleet: " + svc + " has new commits since its snapshot (" + snap.commit() + " → " + head
                            + "): rescanning it");
                    skipOnFailure(svc, () -> NamedFleet.write(dir, added.containsKey(svc)
                            ? scanAdded(dir, svc, root, classpathFile, noBuilds) : scan(root)));
                }
            }
        }
        if (kb) {
            knowledgeBase(dir, members);
        }
        if (service != null) {
            return service(name, dir, service, members, added);
        }
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
        Instant now = Instant.now();
        if (json) {
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
        System.out.println(Ansi.bold("fleet " + name) + Ansi.dim("  " + dir + "  (" + members.size()
                + (members.size() == 1 ? " member)" : " members)")));
        Set<String> names = new java.util.TreeSet<>(members.keySet());
        names.addAll(snaps.keySet());
        int w = names.stream().mapToInt(String::length).max().orElse(8) + 2;
        for (String s : names) {
            NamedFleet.Snapshot snap = snaps.get(s);
            // #132: added (the repo knows nothing of the fleet) or joined (fleet: in its logast.yaml)
            String pad = String.format("  %-" + w + "s%-8s", s, !members.containsKey(s) ? "" : added.containsKey(s) ? "added" : "joined");
            if (snap == null) {
                System.out.println(pad + (added.containsKey(s) ? "no snapshot yet: `logast fleet " + name + " --refresh` scans it"
                        : "no snapshot yet: run `logast scan` in " + members.get(s)));
                continue;
            }
            FleetReport.Service r = snap.report();
            System.out.println(pad + String.format("score %5.1f  open %3d (high %d, medium %d, low %d)  snapshot %s%s%s", r.score(),
                    open(r), r.severity().getOrDefault("high", 0), r.severity().getOrDefault("medium", 0),
                    r.severity().getOrDefault("low", 0), age(snap.created(), now), snap.commit() == null ? "" : ", " + snap.commit(),
                    members.containsKey(s) ? "" : " (not a member any more)"));
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
        System.out.println();
        System.out.print(FleetLinks.text(links));
        System.out.println();
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
        System.out.println();
        if (next.isEmpty()) {
            System.out.println(Ansi.bold("Next:") + " nothing left: the members follow the company policy and nothing high is open");
        } else {
            System.out.println(Ansi.bold("Next:"));
            for (int i = 0; i < next.size(); i++) {
                System.out.println("  " + (i + 1) + ". " + next.get(i));
            }
        }
        // #149: a file:// URL, clickable in a terminal; --open shows it in the browser
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

    @Command(name = "create", description = "Create a named fleet: a folder for its members' snapshots and its shared company.yaml")
    static final class Create implements Callable<Integer> {
        @Parameters(index = "0", description = "The fleet's name (letters, digits, '.', '-', '_')")
        String name;
        @Option(names = "--dir", description = "Keep this fleet in this folder (a synced or git-tracked one); the user config remembers it")
        Path dir;
        @Mixin
        FleetsDir fleetsDir;

        @Override
        public Integer call() {
            if (!FleetHome.validName(name) || Set.of("create", "init", "join", "leave", "add", "remove", "list", "tidy").contains(name)) {
                Out.warn("fleet create: " + name + " is not a fleet name (letters, digits, '.', '-', '_'; not a subcommand)");
                return 2;
            }
            FleetHome.Env env = fleetsDir.env();
            boolean existed = NamedFleet.exists(dir != null ? dir.toAbsolutePath().normalize() : FleetHome.fleetDir(env, name).dir());
            Path at = NamedFleet.create(env, name, dir);
            System.out.println("fleet " + name + (existed ? " (already there): " : ": ") + at);
            System.out.println("  company.yaml: the policy every member extends; its settings are commented out until you "
                    + "decide them: " + at.resolve(CentralVocabulary.COMPANY));
            System.out.println("next: in each service, `logast fleet join " + name + "`; then `logast fleet " + name + "` compares them");
            return 0;
        }
    }

    @Command(name = "init", description = "Make this folder (a git repo) the fleet's home: company.yaml, repos.yaml (the members by "
            + "URL), a README for the owner and the service devs, and a GitHub workflow that refreshes the report (#147)")
    static final class Init implements Callable<Integer> {
        @Parameters(index = "0", description = "The fleet's name (letters, digits, '.', '-', '_')")
        String name;
        @Option(names = "--dir", defaultValue = ".", description = "The folder (default: the current one)")
        Path dir;
        @Mixin
        FleetsDir fleetsDir;

        @Override
        public Integer call() throws Exception {
            if (!FleetHome.validName(name) || Set.of("create", "init", "join", "leave", "add", "remove", "list", "tidy").contains(name)) {
                Out.warn("fleet init: " + name + " is not a fleet name (letters, digits, '.', '-', '_'; not a subcommand)");
                return 2;
            }
            Path at = NamedFleet.create(fleetsDir.env(), name, dir.toAbsolutePath().normalize());
            Map<String, Boolean> written = FleetScaffold.write(at, name, logastRef());
            System.out.println("fleet " + name + ": " + at);
            System.out.println("  company.yaml   the settings every member follows (commented out until you decide them)");
            written.forEach((f, w) -> {
                if (f.endsWith("settings.gradle.kts")) {
                    return;
                }
                System.out.println("  " + String.format("%-14s ", f.equals(FleetScaffold.WORKFLOW) ? "workflow"
                        : f.equals(FleetScaffold.CLASSPATH_BUILD) ? "classpath/" : f) + (w ? "" : "(kept: it existed) ") + switch (f) {
                    case FleetRepos.FILE -> "the members by repo URL: add yours";
                    case "README.md" -> "what the owner and the service devs do";
                    case ".gitignore" -> "clones, snapshots and the report are generated";
                    case FleetScaffold.CLASSPATH_BUILD -> "the libraries the workflow resolves the members' code against "
                            + "(their own builds never run in CI)";
                    default -> f + ": refreshes the report on push; Pages stays off until LOGAST_PAGES is true (README)";
                });
            });
            System.out.println("next: list the repos in " + FleetRepos.FILE + ", then `logast fleet . --refresh` here; commit and push");
            return 0;
        }

        /** The logast commit the workflow builds: this one's, else main. */
        static String logastRef() {
            try {
                String v = new Main.Version().getVersion()[0];
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("([0-9a-f]{7,40})(?:-dirty)?\\)").matcher(v);
                return m.find() ? m.group(1) : "main";
            } catch (Exception e) {
                return "main";
            }
        }
    }

    @Command(name = "join", description = "Make the service a member: writes `fleet: <name>` and `extends: [fleet:<name>]` into its "
            + "logast.yaml (the fleet's company.yaml is extended) and registers it in the fleet folder; its scans and applies report to "
            + "the fleet. Settings the service sets to company.yaml's value, and vocabulary company.yaml defines, are removed from "
            + "its logast.yaml; its own overrides stay and are listed")
    static final class Join implements Callable<Integer> {
        @Parameters(index = "0", description = "The fleet's name")
        String name;
        @Parameters(index = "1", defaultValue = ".", description = "Service root (default: .)")
        Path root;
        @Option(names = "--follow-company", description = "Also remove the settings where logast.yaml differs from company.yaml "
                + "(the service's own overrides): the company's values apply")
        boolean followCompany;
        @Mixin
        FleetsDir fleetsDir;

        @Override
        public Integer call() {
            FleetHome.Env env = fleetsDir.env();
            FleetHome.Location at = FleetHome.fleetDir(env, name);
            if (!NamedFleet.exists(at.dir())) {
                Out.warn("fleet join: no fleet named " + name + " at " + at.dir() + " (" + at.source() + "); `logast fleet create "
                        + name + "` makes it");
                return 2;
            }
            Path r = root.toAbsolutePath().normalize();
            String before = NamedFleet.of(r);
            if (before != null && !before.equals(name)) {
                Out.warn("fleet join: " + NamedFleet.serviceName(r) + " is in fleet " + before + "; `logast fleet leave` first");
                return 2;
            }
            Path moved = NamedFleet.join(at.dir(), name, r);
            if (moved != null) {
                Out.warn("fleet join: " + NamedFleet.serviceName(r) + " was registered at " + moved + "; now at " + r);
            }
            System.out.println(NamedFleet.serviceName(r) + " joined fleet " + name + " (" + at.dir() + ")");
            System.out.println("  " + dev.logast.policy.PolicyLoader.defaultFile(r).getFileName() + ": fleet: " + name
                    + ", extends: [" + FleetExtends.PREFIX + name + "]"
                    + (Files.exists(at.dir().resolve(CentralVocabulary.COMPANY)) ? " (extends the fleet's company.yaml)"
                    : " (the fleet has no company.yaml yet: `logast fleet " + name + " --vocabulary` writes it)"));
            tidy(r, at.dir(), followCompany, "logast fleet join " + name + " --follow-company");
            System.out.println("  every `logast scan` and `apply` here now leaves a snapshot in the fleet folder");
            return 0;
        }
    }

    /** #133: join's and tidy's report of one member's tidy: what went, which overrides stay. */
    static void tidy(Path root, Path fleetDir, boolean followCompany, String follow) {
        String svc = NamedFleet.serviceName(root);
        FleetTidy.Result t = FleetTidy.apply(root, fleetDir.resolve(CentralVocabulary.COMPANY), followCompany);
        t.removed().forEach(l -> System.out.println("  " + svc + ": removed " + l));
        t.kept().forEach(s -> System.out.println("  " + svc + " keeps its own " + s.path() + ": " + s.own() + " (company: "
                + s.company() + "); `" + follow + "` drops it"));
    }

    @Command(name = "tidy", description = "Tidy every joined member's logast.yaml against the fleet's company.yaml (#133), as "
            + "`fleet join` does: settings with company.yaml's value and vocabulary it defines are removed, own overrides listed. "
            + "Only each member's logast.yaml changes")
    static final class Tidy implements Callable<Integer> {
        @Parameters(index = "0", description = "The fleet's name")
        String name;
        @Option(names = "--follow-company", description = "Also remove the members' own overrides: the company's values apply")
        boolean followCompany;
        @Option(names = "--service", description = "Only this member")
        String service;
        @Option(names = "--force", hidden = true, description = "No longer needed: uncommitted changes of logast.yaml don't block the tidy")
        boolean force;
        @Mixin
        FleetsDir fleetsDir;

        @Override
        public Integer call() {
            FleetHome.Location at = FleetHome.fleetDir(fleetsDir.env(), name);
            if (!NamedFleet.exists(at.dir())) {
                Out.warn("fleet tidy: no fleet named " + name + " at " + at.dir() + " (" + at.source() + ")");
                return 2;
            }
            Map<String, Path> joined = NamedFleet.joined(at.dir());
            if (service != null && !joined.containsKey(service)) {
                Out.warn("fleet tidy: " + service + " is no joined member of fleet " + name + " (joined: "
                        + String.join(", ", joined.keySet()) + ")");
                return 2;
            }
            int exit = 0;
            for (var m : joined.entrySet()) {
                if (service != null && !service.equals(m.getKey())) {
                    continue;
                }
                Path file = dev.logast.policy.PolicyLoader.defaultFile(m.getValue());
                if (!Files.isRegularFile(file)) {
                    Out.warn("fleet tidy: " + m.getKey() + ": no " + file + " on this machine");
                    exit = 1;
                    continue;
                }
                if (FleetTidy.plan(m.getValue(), at.dir().resolve(CentralVocabulary.COMPANY)).isEmpty()) {
                    System.out.println(m.getKey() + ": nothing to tidy");
                    continue;
                }
                // logast.yaml is logast's own file (init, join and scan write it, apply's guard leaves it out too): its
                // uncommitted changes don't block the tidy, they are named so the diff is read with them in mind
                boolean wasDirty = dirty(m.getValue(), file);
                System.out.println(m.getKey() + ":");
                tidy(m.getValue(), at.dir(), followCompany, "logast fleet tidy " + name + " --follow-company");
                if (wasDirty) {
                    System.out.println("  (" + file.getFileName() + " had uncommitted changes already: `git diff " + file.getFileName()
                            + "` shows them together with the tidy)");
                }
            }
            return exit;
        }

        /** True when git shows uncommitted changes of {@code file} (untracked included); false outside a git work tree. */
        private static boolean dirty(Path root, Path file) {
            try {
                Process p = new ProcessBuilder("git", "status", "--porcelain", "--", file.getFileName().toString())
                        .directory(root.toFile()).redirectError(ProcessBuilder.Redirect.DISCARD).start();
                String out = new String(p.getInputStream().readAllBytes()).strip();
                return p.waitFor() == 0 && !out.isEmpty();
            } catch (IOException e) {
                return false;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }

    @Command(name = "leave", description = "Take the service out of its fleet: the `fleet:` line and the `extends:` entry "
            + "`fleet:<name>`, its member entry and snapshot go")
    static final class Leave implements Callable<Integer> {
        @Parameters(index = "0", defaultValue = ".", description = "Service root (default: .)")
        Path root;
        @Mixin
        FleetsDir fleetsDir;

        @Override
        public Integer call() {
            Path r = root.toAbsolutePath().normalize();
            String name = NamedFleet.of(r);
            if (name == null) {
                Out.warn("fleet leave: " + NamedFleet.serviceName(r) + " is in no fleet (its logast.yaml has no `fleet:`)");
                return 2;
            }
            NamedFleet.leave(FleetHome.fleetDir(fleetsDir.env(), name).dir(), r);
            System.out.println(NamedFleet.serviceName(r) + " left fleet " + name);
            return 0;
        }
    }

    @Command(name = "add", description = "Register repos in a fleet without touching them (#132): nothing is written into a repo, "
            + "no logast.yaml needed; their policy is the fleet's company.yaml (under the repo's own logast.yaml if it has one), "
            + "and their scans (--scan, `logast fleet <name> --refresh`) write only to <fleet>/services/<service>/")
    static final class Add implements Callable<Integer> {
        @Parameters(index = "0", description = "The fleet's name")
        String name;
        @Parameters(index = "1..*", arity = "0..*", description = "Repo roots (shell globs work)")
        List<Path> repos = new ArrayList<>();
        @Option(names = "--from", description = "A text file with one repo root per line ('#' starts a comment)")
        Path from;
        @Option(names = "--scan", description = "Scan the added repos right away (read-only: the results go to the fleet folder)")
        boolean scan;
        @Option(names = "--classpath-file", description = "With --scan: one classpath file for every repo (default: each repo's own Gradle)")
        Path classpathFile;
        @Mixin
        FleetsDir fleetsDir;

        @Override
        public Integer call() throws IOException {
            FleetHome.Location at = FleetHome.fleetDir(fleetsDir.env(), name);
            if (!NamedFleet.exists(at.dir())) {
                Out.warn("fleet add: no fleet named " + name + " at " + at.dir() + " (" + at.source() + "); `logast fleet create "
                        + name + "` makes it");
                return 2;
            }
            List<Path> all = new ArrayList<>(repos);
            if (from != null) {
                Files.readAllLines(from).stream().map(String::strip).filter(l -> !l.isEmpty() && !l.startsWith("#")).map(Path::of).forEach(all::add);
            }
            if (all.isEmpty()) {
                Out.warn("fleet add: name the repos (paths, globs, or --from <file>)");
                return 2;
            }
            int rc = 0;
            Map<String, Path> toScan = new LinkedHashMap<>();
            for (Path repo : all) {
                Path r = repo.toAbsolutePath().normalize();
                String svc = NamedFleet.serviceName(r);
                if (!Files.isDirectory(r)) {
                    Out.warn("fleet add: " + r + " is not a folder; skipped");
                    rc = 2;
                    continue;
                }
                switch (NamedFleet.add(at.dir(), r)) {
                    case ADDED -> System.out.println(svc + " added to fleet " + name + " (" + r + ")");
                    case AGAIN -> System.out.println(svc + " is in fleet " + name + " already (" + r + ")");
                    case MOVED -> System.out.println(svc + " added to fleet " + name + ", now at " + r);
                    case JOINED_ALREADY -> {
                        System.out.println(svc + " joined fleet " + name + " already: its own scans report to it");
                        continue;
                    }
                }
                toScan.put(svc, r);
            }
            System.out.println("  nothing is written into the repos; their policy: logast defaults → " + at.dir().resolve(CentralVocabulary.COMPANY)
                    + " → the repo's own logast.yaml, if it has one");
            if (scan) {
                for (var e : toScan.entrySet()) {
                    if (skipOnFailure(e.getKey(), () -> NamedFleet.write(at.dir(), scanAdded(at.dir(), e.getKey(), e.getValue(), classpathFile)))) {
                        System.out.println("  " + e.getKey() + ": scanned, results in " + NamedFleet.outputDir(at.dir(), e.getKey()));
                    }
                }
                CompanyPolicy.refresh(at.dir(), name, NamedFleet.snapshots(at.dir()).values());
            }
            System.out.println("next: `logast fleet " + name + "` compares the members" + (scan ? "" : " (`--refresh` scans them first)")
                    + "; `logast fleet " + name + " --service <service>` shows one member's findings");
            return rc;
        }
    }

    @Command(name = "remove", description = "Take a member `fleet add` registered out of the fleet: its entry, snapshot and results in "
            + "the fleet folder go (a joined member leaves with `logast fleet leave`)")
    static final class Remove implements Callable<Integer> {
        @Parameters(index = "0", description = "The fleet's name")
        String name;
        @Parameters(index = "1..*", arity = "1..*", description = "Repo roots or service names")
        List<String> members = new ArrayList<>();
        @Mixin
        FleetsDir fleetsDir;

        @Override
        public Integer call() {
            FleetHome.Location at = FleetHome.fleetDir(fleetsDir.env(), name);
            if (!NamedFleet.exists(at.dir())) {
                Out.warn("fleet remove: no fleet named " + name + " at " + at.dir() + " (" + at.source() + ")");
                return 2;
            }
            int rc = 0;
            for (String m : members) {
                String removed = NamedFleet.remove(at.dir(), m);
                if (removed != null) {
                    System.out.println(removed + " removed from fleet " + name);
                } else if (NamedFleet.joined(at.dir()).containsKey(m) || NamedFleet.joined(at.dir()).containsValue(Path.of(m).toAbsolutePath().normalize())) {
                    Out.warn("fleet remove: " + m + " joined fleet " + name + " (fleet: in its logast.yaml): `logast fleet leave <root>` takes it out");
                    rc = 2;
                } else {
                    Out.warn("fleet remove: " + m + " is no added member of fleet " + name);
                    rc = 2;
                }
            }
            return rc;
        }
    }

    @Command(name = "list", description = "The fleets this machine knows, with their folders and members")
    static final class ListFleets implements Callable<Integer> {
        @Mixin
        FleetsDir fleetsDir;

        @Override
        public Integer call() {
            FleetHome.Env env = fleetsDir.env();
            FleetHome.Location home = FleetHome.fleetsDir(env);
            System.out.println("fleets folder: " + home.dir() + " (" + home.source() + ")");
            Map<String, Path> fleets = NamedFleet.list(env);
            if (fleets.isEmpty()) {
                System.out.println("no fleets yet: `logast fleet create <name>`");
            }
            fleets.forEach((n, d) -> {
                Map<String, Path> members = NamedFleet.members(d);
                System.out.println("  " + n + "  " + d + "  " + members.size() + (members.size() == 1 ? " member" : " members")
                        + (members.isEmpty() ? "" : ": " + String.join(", ", members.keySet())));
            });
            return 0;
        }
    }
}
