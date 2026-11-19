package dev.logast;

import com.fasterxml.jackson.databind.JsonNode;
import dev.logast.policy.PolicyLoader;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Which of a service's own vocabulary entries an extended policy (the central company.yaml, #113) now defines, and
 * removing those from the service's logast.yaml. Only an entry the parent covers completely goes (same canonical key,
 * or the key is one of the parent term's aliases; its aliases, MDC aliases and sources all in the parent's); an entry
 * with a real difference stays (the merge then shows it).
 */
public final class VocabularyPrune {
    private VocabularyPrune() {
    }

    /** A service term the parent defines: {@code by} is the parent's term. */
    public record Covered(String term, String by) {
    }

    /** A service term that matches a parent term but differs from it: kept. */
    public record Differs(String term, String by, String what) {
    }

    public record Report(Path file, List<String> parents, List<Covered> covered, List<Differs> differs) {
        public static final Report NONE = new Report(null, List.of(), List.of(), List.of());

        /** One line per finding, for scan. */
        public List<String> lines(String root) {
            List<String> out = new ArrayList<>();
            String by = String.join(", ", parents);
            if (!covered.isEmpty()) {
                out.add("vocabulary: " + by + " now defines " + covered.size() + " of this service's entries: "
                        + String.join(", ", covered.stream().map(c -> c.term().equals(c.by()) ? c.term() : c.term() + " (as " + c.by() + ")").toList())
                        + "; `logast vocabulary --prune " + root + "` removes them from " + file.getFileName());
            }
            differs.forEach(d -> out.add("vocabulary: " + d.term() + " differs from " + by + "'s term " + d.by() + " (" + d.what()
                    + "): kept, this service's entry wins"));
            return out;
        }
    }

    /** The service's policy file compared with what its parents define; {@link Report#NONE} without a file or parents. */
    public static Report check(Path root) {
        Path file = PolicyLoader.defaultFile(root);
        if (!Files.exists(file)) {
            return Report.NONE;
        }
        PolicyLoader.VocabularyLayers layers = PolicyLoader.vocabularyLayers(file);
        if (layers.parents().isEmpty() || !layers.own().isObject() || !layers.inherited().isObject()) {
            return Report.NONE;
        }
        List<Covered> covered = new ArrayList<>();
        List<Differs> differs = new ArrayList<>();
        layers.own().fields().forEachRemaining(e -> {
            String name = e.getKey();
            JsonNode own = e.getValue();
            String ownKey = own.path("mdc").path("key").asText(null);
            List<String> candidates = new ArrayList<>();
            if (layers.inherited().has(name)) {
                candidates.add(name);
            }
            layers.inherited().fields().forEachRemaining(p -> {
                JsonNode mdc = p.getValue().path("mdc");
                if (!p.getKey().equals(name) && ownKey != null
                        && (ownKey.equals(mdc.path("key").asText(null)) || strings(mdc.path("aliases")).contains(ownKey))) {
                    candidates.add(p.getKey());
                }
            });
            String firstDifference = null;
            String firstBy = null;
            for (String c : candidates) {
                String diff = difference(own, layers.inherited().get(c));
                if (diff == null) {
                    covered.add(new Covered(name, c));
                    return;
                }
                if (firstDifference == null) {
                    firstDifference = diff;
                    firstBy = c;
                }
            }
            if (firstDifference != null) {
                differs.add(new Differs(name, firstBy, firstDifference));
            }
        });
        return new Report(file, layers.parents(), covered, differs);
    }

