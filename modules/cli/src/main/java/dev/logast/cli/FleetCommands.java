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

/** The fleet subcommands that manage members and folders: create, init, join, tidy, leave, add, remove, list. */
final class FleetCommands {
    private FleetCommands() {
    }

    @Command(name = "create", description = "Create a named fleet: a folder for its members' snapshots and its shared company.yaml")
    static final class Create implements Callable<Integer> {
        @Parameters(index = "0", description = "The fleet's name (letters, digits, '.', '-', '_')")
        String name;
        @Option(names = "--dir", description = "Keep this fleet in this folder (a synced or git-tracked one); the user config remembers it")
        Path dir;
        @Mixin
        Fleet.FleetsDir fleetsDir;

        @Override
        public Integer call() {
            if (!FleetHome.validName(name) || Set.of("create", "init", "join", "leave", "add", "remove", "list", "tidy", "rollout", "tag-policy").contains(name)) {
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
        Fleet.FleetsDir fleetsDir;

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
        Fleet.FleetsDir fleetsDir;

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
        Fleet.FleetsDir fleetsDir;

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
        Fleet.FleetsDir fleetsDir;

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
        Fleet.FleetsDir fleetsDir;

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
                    if (Fleet.skipOnFailure(e.getKey(), () -> NamedFleet.write(at.dir(), Fleet.scanAdded(at.dir(), e.getKey(), e.getValue(), classpathFile)))) {
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
        Fleet.FleetsDir fleetsDir;

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
        Fleet.FleetsDir fleetsDir;

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
