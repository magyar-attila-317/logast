package dev.logast.fleet;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import dev.logast.VocabularyPrune;
import dev.logast.policy.PolicyLoader;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * #133: a member's own logast.yaml against its fleet's company.yaml. A setting the service sets to the value
 * company.yaml sets is redundant: {@code fleet join} and {@code fleet tidy} remove it (with the comment on its line, and a
 * block like {@code logging:} it leaves empty), and the vocabulary entries company.yaml defines in full
 * ({@link VocabularyPrune}). A setting with another value is the service's own override: kept and listed, removed only
 * when the service follows the company ({@code --follow-company}). Only those lines change.
 */
public final class FleetTidy {
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    /** Top-level keys that are no settings to compare. */
    private static final Set<String> NOT_SETTINGS = Set.of("fleet", "extends", "vocabulary", "areas");
    /** Lists a later layer replaces (as in PolicyLoader): a different one is an override; other lists add up. */
    private static final Set<String> REPLACED_LISTS = Set.of("packs", "mdc.ambientKeys", "metrics.defaults");
    private static final Pattern KEY = Pattern.compile("( *)(\"[^\"]*\"|'[^']*'|[^\\s#\"'\\-][^:#]*?)\\s*:(\\s.*)?");

    private FleetTidy() {
    }

    /** One setting of the service's file: its value and company.yaml's. */
    public record Setting(String path, String own, String company) {
    }

    /** What tidying would do: settings with company.yaml's value, own overrides, vocabulary company.yaml defines. */
    public record Plan(List<Setting> same, List<Setting> overrides, List<VocabularyPrune.Covered> vocabulary) {
        public static final Plan NONE = new Plan(List.of(), List.of(), List.of());

        public boolean isEmpty() {
            return same.isEmpty() && overrides.isEmpty() && vocabulary.isEmpty();
        }

        /** The overview's line for a member, or null when there is nothing to tidy. */
        public String overviewLine(String service, String fleet) {
            if (isEmpty()) {
                return null;
            }
            List<String> parts = new ArrayList<>();
            if (!same.isEmpty()) {
                parts.add(same.size() + (same.size() == 1 ? " setting" : " settings") + " company.yaml already sets ("
                        + String.join(", ", same.stream().map(Setting::path).toList()) + ")");
            }
            if (!vocabulary.isEmpty()) {
                parts.add(vocabulary.size() + (vocabulary.size() == 1 ? " vocabulary entry" : " vocabulary entries")
                        + " company.yaml defines (" + String.join(", ", vocabulary.stream().map(VocabularyPrune.Covered::term).toList()) + ")");
            }
            if (!overrides.isEmpty()) {
                parts.add(overrides.size() + (overrides.size() == 1 ? " own override" : " own overrides") + " ("
                        + String.join(", ", overrides.stream().map(s -> s.path() + ": " + s.own()).toList()) + ")");
            }
            return service + ": " + String.join(" · ", parts) + " → `logast fleet tidy " + fleet + "`";
        }
    }

    /** What {@link #apply} did: one line per removal, and the overrides it kept. */
    public record Result(List<String> removed, List<Setting> kept) {
    }

    /** What tidying {@code root}'s logast.yaml against {@code company} would do; nothing is written. */
    public static Plan plan(Path root, Path company) {
        Path file = PolicyLoader.defaultFile(root);
        if (!Files.isRegularFile(file) || !Files.isRegularFile(company)) {
            return Plan.NONE;
        }
        JsonNode own = read(file);
        JsonNode comp = read(company);
        if (own == null || !own.isObject() || comp == null || !comp.isObject()) {
            return Plan.NONE;
        }
        List<Setting> same = new ArrayList<>();
        List<Setting> differ = new ArrayList<>();
        walk(own, comp, "", same, differ);
        same.sort(Comparator.comparing(Setting::path));
        differ.sort(Comparator.comparing(Setting::path));
        return new Plan(same, differ, VocabularyPrune.check(root, company).covered());
    }

    /**
     * Removes what {@link #plan} found redundant (and with {@code followCompany} the overrides too) from {@code root}'s
     * logast.yaml; the rest of the file stays as it is.
     */
    public static Result apply(Path root, Path company, boolean followCompany) {
        Plan plan = plan(root, company);
        if (plan.isEmpty()) {
            return new Result(List.of(), List.of());
        }
        List<String> removed = new ArrayList<>();
        Set<String> paths = new LinkedHashSet<>();
        plan.same().forEach(s -> paths.add(s.path()));
        if (followCompany) {
            plan.overrides().forEach(s -> paths.add(s.path()));
        }
        Path file = PolicyLoader.defaultFile(root);
        Set<String> gone = Set.of();
        if (!paths.isEmpty()) {
            try {
                List<String> lines = Files.readAllLines(file);
                Map<String, Integer> found = new HashMap<>();
                List<String> out = remove(lines, paths, found);
                gone = found.keySet();
                if (!out.equals(lines)) {
                    Files.write(file, out);
                }
            } catch (IOException e) {
                throw new UncheckedIOException("cannot edit " + file, e);
            }
        }
        for (Setting s : plan.same()) {
            if (gone.contains(s.path())) {
                removed.add(s.path() + ": " + s.own() + " (company.yaml sets the same)");
            }
        }
        List<Setting> kept = new ArrayList<>();
        for (Setting s : plan.overrides()) {
            if (followCompany && gone.contains(s.path())) {
                removed.add(s.path() + ": " + s.own() + " (company: " + s.company() + ")");
            } else {
                kept.add(s);
            }
        }
        for (VocabularyPrune.Covered c : VocabularyPrune.prune(root, company)) {
            removed.add("vocabulary " + c.term() + (c.term().equals(c.by()) ? "" : " (as " + c.by() + ")") + " (company.yaml defines it)");
        }
        return new Result(removed, kept);
    }

