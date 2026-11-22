package dev.logast.fleet;

import dev.logast.Git;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import dev.logast.Analysis;
import dev.logast.CentralVocabulary;
import dev.logast.orders.OrderJson;
import dev.logast.orders.ScanResult;
import dev.logast.policy.Policy;
import dev.logast.policy.PolicyLoader;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * A named fleet (#124): a folder ({@link FleetHome}) with its members ({@value #MEMBERS}: service → root on this
 * machine), one snapshot per service ({@value #SERVICES}/&lt;service&gt;.json, written by every scan and apply of a
 * member) and the fleet's shared {@code company.yaml} with the vocabulary worksheet it is generated from. A service's
 * logast.yaml names the fleet ({@code fleet: <name>}); {@link PolicyLoader} layers the fleet's company.yaml under it.
 */
public final class NamedFleet {
    public static final String MEMBERS = "members.yaml";
    public static final String SERVICES = "services";
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private static final ObjectMapper READ = OrderJson.MAPPER.copy().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    private static final Pattern FLEET_LINE = Pattern.compile("fleet\\s*:.*");

    private NamedFleet() {
    }

    /**
     * What a member's scan or apply left in the fleet folder: what {@code logast fleet <paths…>} computes per service,
     * plus the vocabulary evidence the worksheet is built from, so the overview needs no rescan.
     */
    /** @param logast #195: the logast build that made it; a snapshot of another build is made again */
    public record Snapshot(String service, String root, String created, String commit, FleetReport.Service report,
                           List<CentralVocabulary.Use> vocabulary, List<CentralVocabulary.KnownArea> areas,
                           Settings settings, String appPackage, String logast) {
        public Snapshot {
            vocabulary = vocabulary == null ? List.of() : vocabulary;
            areas = areas == null ? List.of() : areas;
        }

        public Snapshot(String service, String root, String created, String commit, FleetReport.Service report,
                        List<CentralVocabulary.Use> vocabulary, List<CentralVocabulary.KnownArea> areas, Settings settings,
                        String appPackage) {
            this(service, root, created, commit, report, vocabulary, areas, settings, appPackage, null);
        }

        /** A snapshot without the application package (#132 added it: {@code fleet --service} shows paths below it). */
        public Snapshot(String service, String root, String created, String commit, FleetReport.Service report,
                        List<CentralVocabulary.Use> vocabulary, List<CentralVocabulary.KnownArea> areas, Settings settings) {
            this(service, root, created, commit, report, vocabulary, areas, settings, null);
        }
    }

    /**
     * #129: the member's effective settings a company usually decides (all layers merged, defaults included); null in
     * snapshots written before #129.
     */
    public record Settings(List<String> packs, String structuredFormat, String keyStyle, String supportMode) {
        public Settings {
            packs = packs == null ? List.of() : List.copyOf(packs);
        }

        /** The policy's values; packs in the usual order, so two members with the same packs agree. */
        public static Settings of(Policy p) {
            List<String> order = dev.logast.ServiceSetup.ALL_PACKS;
            List<String> packs = p.packs().stream().distinct().sorted(Comparator.comparingInt((String x) -> order.contains(x)
                    ? order.indexOf(x) : order.size()).thenComparing(Comparator.naturalOrder())).toList();
            return new Settings(packs, p.logging().structuredFormat(), p.logging().keyStyle(), p.support().mode());
        }
    }

    /** The fleet {@code root}'s logast.yaml names ({@code fleet: <name>}); null when it names none. */
    public static String of(Path root) {
        Path file = PolicyLoader.defaultFile(root);
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            JsonNode n = YAML.readTree(Files.readString(file));
            String name = n == null ? "" : n.path("fleet").asText("").strip();
            return name.isEmpty() ? null : name;
        } catch (IOException e) {
            return null;
        }
    }

    /** The service name a root has in fleets: its folder name. */
    public static String serviceName(Path root) {
        Path n = root.toAbsolutePath().normalize().getFileName();
        return n == null ? "root" : n.toString();
    }

    /**
     * Creates the fleet folder (with an empty member list) and its company.yaml (#129: the settings commented out, see
     * {@link CompanyPolicy}); an existing company.yaml is kept. With {@code dir}, the user config remembers it.
     */
    public static Path create(FleetHome.Env env, String name, Path dir) {
        if (!FleetHome.validName(name)) {
            throw new IllegalArgumentException("fleet name " + name + ": use letters, digits, '.', '-' and '_'");
        }
        Path fleet = dir != null ? env.expand(dir.toString()) : FleetHome.fleetDir(env, name).dir();
        try {
            Files.createDirectories(fleet.resolve(SERVICES));
            if (!Files.exists(fleet.resolve(MEMBERS))) {
                Files.writeString(fleet.resolve(MEMBERS), membersYaml(Map.of(), Map.of()));
            }
            if (!Files.exists(fleet.resolve(CentralVocabulary.COMPANY))) {
                Files.writeString(fleet.resolve(CentralVocabulary.COMPANY), CompanyPolicy.initial(name));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot create " + fleet, e);
        }
        if (dir != null || !fleet.equals(FleetHome.fleetDir(new FleetHome.Env(null, env.env(), env.home()), name).dir())) {
            // a folder the next run would not find by itself (--dir, or --fleets-dir for this run only): remember it
            FleetHome.remember(env, name, fleet);
        }
        return fleet;
    }

    /** True when {@code dir} is a fleet folder. */
    public static boolean exists(Path dir) {
        // #147: a fleet kept in a git repo has repos.yaml; its members.yaml (this machine's paths) is not committed
        return Files.isRegularFile(dir.resolve(MEMBERS)) || Files.isRegularFile(dir.resolve(FleetRepos.FILE));
    }

    /** The fleets this machine knows: folders under the fleets folder and the ones the user config remembers. */
    public static Map<String, Path> list(FleetHome.Env env) {
        Map<String, Path> out = new TreeMap<>();
        Path home = FleetHome.fleetsDir(env).dir();
        if (Files.isDirectory(home)) {
            try (Stream<Path> s = Files.list(home)) {
                s.filter(NamedFleet::exists).forEach(p -> out.put(p.getFileName().toString(), p));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        out.putAll(FleetHome.remembered(env));
        return out;
    }

    /** The members, joined and added: service → root. */
    public static Map<String, Path> members(Path fleet) {
        Map<String, Path> out = new TreeMap<>(section(fleet, "added"));
        out.putAll(section(fleet, "members"));
        return out;
    }

    /** #132: the members {@code fleet add} registered (their repos know nothing of the fleet): service → root. */
    public static Map<String, Path> added(Path fleet) {
        return section(fleet, "added");
    }

    /** The members that joined ({@code fleet: <name>} in their logast.yaml): service → root. */
    public static Map<String, Path> joined(Path fleet) {
        return section(fleet, "members");
    }

    private static Map<String, Path> section(Path fleet, String key) {
        Map<String, Path> out = new TreeMap<>();
        Path f = fleet.resolve(MEMBERS);
        if (!Files.isRegularFile(f)) {
            return out;
        }
        try {
            JsonNode n = YAML.readTree(Files.readString(f));
            if (n != null) {
                n.path(key).fields().forEachRemaining(e -> out.put(e.getKey(), Path.of(e.getValue().asText())));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + f, e);
        }
        return out;
    }

    /**
     * #132: where an added member's scan writes what {@code logast scan} writes to {@code <root>/.logast} (classpath,
     * orders, coverage, reports): {@code <fleet>/services/<service>/}, laid out like .logast.
     */
    public static Path outputDir(Path fleet, String service) {
        return fleet.resolve(SERVICES).resolve(service);
    }

    /** What {@link #add} did with a repo. */
    public enum Added { ADDED, AGAIN, MOVED, JOINED_ALREADY }

    /**
     * #132: registers {@code root} as an added member: only the fleet folder changes, nothing is written into the repo.
     * A service that joined already stays joined.
     */
    public static Added add(Path fleet, Path root) {
        Path r = root.toAbsolutePath().normalize();
        String service = serviceName(r);
        Map<String, Path> joined = joined(fleet);
        if (joined.containsKey(service)) {
            return Added.JOINED_ALREADY;
        }
        Map<String, Path> added = new TreeMap<>(added(fleet));
        Path before = added.put(service, r);
        writeMembers(fleet, joined, added);
        return before == null ? Added.ADDED : before.equals(r) ? Added.AGAIN : Added.MOVED;
    }

    /**
     * #132: takes an added member out, by service name or root: its entry, snapshot and output folder go (the repo was
     * never touched). Null when no added member has that name or root; else the service name.
     */
    public static String remove(Path fleet, String serviceOrRoot) {
        Map<String, Path> added = new TreeMap<>(added(fleet));
        Path asRoot = Path.of(serviceOrRoot).toAbsolutePath().normalize();
        String service = added.containsKey(serviceOrRoot) ? serviceOrRoot : added.entrySet().stream()
                .filter(e -> e.getValue().equals(asRoot)).map(Map.Entry::getKey).findFirst().orElse(null);
        if (service == null) {
            return null;
        }
        added.remove(service);
        writeMembers(fleet, joined(fleet), added);
        try {
            Files.deleteIfExists(fleet.resolve(SERVICES).resolve(service + ".json"));
            Path out = outputDir(fleet, service);
            if (Files.isDirectory(out)) {   // logast's own output for this member, nothing a person wrote
                try (Stream<Path> s = Files.walk(out)) {
                    for (Path f : s.sorted(Comparator.reverseOrder()).toList()) {
                        Files.delete(f);
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return service;
    }

    /**
     * Makes {@code root} a member: registers it in the fleet folder and writes {@code fleet: <name>} into its
     * logast.yaml, and {@code fleet:<name>} first in its {@code extends:} with the path it resolves to here as a comment
     * (#133; those lines only: the rest of the file and its comments stay). Returns the root registered before
     * under the same service name, if another.
     */
    public static Path join(Path fleet, String name, Path root) {
        Path r = root.toAbsolutePath().normalize();
        Map<String, Path> members = new TreeMap<>(joined(fleet));
        Map<String, Path> added = new TreeMap<>(added(fleet));
        Path before = members.put(serviceName(r), r);
        Path wasAdded = added.remove(serviceName(r));   // #132: an added member that joins is a joined one from now on
        before = before != null ? before : wasAdded;
        writeMembers(fleet, members, added);
        setFleetLine(r, name);
        Path company = fleet.resolve(CentralVocabulary.COMPANY).toAbsolutePath().normalize();
        FleetExtends.edit(PolicyLoader.defaultFile(r), lines -> FleetExtends.add(lines, name, company));
        return before == null || before.equals(r) ? null : before;
    }

    /**
     * Takes {@code root} out of its fleet: the {@code fleet:} line and the fleet's {@code extends:} entry go (#133; an
     * {@code extends:} left empty too), the member entry and snapshot too.
     */
    public static void leave(Path fleet, Path root) {
        Path r = root.toAbsolutePath().normalize();
        String name = of(r);
        if (name != null) {
            FleetExtends.edit(PolicyLoader.defaultFile(r), lines -> FleetExtends.remove(lines, name));
        }
        String service = serviceName(r);
        if (fleet != null && exists(fleet)) {
            Map<String, Path> members = new TreeMap<>(joined(fleet));
            members.remove(service);
            writeMembers(fleet, members, added(fleet));
            try {
                Files.deleteIfExists(fleet.resolve(SERVICES).resolve(service + ".json"));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        setFleetLine(r, null);
    }

    /** Sets (or with null removes) the top-level {@code fleet:} line of the service's logast.yaml; creates the file if needed. */
    static void setFleetLine(Path root, String name) {
        Path file = PolicyLoader.defaultFile(root);
        try {
            List<String> lines = Files.exists(file) ? new ArrayList<>(Files.readAllLines(file)) : new ArrayList<>();
            int at = -1;
            for (int i = 0; i < lines.size() && at < 0; i++) {
                if (FLEET_LINE.matcher(lines.get(i)).matches()) {
                    at = i;
                }
            }
            if (name == null) {
                if (at >= 0) {
                    lines.remove(at);
                    if (at > 0 && lines.get(at - 1).startsWith("# The fleet this service belongs to")) {
                        lines.remove(at - 1);   // the line setup writes above it
                    }
                    // the setup's notes on settings the fleet decided: no longer true (the lines stay commented out)
                    lines.replaceAll(l -> l.replaceFirst("\\s+# decided by fleet \\S+'s company\\.yaml$", ""));
                    Files.write(file, lines);
                }
                return;
            }
            String line = "fleet: " + name;
            if (at >= 0) {
                Matcher comment = Pattern.compile("\\s+#.*$").matcher(lines.get(at));
                lines.set(at, line + (comment.find() ? comment.group() : ""));
            } else {
                int first = 0;   // after the leading comment block, so a file's header stays on top
                while (first < lines.size() && lines.get(first).startsWith("#")) {
                    first++;
                }
                lines.add(first, line);
            }
            Files.write(file, lines);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot edit " + file, e);
        }
    }

    private static void writeMembers(Path fleet, Map<String, Path> members, Map<String, Path> added) {
        try {
            Files.createDirectories(fleet);
            Files.writeString(fleet.resolve(MEMBERS), membersYaml(members, added));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write " + fleet.resolve(MEMBERS), e);
        }
    }

    private static String membersYaml(Map<String, Path> members, Map<String, Path> added) {
        StringBuilder sb = new StringBuilder("# logast fleet members (#124): service -> its root on this machine; "
                + "`logast fleet join` / `leave` keep it\nmembers:");
        entries(sb, members);
        if (!added.isEmpty()) {
            sb.append("# #132: repos `logast fleet add` registered without touching them; `logast fleet remove` takes one out\nadded:");
            entries(sb, added);
        }
        return sb.toString();
    }

    private static void entries(StringBuilder sb, Map<String, Path> members) {
        if (members.isEmpty()) {
            sb.append(" {}");
        }
        sb.append('\n');
        new TreeMap<>(members).forEach((k, v) -> sb.append("  ").append(k).append(": \"")
                .append(v.toString().replace("\\", "\\\\").replace("\"", "\\\"")).append("\"\n"));
    }

    // ---- snapshots ----------------------------------------------------------------------------------------------------

    /** The snapshot of one scan: the fleet report's service entry, the vocabulary evidence, commit and time. */
    public static Snapshot snapshot(Path root, ScanResult r, Analysis a, Policy policy) {
        Path rr = root.toAbsolutePath().normalize();
        String name = serviceName(rr);
        FleetReport.Service s = FleetReport.service(name, r, policy.overrides(), policy.packs(),
                dev.logast.metrics.MeterCatalog.of(a), ServiceSurface.of(a, r));
        // apply scans a scratch copy: the snapshot names the service's own root
        FleetReport.Service service = new FleetReport.Service(s.name(), rr.toString(), s.files(), s.entrypoints(), s.severity(),
                s.rules(), s.packScore(), s.score(), s.overrides(), s.meters(), s.logCoverage(), s.surface());
        return new Snapshot(name, rr.toString(), Instant.now().toString(), commit(rr), service,
                CentralVocabulary.uses(rr, name), CentralVocabulary.knownAreas(rr, name), Settings.of(policy), a.appPackage,
                dev.logast.Build.id());
    }

    /** Writes the snapshot to {@code <fleet>/services/<service>.json} (whole or not at all). */
    public static Path write(Path fleet, Snapshot s) {
        try {
            Path dir = Files.createDirectories(fleet.resolve(SERVICES));
            Path target = dir.resolve(s.service() + ".json");
            Path tmp = Files.createTempFile(dir, s.service(), ".tmp");
            OrderJson.MAPPER.writeValue(tmp.toFile(), s);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return target;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Every snapshot in the fleet folder, by service; one that cannot be read is left out. */
    public static Map<String, Snapshot> snapshots(Path fleet) {
        Map<String, Snapshot> out = new TreeMap<>();
        Path dir = fleet.resolve(SERVICES);
        if (!Files.isDirectory(dir)) {
            return out;
        }
        try (Stream<Path> s = Files.list(dir)) {
            for (Path p : s.filter(f -> f.getFileName().toString().endsWith(".json")).sorted().toList()) {
                try {
                    Snapshot snap = READ.readValue(p.toFile(), Snapshot.class);
                    out.put(snap.service(), snap);
                } catch (IOException e) {
                    // a snapshot of another logast version or a half-synced file: the overview says "no snapshot"
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out;
    }

    /** The vocabulary worksheet and company.yaml from the snapshots, with the decisions already in the worksheet. */
    public static CentralVocabulary.Result vocabulary(Path fleet, Collection<Snapshot> snapshots) {
        List<CentralVocabulary.Use> uses = new ArrayList<>();
        List<CentralVocabulary.KnownArea> known = new ArrayList<>();
        Set<String> names = new TreeSet<>();
        for (Snapshot s : snapshots) {
            uses.addAll(s.vocabulary());
            known.addAll(s.areas());
            names.add(s.service());
        }
        return CentralVocabulary.build(uses, names, CentralVocabulary.read(fleet.resolve(CentralVocabulary.WORKSHEET)), known)
                .rerunBy("`logast fleet " + fleet.getFileName() + "`");
    }

    /** The short commit of {@code root}'s git checkout; null outside git. */
    /** The short HEAD commit of {@code root}, or null outside a git work tree. */
    public static String commit(Path root) {
        String sha = Git.output(root, "rev-parse", "--short", "HEAD");
        return sha == null || sha.isEmpty() ? null : sha;
    }
}