    /** What the service's term says that the parent's does not; null when the parent covers it. */
    static String difference(JsonNode own, JsonNode parent) {
        if (own.path("override").asBoolean(false)) {
            return "override: true";
        }
        List<String> missing = new ArrayList<>(strings(own.path("aliases")));
        missing.removeAll(strings(parent.path("aliases")));
        if (!missing.isEmpty()) {
            return "aliases " + missing;
        }
        if (own.has("logKey") && !own.path("logKey").equals(parent.path("logKey"))) {
            return "logKey " + own.path("logKey").asText();
        }
        JsonNode om = own.path("mdc");
        if (om.isMissingNode() || om.isNull()) {
            return null;
        }
        JsonNode pm = parent.path("mdc");
        Set<String> spellings = new LinkedHashSet<>();
        if (pm.has("key")) {
            spellings.add(pm.path("key").asText());
        }
        spellings.addAll(strings(pm.path("aliases")));
        if (om.has("key") && !spellings.contains(om.path("key").asText())) {
            return "mdc.key " + om.path("key").asText();
        }
        List<String> keys = new ArrayList<>(strings(om.path("aliases")));
        keys.removeAll(spellings);
        if (!keys.isEmpty()) {
            return "mdc.aliases " + keys;
        }
        if (om.has("cardinality") && !om.path("cardinality").asText().equals(pm.path("cardinality").asText("high"))) {
            return "mdc.cardinality " + om.path("cardinality").asText();
        }
        List<String> params = new ArrayList<>(strings(om.path("sources").path("params")));
        params.removeAll(strings(pm.path("sources").path("params")));
        if (!params.isEmpty()) {
            return "mdc.sources.params " + params;
        }
        Iterator<Map.Entry<String, JsonNode>> types = om.path("sources").path("types").fields();
        while (types.hasNext()) {
            Map.Entry<String, JsonNode> t = types.next();
            if (!t.getValue().equals(pm.path("sources").path("types").path(t.getKey()))) {
                return "mdc.sources.types " + t.getKey();
            }
        }
        return null;
    }

    private static List<String> strings(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.asText()));
        return out;
    }

    private static final Pattern TOP_VOCABULARY = Pattern.compile("vocabulary:\\s*(#.*)?");

    /** Removes the covered entries from the service's policy file, comments and the rest untouched; returns them. */
    public static List<Covered> prune(Path root) {
        Report r = check(root);
        if (r.covered().isEmpty()) {
            return List.of();
        }
        try {
            List<String> lines = new ArrayList<>(Files.readAllLines(r.file()));
            Set<String> names = new HashSet<>();
            r.covered().forEach(c -> names.add(c.term()));
            int start = -1;
            for (int i = 0; i < lines.size() && start < 0; i++) {
                if (TOP_VOCABULARY.matcher(lines.get(i)).matches()) {
                    start = i;
                }
            }
            if (start < 0) {
                throw new IllegalStateException(r.file() + ": no block-style top-level `vocabulary:` to prune (flow style is not edited)");
            }
            int end = start + 1;
            while (end < lines.size() && (lines.get(end).isBlank() || lines.get(end).startsWith(" ") || lines.get(end).startsWith("#"))) {
                end++;
            }
            int indent = -1;
            List<String> kept = new ArrayList<>();
            int entries = 0;
            boolean dropping = false;
            Pattern entry = null;
            for (int i = start + 1; i < end; i++) {
                String line = lines.get(i);
                int lead = line.length() - line.stripLeading().length();
                boolean content = !line.isBlank() && !line.stripLeading().startsWith("#");
                if (indent < 0 && content) {
                    indent = lead;
                    entry = Pattern.compile(" {" + indent + "}([\"']?)([^\"':#\\s]+)\\1\\s*:.*");
                }
                if (content && lead <= indent) {
                    Matcher m = entry.matcher(line);
                    dropping = m.matches() && names.contains(m.group(2));
                    entries += dropping ? 0 : 1;
                }
                if (!dropping) {
                    kept.add(line);
                }
            }
            List<String> out = new ArrayList<>(lines.subList(0, start));
            if (entries > 0) {
                out.add(lines.get(start));
                out.addAll(kept);
            } else {
                // nothing of its own left: drop the key, keep comments that stood in the block
                kept.stream().filter(l -> l.isBlank() || l.stripLeading().startsWith("#")).forEach(out::add);
            }
            out.addAll(lines.subList(end, lines.size()));
            Files.write(r.file(), out);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return r.covered();
    }
}
