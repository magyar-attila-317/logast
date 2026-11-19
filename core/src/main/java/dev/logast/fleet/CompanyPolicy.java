package dev.logast.fleet;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import dev.logast.CentralVocabulary;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;

/**
 * A named fleet's {@code company.yaml} as the company's policy (#129). {@code logast fleet create} writes it at once:
 * a header, the settings a company usually decides, each commented out with one explaining line, and the empty
 * generated vocabulary part. From the members' snapshots, a marked part of its own ({@link #BEGIN} … {@link #END})
 * holds commented suggestions with counts ("2 of 2 members use it"); it is rewritten whenever a snapshot is written and
 * by {@code logast fleet <name>}. Suggestions are never active values: company.yaml is a layer every member extends,
 * and an automatic value would change other teams' services silently. Lines outside the marked parts are never
 * rewritten; a line uncommented inside the suggestions is moved out of it, above it, and kept.
 */
public final class CompanyPolicy {
    static final String BEGIN = "# >>> logast suggestions: what the members' snapshots show, rewritten with every snapshot; "
            + "a line you uncomment here is moved above this part and kept";
    static final String END = "# <<< logast suggestions: end";
    private static final String BEGIN_PREFIX = "# >>> logast suggestions";
    private static final String END_PREFIX = "# <<< logast suggestions";
    private static final String VOCABULARY_BEGIN = "# >>> logast:";
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    /** The settings a company usually decides, in file order, with what each one does. */
    record Setting(String parent, String key, String example, String explains) {
        String path() {
            return parent == null ? key : parent + "." + key;
        }
    }

    static final List<Setting> SETTINGS = List.of(
            new Setting(null, "packs", "[logging, tracing, metrics, resilience, errors, audit]",
                    "the rule packs every member runs (a member's own packs: list replaces it)"),
            new Setting("logging", "structuredFormat", "logstash",
                    "the JSON console log format written to application.yml: logstash, ecs, gelf, or none"),
            new Setting("logging", "keyStyle", "camel",
                    "how MDC keys outside the vocabulary are spelled: dotted, camel, pascal, snake, or keep"),
            new Setting("support", "mode", "vendor",
                    "logging-support: vendor (its classes copied into each service) or dependency (the published starter)"),
            new Setting("support", "repository", "https://repo.example.com/maven-releases",
                    "where the logging-support starter is published (dependency mode)"));

    /**
     * One setting the members' snapshots show and company.yaml does not set yet.
     *
     * @param agree    members with {@code value}, the most used one
     * @param of       members whose snapshot records the setting
     * @param outliers the other members → their value
     */
    public record Suggestion(String path, String value, int agree, int of, Map<String, String> outliers) {
        public boolean unanimous() {
            return agree == of;
        }

        /** One line for the overview and the report: {@code logging.keyStyle: camel (2 of 2 members use it: …)}. */
        public String text() {
            return path + ": " + value + " (" + comment() + ")";
        }

        String comment() {
            if (unanimous()) {
                return of == 1 ? "1 of 1 member uses it: uncomment to make it the company's"
                        : of + " of " + of + " members use it: uncomment to make it the company's";
            }
            StringJoiner others = new StringJoiner(", ");
            outliers.forEach((service, v) -> others.add(service + ": " + v));
            return agree + " of " + of + " (" + others + "): decide, then uncomment";
        }
    }

    private CompanyPolicy() {
    }

    /** company.yaml as {@code logast fleet create} writes it: header, commented settings, empty suggestions and vocabulary. */
    public static String initial(String fleet) {
        StringBuilder sb = new StringBuilder();
        sb.append("# company.yaml of fleet ").append(fleet).append(" (#129): the policy every member extends. A member's logast.yaml\n")
                .append("# names the fleet (`fleet: ").append(fleet).append("`); its own settings win over this file. Uncomment a\n")
                .append("# setting to make it the company's. logast rewrites only its marked parts (# >>> … # <<<), never the rest.\n")
                .append("#\n");
        String parent = null;
        for (Setting s : SETTINGS) {
            if (s.parent() != null && !s.parent().equals(parent)) {
                sb.append("# ").append(s.parent()).append(":\n");
            }
            parent = s.parent();
            sb.append(s.parent() == null ? "# " : "#   ").append(s.key()).append(": ").append(s.example())
                    .append("   # ").append(s.explains()).append('\n');
        }
        sb.append('\n').append(block(List.of(), Set.of(), false)).append('\n');
        sb.append(CentralVocabulary.mergeCompany(null, CentralVocabulary.build(List.of(), List.of(),
                CentralVocabulary.Previous.NONE).company()));
        return sb.toString();
    }

