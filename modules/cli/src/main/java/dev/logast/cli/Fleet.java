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
    /** The knowledge-base export of this run (--kb). */
    final FleetKnowledgeBase kbExport = new FleetKnowledgeBase(this);

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
        FleetScans.lastScanned.set(new dev.logast.export.Export.Analysed(Logast.LAST_ANALYSIS.get(), scan, cp));   // #184: the export reuses it
        FleetScans.lastScannedRoot.set(r);
        return NamedFleet.snapshot(r, scan, Logast.LAST_ANALYSIS.get(), policy);
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
                + (snap == null ? "" : ", snapshot " + FleetOverview.age(snap.created(), Instant.now()) + (snap.commit() == null ? "" : ", " + snap.commit()))));
        System.out.print(dev.logast.orders.HumanFormat.render(r, r.orders(), snap == null ? null : snap.appPackage(), Out.width()));
        Path reports = orders.getParent();
        Main.printBlocks(List.of(Ansi.dim("reports: " + reports + "/ metrics.md queries.md lifecycle.txt coverage.txt")));
        return 0;
    }

    /** #182: every member's facts into {@code <fleet>/kb/<member>/}, then the fleet's mappings into {@code kb/mapped/}. */
    /** #184: members exported during this run's refresh (with the scan's analysis). */
    final java.util.Set<String> kbDone = java.util.concurrent.ConcurrentHashMap.newKeySet();

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

    /** #226: scans one member (and exports its knowledge base with that analysis); what prints its line in the phase. */
    private Runnable scanMember(Path dir, String svc, Path root, Map<String, Path> added, FleetLog.Phase phase, Out.Progress p) {
        long t0 = System.nanoTime();
        FleetScans.lastScanned.remove();
        String error = FleetScans.scanError(() -> NamedFleet.write(dir, added.containsKey(svc) ? FleetScans.scanAdded(dir, svc, root, classpathFile, noBuilds)
                : scan(root)));
        FleetKnowledgeBase.KbOutcome o = null;
        if (kb && FleetScans.lastScanned.get() != null) {   // #184: exported now, with the analysis the scan just made
            if (p != null) {
                p.update("~exporting its knowledge base");
            }
            o = kbExport.exportMember(dir, svc, root, FleetScans.lastScanned.get(), FleetScans.lastScannedRoot.get());
            kbDone.add(svc);
        }
        FleetScans.lastScanned.remove();
        long took = System.nanoTime() - t0;
        FleetKnowledgeBase.KbOutcome out = o;
        if (error != null) {
            return () -> phase.done(svc, FleetLog.Status.FAILED, took, "scan failed: " + FleetLog.oneLine(error) + " (its snapshot stays as it was; "
                    + "`logast scan` in the repo shows the whole error)", List.of());
        } else if (out == null) {
            return () -> phase.done(svc, FleetLog.Status.OK, took, "scanned", List.of());
        }
        return () -> phase.done(svc, out.status() == FleetLog.Status.SAME ? FleetLog.Status.OK : out.status(), took,
                "scanned · " + out.detail(), out.notes());
    }

    /** #223: the members a run works on: the one --service names (with --refresh/--kb), else all. */
    Map<String, Path> work(Map<String, Path> members) {
        if (service == null || !(refresh || kb)) {
            return members;
        }
        return members.containsKey(service) ? Map.of(service, members.get(service)) : Map.of();
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
            kbExport.tablesMade = KbExport.tablesMade(dir.resolve("kb"));
            kbExport.tableOwners = KbExport.tableOwners(dir.resolve("kb"));
        }
        if (refresh) {
            scanAll(dir, members, added, log);
        }
        if (!stale.isEmpty()) {
            rescan(dir, stale, members, added, log);
        }
        if (kb) {
            kbExport.knowledgeBase(dir, members, log);
        }
        if (log != null) {
            summary(dir, log);
        }
        if (service != null && !(refresh || kb)) {   // #223: with --refresh/--kb, --service only picks what is worked on
            return service(name, dir, service, members, added);
        }
        FleetOverview.Overview ov = FleetOverview.overview(name, dir, members, out, vocabulary);
        return json ? FleetOverview.printJson(name, dir, members, added, ov)
                : FleetOverview.printOverview(name, dir, members, added, ov, vocabulary, openPage);
    }

    /** The tables whose rows become facts: repos.yaml's per repo (#216) and what the fleet's mappings need (#215). */
    private void rowsToExport(Path dir) {
        Map<String, List<String>> asked = new java.util.TreeMap<>();
        for (FleetRepos.Repo r : FleetRepos.list(dir)) {
            if (!r.rows().isEmpty()) {
                asked.put(r.name(), r.rows());
            }
        }
        kbExport.repoRows = asked;
        for (String t : FleetKnowledgeBase.neededRows(dir.resolve("kb"))) {   // #215: what the fleet's mappings say they need
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
                FleetScans.skipOnFailure(svc, () -> NamedFleet.write(dir, added.containsKey(svc)
                        ? FleetScans.scanAdded(dir, svc, root, classpathFile, noBuilds) : scan(root)));
                continue;
            }
            long t0 = System.nanoTime();
            String error;
            try (Out.Progress p = phase.working(svc, "rescanning")) {
                error = FleetScans.scanError(() -> NamedFleet.write(dir, added.containsKey(svc)
                        ? FleetScans.scanAdded(dir, svc, root, classpathFile, noBuilds) : scan(root)));
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