    private static void walk(JsonNode own, JsonNode comp, String prefix, List<Setting> same, List<Setting> differ) {
        own.fields().forEachRemaining(e -> {
            String key = e.getKey();
            if (prefix.isEmpty() && NOT_SETTINGS.contains(key)) {
                return;
            }
            String path = prefix.isEmpty() ? key : prefix + "." + key;
            JsonNode v = e.getValue();
            JsonNode c = comp.get(key);
            if (c == null || c.isNull() || v.isNull()) {
                return;
            }
            if (v.isObject()) {
                if (!v.path("override").asBoolean(false) && c.isObject()) {
                    walk(v, c, path, same, differ);
                }
            } else if (v.isArray()) {
                if (v.equals(c)) {
                    same.add(new Setting(path, text(v), text(c)));
                } else if (c.isArray() && REPLACED_LISTS.contains(path)) {
                    differ.add(new Setting(path, text(v), text(c)));
                }
            } else if (c.isValueNode()) {
                (v.equals(c) || v.asText().equals(c.asText()) ? same : differ).add(new Setting(path, text(v), text(c)));
            }
        });
    }

    private static String text(JsonNode n) {
        if (n.isArray()) {
            List<String> items = new ArrayList<>();
            n.forEach(i -> items.add(text(i)));
            return "[" + String.join(", ", items) + "]";
        }
        return n.isValueNode() ? n.asText() : n.toString();
    }

    /** A key line of a block-style YAML file: its path, indentation and the lines its value spans. */
    private record Key(String path, int indent, int line, int last, boolean inlineValue) {
    }

    private static List<Key> keys(List<String> lines) {
        List<Key> out = new ArrayList<>();
        Deque<Key> stack = new ArrayDeque<>();
        for (int i = 0; i < lines.size(); i++) {
            String l = lines.get(i);
            if (l.isBlank() || l.stripLeading().startsWith("#") || l.stripLeading().startsWith("-")) {
                continue;
            }
            Matcher m = KEY.matcher(l);
            if (!m.matches()) {
                continue;
            }
            int indent = m.group(1).length();
            while (!stack.isEmpty() && stack.peek().indent() >= indent) {
                stack.pop();
            }
            String name = m.group(2).strip().replaceAll("^[\"']|[\"']$", "");
            String path = stack.isEmpty() ? name : stack.peek().path() + "." + name;
            String rest = m.group(3) == null ? "" : m.group(3).strip();
            Key k = new Key(path, indent, i, last(lines, i, indent), !rest.isEmpty() && !rest.startsWith("#"));
            out.add(k);
            stack.push(k);
        }
        return out;
    }

    /** The last line of the value of the key at {@code at}: lines indented deeper (or list items at its indentation). */
    private static int last(List<String> lines, int at, int indent) {
        int last = at;
        for (int j = at + 1; j < lines.size(); j++) {
            String l = lines.get(j);
            if (l.isBlank()) {
                continue;
            }
            int ind = l.length() - l.stripLeading().length();
            boolean comment = l.stripLeading().startsWith("#");
            if (!comment && (ind > indent || ind == indent && l.stripLeading().startsWith("- "))) {
                last = j;
            } else if (!(comment && ind > indent)) {
                break;
            }
        }
        return last;
    }

    /**
     * {@code lines} without the keys at {@code paths} (each with its value lines; comment lines between keys stay), and
     * without a block a removal left with no key of its own (with what stood in it). {@code found}: the paths removed.
     */
    static List<String> remove(List<String> lines, Set<String> paths, Map<String, Integer> found) {
        List<String> out = new ArrayList<>(lines);
        List<Key> doomed = new ArrayList<>(keys(out).stream().filter(k -> paths.contains(k.path())).toList());
        doomed.forEach(k -> found.put(k.path(), k.line()));
        doomed.sort(Comparator.comparingInt(Key::line).reversed());
        Set<String> parents = new HashSet<>();
        for (Key k : doomed) {
            out.subList(k.line(), k.last() + 1).clear();
            for (String p = k.path(); p.contains("."); ) {
                p = p.substring(0, p.lastIndexOf('.'));
                parents.add(p);
            }
        }
        for (boolean again = true; again; ) {
            again = false;
            List<Key> all = keys(out);
            for (Key k : all) {
                if (!parents.contains(k.path()) || k.inlineValue()) {
                    continue;
                }
                boolean hasChild = all.stream().anyMatch(c -> c.line() > k.line() && c.line() <= k.last() && c.indent() > k.indent());
                boolean hasItems = out.subList(k.line() + 1, k.last() + 1).stream()
                        .anyMatch(l -> !l.isBlank() && !l.stripLeading().startsWith("#"));
                if (!hasChild && !hasItems) {
                    // the emptied block goes, with the comments that stood in it
                    int end = k.line();
                    for (int j = k.line() + 1; j < out.size(); j++) {
                        String l = out.get(j);
                        if (!l.isBlank() && l.stripLeading().startsWith("#") && l.length() - l.stripLeading().length() > k.indent()) {
                            end = j;
                        } else if (!l.isBlank()) {
                            break;
                        }
                    }
                    out.subList(k.line(), end + 1).clear();
                    again = true;
                    break;
                }
            }
        }
        return out;
    }

    private static JsonNode read(Path file) {
        try {
            return YAML.readTree(Files.readString(file));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + file, e);
        }
    }
}