    /** The members' effective settings as YAML path → value; null for a snapshot written before #129. */
    static Map<String, String> values(NamedFleet.Settings s) {
        if (s == null) {
            return null;
        }
        Map<String, String> out = new LinkedHashMap<>();
        out.put("packs", "[" + String.join(", ", s.packs()) + "]");
        put(out, "logging.structuredFormat", s.structuredFormat());
        put(out, "logging.keyStyle", s.keyStyle());
        put(out, "support.mode", s.supportMode());
        return out;
    }

    private static void put(Map<String, String> out, String path, String value) {
        if (value != null && !value.isBlank()) {
            out.put(path, value);
        }
    }

    /** What the snapshots suggest for the settings {@code active} (company.yaml's own, uncommented) does not hold. */
    public static List<Suggestion> suggestions(Collection<NamedFleet.Snapshot> snapshots, Set<String> active) {
        Map<String, Map<String, String>> byPath = new LinkedHashMap<>();   // path → service → value
        for (NamedFleet.Snapshot snap : snapshots) {
            Map<String, String> v = values(snap.settings());
            if (v != null) {
                v.forEach((path, value) -> byPath.computeIfAbsent(path, p -> new TreeMap<>()).put(snap.service(), value));
            }
        }
        List<Suggestion> out = new ArrayList<>();
        for (Setting setting : SETTINGS) {
            Map<String, String> services = byPath.get(setting.path());
            if (services == null || active.contains(setting.path())) {
                continue;
            }
            Map<String, Integer> counts = new TreeMap<>();
            services.values().forEach(v -> counts.merge(v, 1, Integer::sum));
            String top = counts.entrySet().stream().max(Map.Entry.<String, Integer>comparingByValue()
                    .thenComparing(Map.Entry.comparingByKey(Comparator.reverseOrder()))).orElseThrow().getKey();
            Map<String, String> outliers = new TreeMap<>(services);
            outliers.values().removeIf(top::equals);
            out.add(new Suggestion(setting.path(), top, counts.get(top), services.size(), outliers));
        }
        return out;
    }

    /** The settings of {@link #SETTINGS} that the YAML text sets (uncommented); null when it does not parse. */
    static Set<String> active(String yaml) {
        JsonNode n;
        try {
            n = YAML.readTree(yaml);
        } catch (IOException e) {
            return null;
        }
        Set<String> out = new TreeSet<>();
        if (n == null || !n.isObject()) {
            return out;
        }
        for (Setting s : SETTINGS) {
            JsonNode v = s.parent() == null ? n.get(s.key()) : n.path(s.parent()).get(s.key());
            if (v != null && !v.isNull()) {
                out.add(s.path());
            }
        }
        if (n.path("logging").isObject()) {
            out.add("logging");
        }
        if (n.path("support").isObject()) {
            out.add("support");
        }
        return out;
    }

    /** The result of a refresh: the new text (null when the file does not parse) and the suggestions in it. */
    public record Refreshed(String text, List<Suggestion> suggestions) {
    }

