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

/**
 * `logast fleet --kb`: every member's facts exported into the fleet's kb/ (#182), with the tables their migrations make
 * (#205: members needing another's tables run again against them) and the rows repos.yaml asks for (#216). It reads the
 * export options of the fleet command it belongs to.
 */
final class FleetKnowledgeBase {
    private final Fleet f;

    FleetKnowledgeBase(Fleet f) {
        this.f = f;
    }

    /** #205: the tables the members' migrations create, for the migrations of others that need them (stand-ins). */
    volatile Map<String, List<String>> tablesMade = Map.of();
    /** #227: which member makes each table, to say whose tables a member's migrations ran against. */
    volatile Map<String, String> tableOwners = Map.of();

    /** #215: the tables the fleet's mappings ({@code kb/mappings/*.yaml}) need the f.rows of ({@code needs: {f.rows: […]}}). */
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

    /** #216: the f.rows each repo of repos.yaml asks for ({@code f.rows:}): repo name → tables. */
    Map<String, List<String>> repoRows = Map.of();

    /** A member's Flyway options: --f.flyway's, with the f.rows repos.yaml asks of it (which run its migrations alone too). */
    dev.logast.export.Export.FlywayOptions flywayOptions(String member) {
        List<String> own = repoRows.getOrDefault(member, List.of());
        if (!f.flyway && own.isEmpty()) {
            return null;
        }
        java.util.Set<String> all = new java.util.LinkedHashSet<>(f.rows);
        all.addAll(own);
        return new dev.logast.export.Export.FlywayOptions(List.copyOf(all), f.postgresImage, tablesMade);
    }

    private String kbOptions(String member) throws IOException {
        return KbExport.optionsKey(McpCmd.build(), flywayOptions(member), f.classpathFile, f.noBuilds);   // a new logast build re-exports
    }

    /** One member's facts into {@code kb/<member>/}, unless its commit and the options are those of its last export. */
    /** #193: what a member's export did, for its line. */
    record KbOutcome(FleetLog.Status status, String detail, List<String> notes) {
    }

    KbOutcome exportMember(Path dir, String name, Path root, dev.logast.export.Export.Analysed analysed, Path analysedRoot) {
        return exportMember(dir, name, root, analysed, analysedRoot, false);
    }

    KbOutcome exportMember(Path dir, String name, Path root, dev.logast.export.Export.Analysed analysed, Path analysedRoot,
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
            if (!again && !f.kbFull && KbExport.upToDate(out, commit, options) && !(fly != null && KbExport.needsChanged(out, tablesMade))) {
                return new KbOutcome(FleetLog.Status.SAME, "knowledge base unchanged since its last export (" + commit + "), kept", List.of());
            }
            Path ar = analysedRoot == null ? null : analysedRoot.toAbsolutePath().normalize();
            dev.logast.export.RepoExport.Result r = KbExport.repo(root, name, out, dir.resolve(".cache/kb-classpath").resolve(name),
                    f.classpathFile, f.noBuilds, fly, List.of(), List.of(),
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
                    notes.add("… and " + (byNeed.size() - 6) + " more (logast mcp: describe f.service:" + name + ")");
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

    void knowledgeBase(Path dir, Map<String, Path> members, FleetLog log) throws IOException {
        Map<String, Path> all = members;
        members = f.work(members);
        Path kbDir = dir.resolve("kb");
        Files.createDirectories(kbDir);
        Map<String, Path> todo = new java.util.LinkedHashMap<>();
        List<String> away = new java.util.ArrayList<>();
        for (var m : members.entrySet()) {
            if (f.kbDone.contains(m.getKey())) {
                continue;
            }
            if (!Files.isDirectory(m.getValue())) {
                away.add(m.getKey());
                continue;
            }
            todo.put(m.getKey(), m.getValue());
        }
        FleetLog.Phase phase = log.phase("knowledge base", todo.isEmpty() && away.isEmpty() ? "every repo was exported with its scan"
                : "the repos not exported with a scan (infra, charts, workflows, unchanged ones kept), " + Math.max(1, f.kbThreads)
                + " at a time" + (f.flyway ? "; each environment's migrations run in a throwaway PostgreSQL" : ""), todo.size() + away.size());
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
            var pool = java.util.concurrent.Executors.newFixedThreadPool(Math.max(1, f.kbThreads));
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
        if (f.flyway) {
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
    void dependencyRounds(Path dir, Map<String, Path> members, FleetLog log) {
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
}
