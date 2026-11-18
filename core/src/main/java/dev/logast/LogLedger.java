package dev.logast;

import dev.logast.model.LogCall;
import dev.logast.model.MethodInfo;
import dev.logast.model.OtherLogCall;
import dev.logast.orders.WorkOrder;
import dev.logast.parse.Hashing;
import dev.logast.parse.Pos;
import dev.logast.parse.SourceFileInfo;
import org.openrewrite.java.tree.J;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Accounts for every log call (#72): what logast did with it, written to {@code .logast/coverage.txt}, one line per
 * call in file/line order. The outcome is the first that applies:
 * <ul>
 *   <li>{@code fixed}: an apply changed it (kept by later runs while its file stays unchanged);</li>
 *   <li>{@code reported}: a finding sits on it;</li>
 *   <li>{@code ignored}: a {@code // logast:ignore} silenced a rule there;</li>
 *   <li>{@code skipped}: a rule passed it over on purpose; the reason is recorded where the rule decides it;</li>
 *   <li>{@code checked}: the rules looked at it and found nothing.</li>
 * </ul>
 * The detail column names the rest too ({@code reported LOG_FORMAT FMT-…; skipped protected message}).
 */
public final class LogLedger {
    public static final String PATH = ".logast/coverage.txt";

    /** Skip reasons: a short phrase each, the same wherever the rule decides it. */
    public static final String HELPER = "inside a log helper (LOG_HELPER reports the helper)";
    public static final String PROTECTED = "protected message (logging.protectedMessages)";
    public static final String NOT_LITERAL = "message not a literal (built at runtime)";
    public static final String NO_MESSAGE = "no message";
    public static final String MESSAGE_ARGUMENT = "message comes from an argument";
    public static final String FLUENT = "fluent call (format and level not checked)";
    public static final String TRACE = "TRACE level (arguments not checked)";
    public static final String SUPPORT = "logging-support code";

    public static String otherApi(String api) {
        return "other logging API: " + api;
    }

    public static final List<String> OUTCOMES = List.of("fixed", "reported", "ignored", "skipped", "checked");

    private static final String HEADER = "# every log call logast knows and what it did with it: fixed, reported, ignored, "
            + "skipped (with the reason) or checked (no finding)";
    private static final String FIXED_FOOTER = "# fixed lines are kept while their file is unchanged:";
    private static final Pattern FIXED_LINE = Pattern.compile("^(.+):(\\d+)\\s+fixed\\s+(.*)$");
    private static final Pattern FINGERPRINT_LINE = Pattern.compile("^# (.+) ([0-9a-f]{12})$");

    private final Map<J.MethodInvocation, Set<String>> skips = new IdentityHashMap<>();
    /** "file:line" → rules a {@code logast:ignore} silenced there. */
    private final Map<String, Set<String>> ignores = new HashMap<>();

    /** A new scan of the same analysis starts over. */
    public void clear() {
        skips.clear();
        ignores.clear();
    }

    /** A rule passes {@code call} over on purpose, for {@code reason}. */
    public void skip(J.MethodInvocation call, String reason) {
        skips.computeIfAbsent(call, k -> new TreeSet<>()).add(reason);
    }

    /** A {@code logast:ignore} silenced {@code rule} at this line. */
    public void ignored(String file, int line, String rule) {
        ignores.computeIfAbsent(file + ":" + line, k -> new TreeSet<>()).add(rule);
    }

    /** One log call: where it is, its outcome, and what it is based on. */
    public record Entry(String file, int line, int offset, String outcome, String detail) {
        String location() {
            return file + ":" + line;
        }
    }

    /**
     * Every log call of the analysis, in file/line order.
     *
     * @param fixed file → line → "RULE id" an apply changed there (lines of the analysed code)
     */
    public List<Entry> entries(Analysis a, List<WorkOrder> orders, Map<String, ? extends Map<Integer, Set<String>>> fixed) {
        Map<String, List<WorkOrder>> byFile = new HashMap<>();
        orders.forEach(o -> byFile.computeIfAbsent(o.file(), k -> new ArrayList<>()).add(o));
        List<Entry> out = new ArrayList<>();
        for (MethodInfo m : a.index.methods()) {
            SourceFileInfo f = m.file();
            for (LogCall lc : m.facts().logs) {
                out.add(entry(f, lc.call(), skips.getOrDefault(lc.call(), Set.of()), byFile, fixed));
            }
            for (OtherLogCall oc : m.facts().otherLogs) {
                out.add(entry(f, oc.call(), Set.of(otherApi(oc.api())), byFile, fixed));
            }
        }
        out.sort(Comparator.comparing(Entry::file).thenComparingInt(Entry::line).thenComparingInt(Entry::offset));
        return out;
    }

    private Entry entry(SourceFileInfo f, J.MethodInvocation call, Set<String> skipped, Map<String, List<WorkOrder>> byFile,
                        Map<String, ? extends Map<Integer, Set<String>>> fixed) {
        String file = f.relPath();
        int from = Pos.line(call);
        int to = Math.max(from, Pos.endLine(call));
        Set<String> fixedHere = new LinkedHashSet<>();
        Map<Integer, Set<String>> fixedInFile = fixed.containsKey(file) ? fixed.get(file) : Map.of();
        Set<String> ignoredHere = new TreeSet<>();
        for (int l = from; l <= to; l++) {
            fixedHere.addAll(fixedInFile.getOrDefault(l, Set.of()));
            ignoredHere.addAll(ignores.getOrDefault(file + ":" + l, Set.of()));
        }
        Set<String> fixedIds = new HashSet<>();
        fixedHere.forEach(s -> fixedIds.add(s.substring(s.indexOf(' ') + 1)));
        List<String> reported = byFile.getOrDefault(file, List.of()).stream()
                .filter(o -> o.line() >= from && o.line() <= to && !fixedIds.contains(o.id()))
                .sorted(Comparator.comparing(WorkOrder::rule).thenComparing(WorkOrder::id))
                .map(o -> o.rule() + " " + o.id()).distinct().toList();
        List<String> parts = new ArrayList<>();
        String outcome = null;
        if (!fixedHere.isEmpty()) {
            outcome = "fixed";
            parts.add(String.join(", ", fixedHere));
        }
        outcome = part(parts, outcome, "reported", reported);
        outcome = part(parts, outcome, "ignored", ignoredHere);
        outcome = part(parts, outcome, "skipped", skipped);
        if (outcome == null) {
            outcome = "checked";
            parts.add("no finding");
        }
        return new Entry(file, from, Pos.start(call), outcome, String.join("; ", parts));
    }

    private static String part(List<String> parts, String outcome, String kind, Collection<String> items) {
        if (items.isEmpty()) {
            return outcome;
        }
        parts.add((outcome == null ? "" : kind + " ") + String.join(", ", items));
        return outcome == null ? kind : outcome;
    }

    /** The text of coverage.txt; {@code fingerprints}: the files with fixed lines and the content they were fixed in. */
    public static String render(List<Entry> entries, Map<String, String> fingerprints) {
        int width = entries.stream().mapToInt(e -> e.location().length()).max().orElse(0);
        StringBuilder sb = new StringBuilder(HEADER).append('\n');
        for (Entry e : entries) {
            sb.append(e.location()).append(" ".repeat(width - e.location().length() + 2))
                    .append(String.format("%-9s", e.outcome())).append(e.detail()).append('\n');
        }
        if (!fingerprints.isEmpty()) {
            sb.append(FIXED_FOOTER).append('\n');
            fingerprints.forEach((file, hash) -> sb.append("# ").append(file).append(' ').append(hash).append('\n'));
        }
        return sb.toString();
    }

    /**
     * One line for a person (#101): "Log lines: 85 — 51 fixed, 15 with a finding, 11 fine, 8 not checked
     * (.logast/coverage.txt)". The skip reasons and every line's detail stay in the file.
     */
    public static String line(List<Entry> entries) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        List.of("fixed", "reported", "checked", "ignored", "skipped").forEach(o -> counts.put(o, 0));   // the reader's order
        entries.forEach(e -> counts.merge(e.outcome(), 1, Integer::sum));
        List<String> parts = new ArrayList<>();
        counts.forEach((outcome, n) -> {
            if (n > 0) {
                parts.add(n + " " + switch (outcome) {
                    case "fixed" -> "fixed";
                    case "reported" -> "with a finding";
                    case "ignored" -> "silenced by logast:ignore";
                    case "skipped" -> "not checked";
                    default -> "fine";
                });
            }
        });
        return "Log lines: " + entries.size() + (parts.isEmpty() ? "" : " — " + String.join(", ", parts)) + " (" + PATH + ")";
    }

    /**
     * Writes coverage.txt under {@code root} and returns its entries. Lines fixed by an earlier apply stay fixed while
     * their file has the content it had then (its fingerprint in the file's footer).
     *
     * @param fixedNow lines an apply changed in this run (empty for a scan)
     */
    public List<Entry> write(Path root, Analysis a, List<WorkOrder> orders, Map<String, ? extends Map<Integer, Set<String>>> fixedNow) {
        Path target = root.resolve(PATH);
        Map<String, String> current = new HashMap<>();
        a.project.files().forEach(f -> current.put(f.relPath(), fingerprint(f.text())));
        Map<String, Map<Integer, Set<String>>> fixed = new HashMap<>();
        previousFixed(target).forEach((file, lines) -> {
            if (lines.fingerprint() != null && lines.fingerprint().equals(current.get(file)) && !fixedNow.containsKey(file)) {
                fixed.put(file, new HashMap<>(lines.lines()));
            }
        });
        fixedNow.forEach((file, lines) -> lines.forEach((line, ids) ->
                fixed.computeIfAbsent(file, k -> new HashMap<>()).computeIfAbsent(line, k -> new LinkedHashSet<>()).addAll(ids)));
        List<Entry> entries = entries(a, orders, fixed);
        Map<String, String> fingerprints = new TreeMap<>();
        entries.stream().filter(e -> e.outcome().equals("fixed")).forEach(e -> fingerprints.put(e.file(), current.get(e.file())));
        try {
            Files.createDirectories(target.getParent());
            Files.writeString(target, render(entries, fingerprints), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return entries;
    }

    private record Fixed(String fingerprint, Map<Integer, Set<String>> lines) {
    }

    /** The fixed lines of an earlier coverage.txt, per file, with the fingerprint the file had then. */
    private static Map<String, Fixed> previousFixed(Path file) {
        Map<String, Fixed> out = new HashMap<>();
        if (!Files.isRegularFile(file)) {
            return out;
        }
        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException | UncheckedIOException e) {
            return out;   // unreadable: nothing carried over
        }
        Map<String, Map<Integer, Set<String>>> fixed = new HashMap<>();
        Map<String, String> prints = new HashMap<>();
        boolean footer = false;
        for (String l : lines) {
            if (l.equals(FIXED_FOOTER)) {
                footer = true;
                continue;
            }
            Matcher fp = FINGERPRINT_LINE.matcher(l);
            if (footer && fp.matches()) {
                prints.put(fp.group(1), fp.group(2));
                continue;
            }
            Matcher m = FIXED_LINE.matcher(l);
            if (!l.startsWith("#") && m.matches()) {
                String ids = m.group(3).split("; ", 2)[0];
                fixed.computeIfAbsent(m.group(1).strip(), k -> new HashMap<>())
                        .computeIfAbsent(Integer.parseInt(m.group(2)), k -> new LinkedHashSet<>())
                        .addAll(Arrays.asList(ids.split(", ")));
            }
        }
        fixed.forEach((f, ls) -> out.put(f, new Fixed(prints.get(f), ls)));
        return out;
    }

    static String fingerprint(String text) {
        return Hashing.sha256(text).substring(0, 12);
    }
}