    /**
     * Rewrites the suggestions part of {@code existing} from the snapshots. Lines outside the part stay as they are; an
     * uncommented line inside it moves above it. Without the part, it goes before the vocabulary part (else at the end).
     */
    public static Refreshed refresh(String existing, Collection<NamedFleet.Snapshot> snapshots) {
        List<String> lines = new ArrayList<>(existing.lines().toList());
        int b = -1;
        int e = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (b < 0 && lines.get(i).startsWith(BEGIN_PREFIX)) {
                b = i;
            } else if (b >= 0 && lines.get(i).startsWith(END_PREFIX)) {
                e = i;
                break;
            }
        }
        List<String> before;
        List<String> after;
        if (b >= 0 && e > b) {
            before = new ArrayList<>(lines.subList(0, b));
            for (String l : lines.subList(b + 1, e)) {
                if (!l.isBlank() && !l.strip().startsWith("#")) {
                    before.add(l);   // uncommented by hand: the company's own setting now, kept outside the part
                }
            }
            after = lines.subList(e + 1, lines.size());
        } else {
            int v = 0;
            while (v < lines.size() && !lines.get(v).startsWith(VOCABULARY_BEGIN)) {
                v++;
            }
            before = new ArrayList<>(lines.subList(0, v));
            if (!before.isEmpty() && !before.getLast().isBlank()) {
                before.add("");
            }
            after = new ArrayList<>();
            if (v < lines.size()) {
                after.add("");
                after.addAll(lines.subList(v, lines.size()));
            }
        }
        String outside = String.join("\n", before) + "\n" + String.join("\n", after) + "\n";
        Set<String> active = active(outside);
        if (active == null) {
            return new Refreshed(null, List.of());
        }
        List<Suggestion> suggestions = suggestions(snapshots, active);
        StringBuilder sb = new StringBuilder();
        before.forEach(l -> sb.append(l).append('\n'));
        sb.append(block(suggestions, active, !snapshots.isEmpty()));
        after.forEach(l -> sb.append(l).append('\n'));
        return new Refreshed(sb.toString(), suggestions);
    }

    /** The marked suggestions part: commented YAML a reader can uncomment, a count on each line. */
    static String block(List<Suggestion> suggestions, Set<String> active, boolean snapshots) {
        StringBuilder sb = new StringBuilder(BEGIN).append('\n');
        if (suggestions.isEmpty()) {
            sb.append(snapshots ? "# (every setting the snapshots record is set above)\n"
                    : "# (no member snapshot yet: `logast scan` in a member writes one)\n");
        }
        String parent = null;
        for (Suggestion s : suggestions) {
            int dot = s.path().indexOf('.');
            String p = dot < 0 ? null : s.path().substring(0, dot);
            String key = dot < 0 ? s.path() : s.path().substring(dot + 1);
            if (p != null && !p.equals(parent)) {
                sb.append("# ").append(p).append(':');
                if (active.contains(p)) {   // a second `logging:` would be a duplicate key
                    sb.append("   # set above already: put the line you want under that ").append(p).append(": instead");
                }
                sb.append('\n');
            }
            parent = p;
            sb.append(p == null ? "# " : "#   ").append(key).append(": ").append(s.value()).append("   # ").append(s.comment()).append('\n');
        }
        return sb.append(END).append('\n').toString();
    }

    /**
     * Refreshes the fleet's company.yaml from its snapshots (writing the initial one first when the fleet has none) and
     * returns the suggestions; empty when the file does not parse (it is then left as it is).
     */
    public static List<Suggestion> refresh(Path fleetDir, String fleet, Collection<NamedFleet.Snapshot> snapshots) {
        Path file = fleetDir.resolve(CentralVocabulary.COMPANY);
        try {
            String existing = Files.exists(file) ? Files.readString(file) : initial(fleet);
            Refreshed r = refresh(existing, snapshots);
            if (r.text() == null) {
                return List.of();
            }
            if (!r.text().equals(existing) || !Files.exists(file)) {
                Path tmp = Files.createTempFile(fleetDir, "company", ".tmp");
                Files.writeString(tmp, r.text());
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            }
            return r.suggestions();
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write " + file, e);
        }
    }

    /** The overview's line; null when every setting all members agree on is company policy already. */
    public static String overviewLine(List<Suggestion> suggestions, Path company) {
        long n = suggestions.stream().filter(Suggestion::unanimous).count();
        if (n == 0) {
            return null;
        }
        return n + (n == 1 ? " setting all members agree on isn't" : " settings all members agree on aren't")
                + " company policy yet → " + company;
    }
}
