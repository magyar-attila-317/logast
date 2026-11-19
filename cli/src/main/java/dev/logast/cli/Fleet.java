package dev.logast.cli;

import dev.logast.Analysis;
import dev.logast.CentralVocabulary;
import dev.logast.Logast;
import dev.logast.fleet.FleetHome;
import dev.logast.fleet.FleetLinks;
import dev.logast.fleet.FleetReport;
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
        + "The earlier form `logast fleet <service roots…>` still scans the given roots.",
        subcommands = {Fleet.Create.class, Fleet.Join.class, Fleet.Leave.class, Fleet.ListFleets.class})
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
    @Option(names = "--refresh", description = "Named fleet: rescan every member first (default: the snapshots their last scan or apply left)")
    boolean refresh;
    @Option(names = "--html", description = "Named fleet: also write the report page (<fleet folder>/report/fleet.html, or --out)")
    boolean html;
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
        System.err.println("scanning " + r);
        ScanResult scan = Logast.scan(r, Logast.classpath(r, classpathFile), policy);
        return NamedFleet.snapshot(r, scan, Logast.lastAnalysis, policy);
    }

    // ---- logast fleet <name> -----------------------------------------------------------------------------------------

    private int named(String name, Path dir) throws IOException {
        Map<String, Path> members = NamedFleet.members(dir);
        if (refresh) {
            for (var m : members.entrySet()) {
                if (!Files.isDirectory(m.getValue())) {
                    Out.warn("fleet: " + m.getKey() + " is not at " + m.getValue() + " on this machine; its snapshot stays as it was");
                    continue;
                }
                NamedFleet.write(dir, scan(m.getValue()));
            }
        }
        Map<String, NamedFleet.Snapshot> snaps = NamedFleet.snapshots(dir);
        List<FleetReport.Service> services = snaps.values().stream().map(NamedFleet.Snapshot::report).toList();
        FleetLinks.Result links = FleetLinks.of(services);
        CentralVocabulary.Result vocab = NamedFleet.vocabulary(dir, snaps.values());
        if (vocabulary) {
            Files.writeString(dir.resolve(CentralVocabulary.WORKSHEET), vocab.worksheet());
            Files.writeString(dir.resolve(CentralVocabulary.COMPANY), vocab.company());
        }
        Path page = html ? FleetReport.write(out != null ? out : dir.resolve("report"), services, links) : null;
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
            String pad = String.format("  %-" + w + "s", s);
            if (snap == null) {
                System.out.println(pad + "no snapshot yet: run `logast scan` in " + members.get(s));
                continue;
            }
            FleetReport.Service r = snap.report();
            System.out.println(pad + String.format("score %5.1f  open %3d (high %d, medium %d, low %d)  snapshot %s%s%s", r.score(),
                    open(r), r.severity().getOrDefault("high", 0), r.severity().getOrDefault("medium", 0),
                    r.severity().getOrDefault("low", 0), age(snap.created(), now), snap.commit() == null ? "" : ", " + snap.commit(),
                    members.containsKey(s) ? "" : " (not a member any more)"));
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
        if (vocabulary) {
            System.out.println("worksheet: " + worksheet + " (decide there, then run this again)");
            System.out.println("company:   " + dir.resolve(CentralVocabulary.COMPANY) + " (" + vocab.terms()
                    + (vocab.terms() == 1 ? " term" : " terms") + ", generated; every member extends it)");
        } else {
            System.out.println("  `logast fleet " + name + " --vocabulary` " + (Files.exists(worksheet) ? "updates" : "writes")
                    + " the worksheet " + worksheet + " and company.yaml");
        }
        if (page != null) {
            System.out.println("report: " + page.toAbsolutePath());
        }
        return 0;
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
            if (!FleetHome.validName(name) || Set.of("create", "join", "leave", "list").contains(name)) {
                Out.warn("fleet create: " + name + " is not a fleet name (letters, digits, '.', '-', '_'; not a subcommand)");
                return 2;
            }
            FleetHome.Env env = fleetsDir.env();
            boolean existed = NamedFleet.exists(dir != null ? dir.toAbsolutePath().normalize() : FleetHome.fleetDir(env, name).dir());
            Path at = NamedFleet.create(env, name, dir);
            System.out.println("fleet " + name + (existed ? " (already there): " : ": ") + at);
            System.out.println("next: in each service, `logast fleet join " + name + "`; then `logast fleet " + name + "` compares them");
            return 0;
        }
    }

    @Command(name = "join", description = "Make the service a member: writes `fleet: <name>` into its logast.yaml (the fleet's "
            + "company.yaml then counts as extended) and registers it in the fleet folder; its scans and applies report to the fleet")
    static final class Join implements Callable<Integer> {
        @Parameters(index = "0", description = "The fleet's name")
        String name;
        @Parameters(index = "1", defaultValue = ".", description = "Service root (default: .)")
        Path root;
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
                    + (Files.exists(at.dir().resolve(CentralVocabulary.COMPANY)) ? " (extends the fleet's company.yaml)"
                    : " (the fleet has no company.yaml yet: `logast fleet " + name + " --vocabulary` writes it)"));
            System.out.println("  every `logast scan` and `apply` here now leaves a snapshot in the fleet folder");
            return 0;
        }
    }

    @Command(name = "leave", description = "Take the service out of its fleet: the `fleet:` line, its member entry and snapshot go")
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
