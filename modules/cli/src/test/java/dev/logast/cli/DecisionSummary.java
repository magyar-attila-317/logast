package dev.logast.cli;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * #328: the judgement calls an apply made, as numbers and names, from its diff (git diff of a service before and after
 * the apply): how catches were handled, which lines were given the exception, and every metric name and family. A
 * committed baseline per corpus service turns a rule that drifts (57 counters renamed "outcome") into a visible diff.
 */
final class DecisionSummary {
    private static final Pattern ADDED = Pattern.compile("(?m)^\\+(?!\\+\\+)(.*)$");
    private static final Pattern REMOVED = Pattern.compile("(?m)^-(?!--)(.*)$");
    private static final Pattern LOG = Pattern.compile("\\b(?:log|logger|LOG|LOGGER)\\.(trace|debug|info|warn|error)\\(");
    private static final Pattern COUNT = Pattern.compile("Outcomes\\.(count|countKind|total|perRun)\\(\"([^\"]+)\"(?:,\\s*\"([^\"]+)\")?");
    private static final Pattern CATALOG = Pattern.compile("/\\*\\* ([\\w.]+)\\{(?:kind=\"([^\"]+)\", )?outcome=\"([^\"]+)\"}");

    private DecisionSummary() {
    }

    /** The header of every support class logast copies in (vendor mode): its own code, not a decision about the service. */
    static final String VENDORED = "Copied by logast (support.mode: vendor)";

    static String of(String service, String fullDiff) {
        StringBuilder kept = new StringBuilder();
        for (String file : fullDiff.split("(?m)^(?=diff --git )")) {
            if (!file.contains(VENDORED)) {
                kept.append(file);
            }
        }
        String diff = kept.toString();
        List<String> added = lines(ADDED, diff);
        List<String> removed = lines(REMOVED, diff);
        Map<String, Integer> lines = new TreeMap<>();
        for (String l : added) {
            Matcher m = LOG.matcher(l);
            if (!m.find()) {
                continue;
            }
            String level = m.group(1);
            boolean stack = l.matches(".*,\\s*(e|ex|exc|exception|t|th|throwable|err|error|cause|rejection|r)\\);.*");
            boolean errorType = l.contains(".getClass().getSimpleName()");
            lines.merge(level + (stack ? " with the exception" : errorType ? " with [errorType]" : ""), 1, Integer::sum);
        }
        // an existing line given the exception: the same call, now ending in ", e)"
        int attached = 0;
        for (String r : removed) {
            Matcher m = LOG.matcher(r);
            if (m.find() && !r.matches(".*,\\s*(e|ex|exception|t|throwable|cause)\\);.*")) {
                String start = r.strip().replaceAll("\\);.*$", "");
                if (added.stream().anyMatch(a -> a.strip().startsWith(start) && a.matches(".*,\\s*(e|ex|exception|t|throwable|cause)\\);.*"))) {
                    attached++;
                }
            }
        }
        TreeSet<String> names = new TreeSet<>();
        Map<String, TreeSet<String>> families = new TreeMap<>();
        for (String l : added) {
            Matcher c = CATALOG.matcher(l);
            if (c.find()) {
                names.add(c.group(1));
                if (c.group(2) != null) {
                    families.computeIfAbsent(c.group(1), k -> new TreeSet<>()).add(c.group(2));
                }
                continue;
            }
            Matcher o = COUNT.matcher(l);
            if (o.find()) {
                names.add(o.group(2));
                if (o.group(1).equals("countKind") && o.group(3) != null) {
                    families.computeIfAbsent(o.group(2), k -> new TreeSet<>()).add(o.group(3));
                }
            }
        }
        long single = names.stream().filter(n -> !n.contains(".")).count();
        long long4 = names.stream().filter(n -> n.split("\\.").length > 3).count();
        StringBuilder sb = new StringBuilder("# decisions of the first apply on ").append(service)
                .append(" (#328); after a deliberate change: ./gradlew :cli:corpus -Pcorpus.updateDecisions\n");
        sb.append("log lines added:\n");
        lines.forEach((k, v) -> sb.append("  ").append(k).append(": ").append(v).append('\n'));
        sb.append("existing lines given the exception: ").append(attached).append('\n');
        sb.append("metrics: ").append(names.size()).append(" names, ").append(single).append(" of one word, ").append(long4)
                .append(" of more than three\n");
        families.forEach((n, kinds) -> sb.append("  family ").append(n).append(" ").append(kinds).append('\n'));
        names.forEach(n -> sb.append("  ").append(n).append('\n'));
        return sb.toString();
    }

    private static List<String> lines(Pattern p, String diff) {
        List<String> out = new ArrayList<>();
        Matcher m = p.matcher(diff);
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }
}
