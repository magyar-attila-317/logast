package dev.logast.policy;

import dev.logast.rules.Rules;

import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Checks what a merged {@link Policy} means, not only its structure (#56): Jackson already rejects unknown keys, this
 * rejects values that select, match or generate nothing sensible. Each problem names its YAML path.
 * <ul>
 *   <li>an <b>error</b> when the setting cannot mean anything (an unknown rule id or pack, a regex that does not
 *       compile, a log level that does not exist, a word that is both a term and another term's alias);</li>
 *   <li>a <b>warning</b> when it is probably wrong but has no effect (a setting another one overrides or that only
 *       applies to another mode).</li>
 * </ul>
 */
public final class PolicyValidator {
    /** @param error the setting cannot mean anything: loading fails */
    public record Problem(boolean error, String path, String message) {
    }

    /** Where a YAML path was set, and whether a layer other than the built-in defaults set it. */
    public interface Sources {
        boolean explicit(String path);
    }

    static final List<String> LEVELS = List.of("trace", "debug", "info", "warn", "error");
    static final List<String> SEVERITIES = List.of("high", "medium", "low");
    static final List<String> FORMATS = List.of("ecs", "logstash", "gelf", "none");
    private static final Pattern JAVA_IDENTIFIER = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*");
    private static final Pattern QUALIFIED_NAME = Pattern.compile("[A-Za-z_$][\\w$]*(\\.[A-Za-z_$][\\w$]*)+");
    /** Spring Boot's DurationStyle.SIMPLE: a number with an optional unit (milliseconds without one). */
    private static final Pattern SIMPLE_DURATION = Pattern.compile("[+-]?\\d+(ns|us|ms|s|m|h|d)?", Pattern.CASE_INSENSITIVE);

    private final List<Problem> problems = new ArrayList<>();

    private PolicyValidator() {
    }

    public static List<Problem> check(Policy p, Sources sources) {
        PolicyValidator v = new PolicyValidator();
        v.run(p, sources);
        return v.problems;
    }

    /** Packs given on the command line ({@code --pack}): an unknown one selects nothing. */
    public static void checkPacks(List<String> packs, String where) {
        List<String> bad = new ArrayList<>();
        for (String pack : packs) {
            if (!Rules.packs().contains(pack)) {
                bad.add(where + " " + pack + ": unknown pack" + didYouMean(pack, Rules.packs()) + " (known: "
                        + String.join(", ", new TreeSet<>(Rules.packs())) + ")");
            }
        }
        if (!bad.isEmpty()) {
            throw new IllegalArgumentException(String.join("\n", bad));
        }
    }

    private void run(Policy p, Sources sources) {
        for (int i = 0; i < p.packs().size(); i++) {
            String pack = p.packs().get(i);
            if (!Rules.packs().contains(pack)) {
                error("packs[" + i + "]", "unknown pack '" + pack + "', it selects no rule" + didYouMean(pack, Rules.packs())
                        + " (known: " + String.join(", ", new TreeSet<>(Rules.packs())) + ")");
            }
        }
        p.rules().forEach((id, cfg) -> {
            if (!Rules.all().contains(id)) {
                error("rules." + id, "unknown rule id" + didYouMean(id, Rules.all()) + " (`logast rules` lists them)");
            } else if (cfg != null && cfg.severity() != null && !SEVERITIES.contains(cfg.severity().toLowerCase(Locale.ROOT))) {
                error("rules." + id + ".severity", "'" + cfg.severity() + "' is not a severity; use one of " + SEVERITIES);
            }
        });

        Policy.Logging l = p.logging();
        level("logging.swallowedLevel", l.swallowedLevel());
        level("logging.entryLevel", l.entryLevel());
        level("logging.minEntrypointLevel", l.minEntrypointLevel());
        level("levels.clientErrorLevel", p.levels().clientErrorLevel());
        if (!JAVA_IDENTIFIER.matcher(l.loggerName()).matches()) {
            error("logging.loggerName", "'" + l.loggerName() + "' is not a Java identifier; generated code declares and "
                    + "calls a logger by this name (e.g. log)");
        }
        regexes("logging.protectedMessages", l.protectedMessages(), 0);
        regexes("logging.vagueMessages", l.vagueMessages(), Pattern.CASE_INSENSITIVE);
        if (!FORMATS.contains(l.structuredFormat()) && !QUALIFIED_NAME.matcher(l.structuredFormat()).matches()) {
            error("logging.structuredFormat", "'" + l.structuredFormat() + "' is not a structured log format; use one of "
                    + FORMATS + didYouMean(l.structuredFormat(), FORMATS)
                    + ", or the fully qualified name of a StructuredLogFormatter class");
        }

        if (p.support().coordinates().split(":").length < 3) {
            error("support.coordinates", "'" + p.support().coordinates() + "' is not group:artifact:version");
        }

        if (p.mdc().extractRepeated() < 0) {
            error("mdc.extractRepeated", "must be 0 (off) or more, was " + p.mdc().extractRepeated());
        }
        if (p.levels().boundedLoopMax() < 0) {
            error("levels.boundedLoopMax", "must be 0 or more, was " + p.levels().boundedLoopMax());
        }
        if (p.levels().noInfoInLoops() && !p.levels().infoInLoops().equals("never") && sources.explicit("levels.infoInLoops")) {
            warning("levels.noInfoInLoops", "true overrides levels.infoInLoops: " + p.levels().infoInLoops()
                    + " (it means infoInLoops: never); keep only one of them");
        }

        if (p.metrics().catalogClass() != null && !p.metrics().catalog().equals("single")) {
            warning("metrics.catalogClass", "is only used with metrics.catalog: single (catalog is "
                    + p.metrics().catalog() + "), so it has no effect");
        }

        duration("resilience.httpConnectTimeout", p.resilience().httpConnectTimeout());
        duration("resilience.httpReadTimeout", p.resilience().httpReadTimeout());
        duration("resilience.dbQueryTimeout", p.resilience().dbQueryTimeout());

        // #24: a pinned starter older than the calls this logast writes
        if (!p.support().vendor() && sources.explicit("support.coordinates")) {
            String older = Policy.Support.olderStarter(p.support().version(), Policy.Support.releaseVersion());
            if (older != null) {
                warning("support.coordinates", older);
            }
        }

        for (String b : p.queries().backends()) {
            if (!Policy.Queries.BACKENDS.contains(b)) {
                error("queries.backends", "'" + b + "' is not a query backend; use any of " + Policy.Queries.BACKENDS
                        + didYouMean(b, Policy.Queries.BACKENDS));
            }
        }
        // Splunk index names: lower-case letters, digits, '_' and '-', not starting with '_' or '-'
        if (!p.queries().splunk().index().isEmpty() && !p.queries().splunk().index().matches("[a-z0-9][a-z0-9_-]*")) {
            error("queries.splunk.index", "'" + p.queries().splunk().index() + "' is not a Splunk index name (lower-case "
                    + "letters, digits, '_' and '-', not starting with '_' or '-')");
        }
        if (!p.queries().prometheus().serviceLabel().isEmpty()
                && !p.queries().prometheus().serviceLabel().matches("[a-zA-Z_][a-zA-Z0-9_]*")) {
            error("queries.prometheus.serviceLabel", "'" + p.queries().prometheus().serviceLabel()
                    + "' is not a Prometheus label name ([a-zA-Z_][a-zA-Z0-9_]*)");
        }

        List<Policy.AuditAction> actions = p.audit().actions();
        for (int i = 0; i < actions.size(); i++) {
            Policy.AuditAction a = actions.get(i);
            String path = "audit.actions[" + i + "]";
            if (blank(a.http()) && blank(a.method()) && blank(a.term())) {
                error(path, "matches no entrypoint: give http, method or term");
            }
            if (a.method() != null) {
                regex(path + ".method", a.method(), 0);
            }
        }
        for (int i = 0; i < p.audit().emitters().size(); i++) {
            String e = p.audit().emitters().get(i);
            if (!QUALIFIED_NAME.matcher(e.contains("#") ? e.substring(0, e.indexOf('#')) : e).matches()
                    || e.contains("#") && !JAVA_IDENTIFIER.matcher(e.substring(e.indexOf('#') + 1)).matches()) {
                error("audit.emitters[" + i + "]", "'" + e + "' is not a fully qualified type, or type#method "
                        + "(com.example.AuditService, com.example.ActivityLogger#log)");
            }
        }

        vocabulary("vocabulary", p.vocabulary());
        p.areas().forEach((name, area) -> {
            if (area.packages().isEmpty()) {
                warning("areas." + name + ".packages", "is empty: no class belongs to area '" + name
                        + "', so its vocabulary and requiredMdc apply nowhere");
            }
            vocabulary("areas." + name + ".vocabulary", area.vocabulary());
        });
    }

    /**
     * Within one vocabulary, a word is either a term or an alias of one: an alias equal to another term would rewrite
     * that term away, and one alias on two terms rewrites to whichever comes last. The same for MDC keys.
     */
    private void vocabulary(String base, Map<String, Policy.Term> vocabulary) {
        Map<String, String> aliasOf = new HashMap<>();
        Map<String, String> mdcKeyOf = new HashMap<>();
        vocabulary.forEach((term, t) -> {
            if (t.mdc() != null && t.mdc().key() != null) {
                mdcKeyOf.put(t.mdc().key(), term);
            }
        });
        Map<String, String> mdcAliasOf = new HashMap<>();
        vocabulary.forEach((term, t) -> {
            for (int i = 0; i < t.aliases().size(); i++) {
                String alias = t.aliases().get(i);
                String path = base + "." + term + ".aliases[" + i + "]";
                if (!alias.equals(term) && vocabulary.containsKey(alias)) {
                    error(path, "'" + alias + "' is itself a vocabulary term: as an alias of '" + term
                            + "' it would be rewritten to '" + term + "'; remove one of the two");
                }
                String before = aliasOf.putIfAbsent(alias, term);
                if (before != null && !before.equals(term)) {
                    error(path, "'" + alias + "' is already an alias of '" + before + "'; one word can only be replaced by one term");
                }
            }
            if (t.mdc() == null) {
                if (t.logKey() != null) {
                    warning(base + "." + term + ".logKey", "has no effect without " + base + "." + term + ".mdc.key");
                }
                return;
            }
            if (t.mdc().cardinality() != null && !List.of("high", "low").contains(t.mdc().cardinality())) {
                error(base + "." + term + ".mdc.cardinality", "'" + t.mdc().cardinality() + "' is not high or low");
            }
            for (int i = 0; i < t.mdc().aliases().size(); i++) {
                String alias = t.mdc().aliases().get(i);
                String path = base + "." + term + ".mdc.aliases[" + i + "]";
                String owner = mdcKeyOf.get(alias);
                if (owner != null && !owner.equals(term)) {
                    error(path, "'" + alias + "' is the MDC key of '" + owner + "': as an alias of '" + term
                            + "' it would be renamed to " + t.mdc().key() + "; remove one of the two");
                }
                String before = mdcAliasOf.putIfAbsent(alias, term);
                if (before != null && !before.equals(term)) {
                    error(path, "'" + alias + "' is already an MDC alias of '" + before + "'; one key can only be renamed to one key");
                }
            }
        });
    }

    private void level(String path, String value) {
        if (value != null && !LEVELS.contains(value)) {
            error(path, "'" + value + "' is not a log level; use one of " + LEVELS + didYouMean(value, LEVELS));
        }
    }

    private void regexes(String path, List<String> values, int flags) {
        for (int i = 0; i < values.size(); i++) {
            regex(path + "[" + i + "]", values.get(i), flags);
        }
    }

    private void regex(String path, String value, int flags) {
        try {
            Pattern.compile(value, flags);
        } catch (PatternSyntaxException e) {
            error(path, "invalid regex: " + e.getDescription() + " near index " + e.getIndex() + " of \"" + value + "\"");
        }
    }

    private void duration(String path, String value) {
        String v = value.strip();
        if (SIMPLE_DURATION.matcher(v).matches()) {
            return;
        }
        try {
            if (v.matches("[+-]?[pP].*")) {
                Duration.parse(v);
                return;
            }
        } catch (DateTimeParseException e) {
            // reported below
        }
        error(path, "'" + value + "' is not a Spring duration (30s, 500ms, 5m, PT30S)");
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private void error(String path, String message) {
        problems.add(new Problem(true, path, message));
    }

    private void warning(String path, String message) {
        problems.add(new Problem(false, path, message));
    }

    /** "; did you mean X?" for the closest known value within a few edits, else "". */
    public static String didYouMean(String value, Collection<String> known) {
        String best = null;
        int bestDistance = Integer.MAX_VALUE;
        String v = value.toLowerCase(Locale.ROOT);
        for (String k : known) {
            String lk = k.toLowerCase(Locale.ROOT);
            // warning → warn, metric → metrics: a prefix counts as one edit
            int d = !v.isEmpty() && (v.startsWith(lk) || lk.startsWith(v)) ? 1 : distance(v, lk);
            if (d < bestDistance) {
                best = k;
                bestDistance = d;
            }
        }
        return best != null && bestDistance <= Math.max(2, value.length() / 4) ? "; did you mean " + best + "?" : "";
    }

    private static int distance(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1));
            }
            int[] t = prev;
            prev = cur;
            cur = t;
        }
        return prev[b.length()];
    }
}
