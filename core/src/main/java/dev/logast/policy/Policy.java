package dev.logast.policy;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Project policy: vocabulary (terms, aliases, MDC keys), areas, phrasing, level rules, PII, rule packs and
 * tuning. Built by {@link PolicyLoader} from layers: built-in defaults → {@code extends:} parents → the service's
 * {@code logast.yaml}. {@code glossary} and {@code mdc.keys} are the older flat form and still work.
 *
 * @param parents   {@code extends:} entries (files relative to the declaring file, http(s) URLs, {@code classpath:})
 * @param overrides filled by the loader: every place a later layer overrode an earlier one
 */
public record Policy(@JsonProperty("extends") List<String> parents, Glossary glossary, Mdc mdc, List<String> pii,
                     Entrypoints entrypoints, Logging logging, Support support, Map<String, RuleConfig> rules,
                     Map<String, Term> vocabulary, Map<String, Area> areas, Phrasing phrasing, Levels levels,
                     List<String> packs, List<String> overrides, Verify verify, Metrics metrics, Resilience resilience,
                     Audit audit) {

    /** {@code resilience} pack: timeouts written by HTTP_CLIENT_NO_TIMEOUT (Spring duration syntax). */
    public record Resilience(String httpConnectTimeout, String httpReadTimeout) {
        public Resilience {
            httpConnectTimeout = httpConnectTimeout == null ? "5s" : httpConnectTimeout;
            httpReadTimeout = httpReadTimeout == null ? "30s" : httpReadTimeout;
        }
    }

    /**
     * {@code audit} pack.
     *
     * @param emitters calls that record an audit event: {@code com.x.AuditService} or {@code com.x.ActivityLogger#log};
     *                 empty: any call on a type named *Audit*, or with an *Audit* argument (an AuditAction enum)
     * @param actions  entrypoints that must record one
     */
    public record Audit(List<String> emitters, List<AuditAction> actions) {
        public Audit {
            emitters = emitters == null ? List.of() : List.copyOf(emitters);
            actions = actions == null ? List.of() : List.copyOf(actions);
        }
    }

    /**
     * One kind of auditable entrypoint; any given field must match.
     *
     * @param http   glob on the entrypoint label: {@code "DELETE *"}, {@code "* /admin/**"}
     * @param method regex on the entrypoint method name
     * @param term   vocabulary term in the label or method name ({@code refund})
     */
    public record AuditAction(String http, String method, String term) {
    }

    /**
     * {@code metrics} pack settings.
     *
     * @param tags   tag vocabulary: canonical key → aliases (renamed by METRIC_TAG_KEY) and allowed values
     * @param events business events (lifecycle log messages) without a metric get an Observation
     *               (BUSINESS_EVENT_UNMEASURED); default true
     */
    public record Metrics(Map<String, MetricTag> tags, Boolean events) {
        public Metrics {
            tags = tags == null ? Map.of() : new LinkedHashMap<>(tags);
            events = events == null || events;
        }

        /** Alias → canonical tag key. */
        public Map<String, String> tagAliases() {
            Map<String, String> out = new LinkedHashMap<>();
            tags.forEach((key, t) -> t.aliases().forEach(a -> out.put(a, key)));
            return out;
        }
    }

    /** @param values allowed literal values (empty: any) */
    public record MetricTag(List<String> aliases, List<String> values, Boolean override) {
        public MetricTag {
            aliases = aliases == null ? List.of() : List.copyOf(aliases);
            values = values == null ? List.of() : List.copyOf(values);
        }
    }

    /**
     * {@code logast verify}: which Gradle tasks run the tests ({@code [check]}, {@code [test, intTest, bddTest]}) and
     * where Gradle runs (a repo root, for a service that is a subproject).
     */
    public record Verify(List<String> tasks, String gradleDir) {
        public Verify {
            tasks = tasks == null || tasks.isEmpty() ? List.of("test") : List.copyOf(tasks);
        }
    }

    /**
     * A business term. {@code aliases} are banned words replaced by the term; {@code mdc} describes the MDC key
     * that carries its identifier; {@code logKey} is the name used in a log message's {@code [k=v]} suffix.
     */
    public record Term(List<String> aliases, TermMdc mdc, String logKey, Boolean override) {
        public Term {
            aliases = aliases == null ? List.of() : List.copyOf(aliases);
        }
    }

    /** @param cardinality {@code high} keys (ids) must never become metric tags */
    public record TermMdc(String key, List<String> aliases, KeySource sources, String cardinality) {
        public TermMdc {
            aliases = aliases == null ? List.of() : List.copyOf(aliases);
            sources = sources == null ? new KeySource(null, null) : sources;
            cardinality = cardinality == null ? "high" : cardinality;
        }
    }

    /** A part of the code base (by package) with its own word meanings and required MDC keys. */
    public record Area(List<String> packages, Map<String, Term> vocabulary, List<String> requiredMdc) {
        public Area {
            packages = packages == null ? List.of() : List.copyOf(packages);
            vocabulary = vocabulary == null ? Map.of() : new LinkedHashMap<>(vocabulary);
            requiredMdc = requiredMdc == null ? List.of() : List.copyOf(requiredMdc);
        }
    }

    /** Lifecycle message patterns, e.g. started → {@code "{subject} started"} with recognised variants. */
    public record Phrasing(Map<String, PhraseEvent> events) {
        public Phrasing {
            events = events == null ? Map.of() : new LinkedHashMap<>(events);
        }
    }

    public record PhraseEvent(String canonical, List<String> variants) {
        public PhraseEvent {
            variants = variants == null ? List.of() : List.copyOf(variants);
        }
    }

    /**
     * @param clientErrors     exception types that signal a client/caller error; never logged at ERROR
     * @param clientErrorLevel level to use for them
     * @param noInfoInLoops    INFO+ inside loops should be DEBUG (one line per item floods the logs)
     */
    /**
     * @param volumeBudget lines per day above which an INFO+ statement is a LOG_VOLUME finding (needs traffic data)
     */
    public record Levels(List<String> clientErrors, String clientErrorLevel, Boolean noInfoInLoops, Double volumeBudget,
                         Integer boundedLoopMax) {
        public Levels {
            boundedLoopMax = boundedLoopMax == null ? 20 : boundedLoopMax;
            volumeBudget = volumeBudget == null ? 100_000d : volumeBudget;
            clientErrors = clientErrors == null ? List.of() : List.copyOf(clientErrors);
            clientErrorLevel = clientErrorLevel == null ? "warn" : clientErrorLevel.toLowerCase(Locale.ROOT);
            noInfoInLoops = noInfoInLoops != null && noInfoInLoops;
        }
    }

    /** The company logging-support starter that generated code depends on. */
    /**
     * @param mode           {@code dependency} (add the starter from Nexus) or {@code vendor} (copy its classes into
     *                       the service, no library dependency)
     * @param vendorPackage  target package in vendor mode; default {@code <@SpringBootApplication package>.logging}
     */
    public record Support(String coordinates, String packageName, String mode, String vendorPackage) {
        public Support {
            coordinates = coordinates == null ? "dev.logast:logging-support:0.1.0" : coordinates;
            packageName = packageName == null ? "dev.logast.support" : packageName;
            mode = mode == null ? "dependency" : mode.toLowerCase(Locale.ROOT);
            if (!mode.equals("dependency") && !mode.equals("vendor")) {
                throw new IllegalArgumentException("support.mode must be 'dependency' or 'vendor', was " + mode);
            }
        }

        public boolean vendor() {
            return mode.equals("vendor");
        }

        public String groupAndArtifact() {
            String[] p = coordinates.split(":");
            return p[0] + ":" + p[1];
        }
    }

    public record Glossary(List<String> terms, Map<String, String> synonyms) {
        public Glossary {
            terms = terms == null ? List.of() : List.copyOf(terms);
            synonyms = synonyms == null ? Map.of() : Map.copyOf(synonyms);
        }
    }

    /** @param types fully qualified type name -> accessor expression appended to a variable of that type, e.g. {@code id()} */
    public record KeySource(List<String> params, Map<String, String> types) {
        public KeySource {
            params = params == null ? List.of() : List.copyOf(params);
            types = types == null ? Map.of() : Map.copyOf(types);
        }
    }

    /**
     * @param extractRepeated a vocabulary identifier logged by this many statements of one method goes into MDC once
     *                        (REPEATED_LOG_VALUE); 0 turns it off. Default 2.
     */
    public record Mdc(List<String> ambientKeys, Map<String, KeySource> keys, Integer extractRepeated) {
        public Mdc {
            ambientKeys = ambientKeys == null ? List.of("traceId", "spanId") : List.copyOf(ambientKeys);
            keys = keys == null ? Map.of() : new TreeMap<>(keys);
            extractRepeated = extractRepeated == null ? 2 : extractRepeated;
        }

        public Mdc(List<String> ambientKeys, Map<String, KeySource> keys) {
            this(ambientKeys, keys, null);
        }
    }

    /** @param kafkaObservationEnabled true when {@code spring.kafka.listener.observation-enabled} is set */
    public record Entrypoints(List<String> extraAnnotations, Boolean includeMain, Boolean kafkaObservationEnabled) {
        public Entrypoints {
            extraAnnotations = extraAnnotations == null ? List.of() : List.copyOf(extraAnnotations);
            includeMain = includeMain != null && includeMain;
            kafkaObservationEnabled = kafkaObservationEnabled != null && kafkaObservationEnabled;
        }
    }

    /**
     * @param rewriteExistingMessages true: existing messages are migrated to {@code Text [k=v]}; false: only
     *                                concatenation, glossary and lost exceptions are fixed in existing lines
     */
    /** @param keyStyle spelling of MDC keys and {@code [k=v]} keys outside the vocabulary: dotted, camel, pascal, snake, keep */
    /**
     * @param protectedMessages regexes of messages that alerts or dashboards depend on: never reworded or reformatted,
     *                          only reported (expand-contract: add a stable event field first, change the text later)
     * @param vagueMessages     regexes of avoid-listed wording (MESSAGE_VAGUE), matched case-insensitively on the text
     */
    public record Logging(String loggerName, String swallowedLevel, String entryLevel, String minEntrypointLevel,
                          Boolean rewriteExistingMessages, String keyStyle, List<String> protectedMessages,
                          List<String> vagueMessages) {
        public Logging(String loggerName, String swallowedLevel, String entryLevel, String minEntrypointLevel,
                       Boolean rewriteExistingMessages, String keyStyle) {
            this(loggerName, swallowedLevel, entryLevel, minEntrypointLevel, rewriteExistingMessages, keyStyle, null, null);
        }

        public boolean protectedMessage(String text) {
            return text != null && protectedMessages.stream().anyMatch(r -> java.util.regex.Pattern.compile(r).matcher(text).find());
        }

        public Logging {
            protectedMessages = protectedMessages == null ? List.of() : List.copyOf(protectedMessages);
            vagueMessages = vagueMessages == null ? List.of() : List.copyOf(vagueMessages);
            keyStyle = KeyStyle.parse(keyStyle).toString();   // fails fast on an unknown style
            rewriteExistingMessages = rewriteExistingMessages == null || rewriteExistingMessages;
            loggerName = loggerName == null ? "log" : loggerName;
            swallowedLevel = swallowedLevel == null ? "warn" : swallowedLevel.toLowerCase(Locale.ROOT);
            entryLevel = entryLevel == null ? "info" : entryLevel.toLowerCase(Locale.ROOT);
            minEntrypointLevel = minEntrypointLevel == null ? "info" : minEntrypointLevel.toLowerCase(Locale.ROOT);
        }

        public KeyStyle style() {
            return KeyStyle.parse(keyStyle);
        }
    }

    public record RuleConfig(Boolean enabled, String severity) {
    }

    public Policy {
        parents = parents == null ? List.of() : List.copyOf(parents);
        vocabulary = vocabulary == null ? Map.of() : new LinkedHashMap<>(vocabulary);
        areas = areas == null ? Map.of() : new LinkedHashMap<>(areas);
        phrasing = phrasing == null ? new Phrasing(null) : phrasing;
        levels = levels == null ? new Levels(null, null, null, null, null) : levels;
        packs = packs == null ? List.of("logging", "tracing") : List.copyOf(packs);
        overrides = overrides == null ? List.of() : List.copyOf(overrides);
        glossary = glossary == null ? new Glossary(null, null) : glossary;
        mdc = mdc == null ? new Mdc(null, null) : mdc;
        pii = pii == null ? List.of() : List.copyOf(pii);
        entrypoints = entrypoints == null ? new Entrypoints(null, null, null) : entrypoints;
        logging = logging == null ? new Logging(null, null, null, null, null, null) : logging;
        support = support == null ? new Support(null, null, null, null) : support;
        rules = rules == null ? Map.of() : Map.copyOf(rules);
        verify = verify == null ? new Verify(null, null) : verify;
        metrics = metrics == null ? new Metrics(null, null) : metrics;
        resilience = resilience == null ? new Resilience(null, null) : resilience;
        audit = audit == null ? new Audit(null, null) : audit;
    }

    /** Built-in defaults only (no project file). */
    public static Policy defaults() {
        return PolicyLoader.load(null, null);
    }

    public static Policy load(Path file) throws IOException {
        return PolicyLoader.load(file.toAbsolutePath().getParent(), file);
    }

    /** Built-in defaults → extends chain → {@code <root>/logast.yaml} (or the explicit file). */
    public static Policy resolve(Path root, Path explicit) throws IOException {
        return PolicyLoader.load(root, explicit);
    }

    public Policy withPacks(List<String> selected) {
        return new Policy(parents, glossary, mdc, pii, entrypoints, logging, support, rules, vocabulary, areas,
                phrasing, levels, selected, overrides, verify, metrics, resilience, audit);
    }

    public Policy withLogging(Logging l) {
        return new Policy(parents, glossary, mdc, pii, entrypoints, l, support, rules, vocabulary, areas,
                phrasing, levels, packs, overrides, verify, metrics, resilience, audit);
    }

    public Policy withLevels(Levels l) {
        return new Policy(parents, glossary, mdc, pii, entrypoints, logging, support, rules, vocabulary, areas,
                phrasing, l, packs, overrides, verify, metrics, resilience, audit);
    }

    public Policy withSupport(Support s) {
        return new Policy(parents, glossary, mdc, pii, entrypoints, logging, s, rules, vocabulary, areas,
                phrasing, levels, packs, overrides, verify, metrics, resilience, audit);
    }

    // ---- derived views (vocabulary + the older flat glossary/mdc form) -------------------------------------

    /** Preferred domain terms. */
    public List<String> terms() {
        LinkedHashSet<String> out = new LinkedHashSet<>(vocabulary.keySet());
        out.addAll(glossary.terms());
        return List.copyOf(out);
    }

    /** Banned word → canonical term, global plus the given area's (area wins). */
    public Map<String, String> synonyms(String area) {
        Map<String, String> out = new LinkedHashMap<>(glossary.synonyms());
        vocabulary.forEach((term, t) -> t.aliases().forEach(a -> out.put(a, term)));
        Area ar = area == null ? null : areas.get(area);
        if (ar != null) {
            ar.vocabulary().forEach((term, t) -> t.aliases().forEach(a -> out.put(a, term)));
        }
        return out;
    }

    /**
     * A vocabulary key in the house spelling ({@code logging.keyStyle}): with {@code camel}, {@code order.id} becomes
     * {@code orderId} everywhere (registry, aliases, generated MdcScope keys); the written spelling becomes an alias.
     */
    public String styledKey(String key) {
        return key == null ? null : logging.style().apply(key);
    }

    /** MDC key → where its value comes from. */
    public Map<String, KeySource> mdcRegistry() {
        Map<String, KeySource> out = new TreeMap<>();
        mdc.keys().forEach((k, v) -> out.merge(styledKey(k), v, (a, b) -> new KeySource(concat(a.params(), b.params()), merge(a.types(), b.types()))));
        vocabulary.values().forEach(t -> {
            if (t.mdc() != null && t.mdc().key() != null) {
                out.merge(styledKey(t.mdc().key()), t.mdc().sources(), (a, b) -> new KeySource(
                        concat(a.params(), b.params()), merge(a.types(), b.types())));
            }
        });
        return out;
    }

    /** Non-canonical MDC key → canonical key (e.g. orderId → order.id, or order.id → orderId under keyStyle camel). */
    public Map<String, String> mdcAliases() {
        Map<String, String> out = new LinkedHashMap<>();
        mdc.keys().keySet().forEach(k -> {
            if (!styledKey(k).equals(k)) {
                out.put(k, styledKey(k));
            }
        });
        vocabulary.values().forEach(t -> {
            if (t.mdc() != null && t.mdc().key() != null) {
                String canonical = styledKey(t.mdc().key());
                t.mdc().aliases().forEach(a -> {
                    if (!a.equals(canonical)) {
                        out.put(a, canonical);
                    }
                });
                if (!t.mdc().key().equals(canonical)) {
                    out.put(t.mdc().key(), canonical);
                }
            }
        });
        return out;
    }

    /**
     * The key an MDC call should use instead of {@code key}, or null when it is fine: a vocabulary alias maps to its
     * canonical key; any other key that is not in the vocabulary and not ambient is spelled in {@code logging.keyStyle}.
     */
    public String canonicalMdcKey(String key) {
        if (key == null) {
            return null;
        }
        String alias = mdcAliases().get(key);
        if (alias != null) {
            return alias;
        }
        if (mdcRegistry().containsKey(key) || mdc.ambientKeys().contains(key)) {
            return null;
        }
        String styled = logging.style().apply(key);
        return styled.equals(key) ? null : styled;
    }

    /** Name for an MDC key inside a {@code [k=v]} suffix. */
    public String logKey(String mdcKey) {
        for (Term t : vocabulary.values()) {
            if (t.mdc() != null && mdcKey.equals(styledKey(t.mdc().key())) && t.logKey() != null) {
                return styledKey(t.logKey());
            }
        }
        return mdcKey;
    }

    /** Registry keys carry ids, so they are high-cardinality unless the vocabulary says {@code cardinality: low}. */
    public boolean highCardinality(String mdcKey) {
        for (Term t : vocabulary.values()) {
            if (t.mdc() != null && mdcKey.equals(styledKey(t.mdc().key()))) {
                return "high".equals(t.mdc().cardinality());
            }
        }
        return mdc.keys().keySet().stream().anyMatch(k -> styledKey(k).equals(mdcKey));
    }

    /** Area whose packages contain the class, longest package prefix first; null when none. */
    public String areaOf(String classFqn) {
        String best = null;
        int bestLen = -1;
        for (var e : areas.entrySet()) {
            for (String pkg : e.getValue().packages()) {
                if ((classFqn.startsWith(pkg + ".") || classFqn.equals(pkg)) && pkg.length() > bestLen) {
                    best = e.getKey();
                    bestLen = pkg.length();
                }
            }
        }
        return best;
    }

    public boolean packSelected(String pack) {
        return packs.contains(pack);
    }

    private static List<String> concat(List<String> a, List<String> b) {
        LinkedHashSet<String> s = new LinkedHashSet<>(a);
        s.addAll(b);
        return List.copyOf(s);
    }

    private static Map<String, String> merge(Map<String, String> a, Map<String, String> b) {
        Map<String, String> m = new LinkedHashMap<>(a);
        m.putAll(b);
        return m;
    }

    public boolean ruleEnabled(String rule) {
        RuleConfig c = rules.get(rule);
        return c == null || c.enabled() == null || c.enabled();
    }

    public String severity(String rule, String fallback) {
        RuleConfig c = rules.get(rule);
        return c == null || c.severity() == null ? fallback : c.severity().toLowerCase(Locale.ROOT);
    }
}
