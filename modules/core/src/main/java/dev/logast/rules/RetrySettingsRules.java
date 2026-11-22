package dev.logast.rules;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import dev.logast.Analysis;
import dev.logast.model.Annotations;
import dev.logast.model.ClassInfo;
import dev.logast.model.MethodInfo;
import dev.logast.orders.Edit;
import dev.logast.parse.Pos;
import dev.logast.parse.SourceFileInfo;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * RETRY_SETTINGS_HARDCODED (#104): retry attempts and delays written into {@code @Retryable} need a rebuild to change.
 * AUTO: they move into application.yml, bound by a generated {@code @ConfigurationProperties} record ({@code RetrySettings},
 * in the package of the @SpringBootApplication class), and the annotation reads them through its String attributes.
 * <ul>
 *   <li>Spring Framework 7 {@code org.springframework.resilience.annotation.Retryable}: {@code maxRetriesString},
 *       {@code delayString}, {@code jitterString}, {@code multiplierString}, {@code maxDelayString},
 *       {@code timeoutString} "support Spring-style ${...} placeholders as well as SpEL expressions" (javadoc of each
 *       attribute, spring-context 7.0); the duration ones also take a unit ({@code 1000ms}, {@code 2s}). Resolved once by
 *       RetryAnnotationBeanPostProcessor through the context's embedded value resolver.</li>
 *   <li>Spring Retry 2.x {@code org.springframework.retry.annotation.Retryable#maxAttemptsExpression} and
 *       {@code @Backoff(delayExpression, maxDelayExpression, multiplierExpression)}: "Use #{...} for one-time evaluation
 *       during initialization" (javadoc); AnnotationAwareRetryOperationsInterceptor resolves ${...} placeholders in them
 *       first (ConfigurableBeanFactory#resolveEmbeddedValue), so {@code #{${key}}} reads the property once. Its values
 *       are numbers: delays in plain milliseconds.</li>
 * </ul>
 * By default every usage of a family reads one shared block ({@code resilience.retry.defaults} for Spring Framework's,
 * {@code resilience.retry.spring-retry-defaults} for Spring Retry's), holding the most common literal settings. A usage
 * whose literals differ from the shared block gets its own block ({@code resilience.retry.usages.<class>.<method>}), so
 * no behaviour changes silently. {@code .logast/decisions/retry.yaml} lists every usage: {@code specific: true} gives it its own
 * block (starting from the shared values, or {@code values:} given there), {@code false} points it back to the shared
 * one (its block stays in application.yml, unused: no value typed in is lost). Class lists ({@code includes},
 * {@code retryFor}, ...), predicates and {@code timeUnit} stay in the annotation; a usage with a setting that is not a
 * literal (a constant, an expression of its own) is left alone.
 */
public final class RetrySettingsRules {
    public static final String WORK_FILE = dev.logast.LogastDir.RETRY;
    static final String PREFIX = "resilience.retry.";
    static final String SETTINGS_CLASS = "RetrySettings";
    private static final String ENABLE_PROPS = "org.springframework.boot.context.properties.EnableConfigurationProperties";
    private static final String SCAN_PROPS = "org.springframework.boot.context.properties.ConfigurationPropertiesScan";
    private static final Pattern REF = Pattern.compile(
            "^(?:#\\{)?\\$\\{resilience\\.retry\\.(defaults|spring-retry-defaults|usages\\.[a-z0-9-]+\\.[a-z0-9-]+)\\.([a-z-]+)}}?$");

    /**
     * A setting: the yml key, the annotation attributes holding it as a literal, the String attribute that references
     * it, and the library default ({@code main}: written even when the annotation leaves it out).
     */
    record Knob(String key, List<String> attrs, String twin, boolean duration, boolean decimal, boolean main, String libDefault,
                boolean backoff) {
    }

    enum Family {
        FRAMEWORK("Spring Framework @Retryable", "defaults", List.of(
                new Knob("max-retries", List.of("maxRetries"), "maxRetriesString", false, false, true, "3", false),
                new Knob("delay", List.of("delay"), "delayString", true, false, true, "1000", false),
                new Knob("jitter", List.of("jitter"), "jitterString", true, false, false, "0", false),
                new Knob("multiplier", List.of("multiplier"), "multiplierString", false, true, false, "1.0", false),
                new Knob("max-delay", List.of("maxDelay"), "maxDelayString", true, false, false, String.valueOf(Long.MAX_VALUE), false),
                new Knob("timeout", List.of("timeout"), "timeoutString", true, false, false, "0", false))),
        SPRING_RETRY("Spring Retry @Retryable", "spring-retry-defaults", List.of(
                new Knob("max-attempts", List.of("maxAttempts"), "maxAttemptsExpression", false, false, true, "3", false),
                new Knob("delay", List.of("delay", "value"), "delayExpression", true, false, true, "1000", true),
                new Knob("max-delay", List.of("maxDelay"), "maxDelayExpression", true, false, false, "0", true),
                new Knob("multiplier", List.of("multiplier"), "multiplierExpression", false, true, false, "0.0", true)));

        final String label;
        final String shared;
        final List<Knob> knobs;

        Family(String label, String shared, List<Knob> knobs) {
            this.label = label;
            this.shared = shared;
            this.knobs = knobs;
        }

        Knob knob(String key) {
            return knobs.stream().filter(k -> k.key().equals(key)).findFirst().orElse(null);
        }

        /** How the annotation references a key. */
        String ref(String block, String key) {
            String placeholder = "${" + PREFIX + block + "." + key + "}";
            return this == FRAMEWORK ? placeholder : "#{" + placeholder + "}";
        }

        /** The yml value for a canonical value (durations in milliseconds). */
        String yml(Knob k, String canon) {
            return k.duration() && this == FRAMEWORK ? canon + "ms" : canon;
        }
    }

    /**
     * One annotated method. {@code literals}: canonical values written in the annotation (unmanaged); {@code block}: the
     * block its String attributes reference (managed), else null.
     */
    record Site(MethodInfo m, J.Annotation a, Family family, J.Annotation backoff, Map<String, String> literals, String block,
                String label, String classKey, String methodKey) {
        String own() {
            return "usages." + classKey + "." + methodKey;
        }

        boolean specific() {
            return block != null && block.startsWith("usages.");
        }
    }

    /** A usage as listed in the work file. */
    public record Usage(String label, String family, String file, int line, boolean managed, boolean specific) {
    }

    /** What the person wrote in the work file for a usage. */
    record Mark(Boolean specific, Map<String, String> values) {
    }

    /**
     * A call RETRY_RECOMMENDED recommends retrying (#109), as the work file lists it.
     *
     * @param caller "request" (one quick retry) or "job" (retries with backoff)
     * @param from   the entrypoints that reach it
     */
    public record Candidate(String method, String caller, String from, String file, int line, String via, boolean annotatable) {
    }

    /**
     * The person's answer per candidate ({@code candidates.<method>.retry}): "yes" (add the retry), "no" (never ask
     * again) or "" (undecided).
     */
    public static Map<String, String> candidateMarks(Path root) {
        Path p = root.resolve(WORK_FILE);
        Map<String, String> out = new LinkedHashMap<>();
        if (!Files.exists(p)) {
            return out;
        }
        try {
            JsonNode n = new ObjectMapper(new YAMLFactory()).readTree(Files.readString(p));
            if (n != null && n.path("candidates").isObject()) {
                n.get("candidates").fields().forEachRemaining(e -> {
                    JsonNode r = e.getValue() == null ? null : e.getValue().get("retry");
                    String v = r == null || r.isNull() ? "" : r.isBoolean() ? (r.asBoolean() ? "yes" : "no") : r.asText().strip();
                    out.put(e.getKey(), v.equalsIgnoreCase("true") ? "yes" : v.equalsIgnoreCase("false") ? "no" : v.toLowerCase(Locale.ROOT));
                });
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out;
    }

    /**
     * #122, {@code logast review}: sets the answer of candidate {@code method} ("yes", "no" or "" for undecided) in
     * place, so the file's comments stay; the next scan rewrites the file from the answers as usual. False when the
     * candidate is not listed.
     */
    public static boolean setCandidate(Path root, String method, String answer) {
        String v = answer == null ? "" : answer.strip().toLowerCase(Locale.ROOT);
        if (!v.isEmpty() && !v.equals("yes") && !v.equals("no")) {
            throw new IllegalArgumentException("retry: yes, no or empty, not " + answer);
        }
        Path p = root.resolve(WORK_FILE);
        if (!Files.exists(p)) {
            return false;
        }
        try {
            List<String> lines = new ArrayList<>(Files.readAllLines(p));
            boolean inCandidates = false;
            for (int i = 0; i < lines.size(); i++) {
                String l = lines.get(i);
                if (!l.startsWith(" ") && !l.startsWith("#")) {
                    inCandidates = l.startsWith("candidates:");
                } else if (inCandidates && (l.startsWith("  " + method + ":") && !l.startsWith("   "))) {
                    for (int j = i + 1; j < lines.size() && lines.get(j).startsWith("    "); j++) {
                        Matcher m = Pattern.compile("^(    retry: )(\"[^\"]*\"|[^#\\s]*)(.*)$").matcher(lines.get(j));
                        if (m.matches()) {
                            lines.set(j, m.group(1) + "\"" + v + "\"" + m.group(3));
                            Files.writeString(p, String.join("\n", lines) + "\n");
                            return true;
                        }
                    }
                }
            }
            return false;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private final RuleContext ctx;

    RetrySettingsRules(RuleContext ctx) {
        this.ctx = ctx;
    }

    // ---- finding the usages --------------------------------------------------------------------------------------

    static List<Site> sites(Analysis a, Collection<MethodInfo> methods) {
        List<Site> out = new ArrayList<>();
        for (MethodInfo m : methods) {
            for (J.Annotation ann : m.decl().getLeadingAnnotations()) {
                String fqn = ResilienceRules.annotationFqn(ann, m.file());
                Family fam = (ResilienceRules.RESILIENCE_PKG + "Retryable").equals(fqn) ? Family.FRAMEWORK
                        : (ResilienceRules.SPRING_RETRY_PKG + "Retryable").equals(fqn) ? Family.SPRING_RETRY : null;
                if (fam != null) {
                    Site s = parse(m, ann, fam);
                    if (s != null) {
                        out.add(s);
                    }
                }
            }
        }
        // overloads (or two classes of one simple name) would share a block: left alone
        Map<String, Long> labels = new HashMap<>();
        out.forEach(s -> labels.merge(s.classKey() + "." + s.methodKey(), 1L, Long::sum));
        out.removeIf(s -> labels.get(s.classKey() + "." + s.methodKey()) > 1);
        out.sort(Comparator.comparing((Site s) -> s.m().file().relPath()).thenComparingInt(s -> s.m().line()));
        return out;
    }

    private static List<Expression> args(J.Annotation a) {
        return a.getArguments() == null ? List.of() : a.getArguments().stream().filter(e -> !(e instanceof J.Empty)).toList();
    }

    private static String name(Expression arg) {
        return arg instanceof J.Assignment as && as.getVariable() instanceof J.Identifier id ? id.getSimpleName() : "value";
    }

    private static Expression value(Expression arg) {
        return arg instanceof J.Assignment as ? as.getAssignment() : arg;
    }

    /** The usage, or null when it has a setting logast cannot move (not a literal, another expression, a unit below ms). */
    private static Site parse(MethodInfo m, J.Annotation a, Family fam) {
        Map<String, String> literals = new LinkedHashMap<>();
        Set<String> blocks = new HashSet<>();
        long unit = 1;
        J.Annotation backoff = null;
        List<Map.Entry<Expression, Boolean>> all = new ArrayList<>();   // argument, inside @Backoff
        for (Expression arg : args(a)) {
            if (fam == Family.SPRING_RETRY && name(arg).equals("backoff")) {
                if (!(value(arg) instanceof J.Annotation b)) {
                    return null;
                }
                backoff = b;
                args(b).forEach(x -> all.add(Map.entry(x, true)));
                continue;
            }
            if (fam == Family.FRAMEWORK && name(arg).equals("timeUnit")) {
                String u = value(arg).printTrimmed().replaceFirst("^.*\\.", "");
                Long ms = Map.of("MILLISECONDS", 1L, "SECONDS", 1000L, "MINUTES", 60_000L, "HOURS", 3_600_000L, "DAYS", 86_400_000L).get(u);
                if (ms == null) {
                    return null;
                }
                unit = ms;
            }
            all.add(Map.entry(arg, false));
        }
        for (Map.Entry<Expression, Boolean> e : all) {
            boolean inBackoff = e.getValue();
            Expression arg = e.getKey();
            String n = name(arg);
            for (Knob k : fam.knobs) {
                if (k.backoff() != inBackoff) {
                    continue;
                }
                if (k.attrs().contains(n)) {
                    String canon = value(arg) instanceof J.Literal lit ? canon(k, lit.getValue(), unit) : null;
                    if (canon == null) {
                        return null;
                    }
                    literals.put(k.key(), canon);
                } else if (k.twin().equals(n)) {
                    Matcher r = value(arg) instanceof J.Literal lit && lit.getValue() instanceof String s ? REF.matcher(s) : null;
                    if (r == null || !r.matches() || !r.group(2).equals(k.key())) {
                        return null;   // the team's own placeholder or expression
                    }
                    blocks.add(r.group(1));
                }
            }
        }
        if (blocks.size() > 1 || !blocks.isEmpty() && !literals.isEmpty()) {
            return null;
        }
        String block = blocks.isEmpty() ? null : blocks.iterator().next();
        if (block != null && block.startsWith("defaults") != (fam == Family.FRAMEWORK) && !block.startsWith("usages.")) {
            return null;   // the other family's shared block
        }
        String cls = m.owner().simpleName();
        return new Site(m, a, fam, backoff, literals, block, cls + "." + m.name(), Text.kebab(cls), Text.kebab(m.name()));
    }

    private static String canon(Knob k, Object v, long unit) {
        if (!(v instanceof Number n)) {
            return null;
        }
        if (k.decimal()) {
            return Double.toString(n.doubleValue());
        }
        return String.valueOf(k.duration() ? n.longValue() * unit : n.longValue());
    }

    /** A value from application.yml (or the work file) in canonical form. */
    private static String canonFromConfig(Knob k, String v) {
        try {
            if (k.decimal()) {
                return Double.toString(Double.parseDouble(v.strip()));
            }
            if (k.duration()) {
                Long ms = ResilienceRules.durationMillis(v, false);
                return ms == null ? v.strip() : String.valueOf(ms);
            }
            return String.valueOf(Long.parseLong(v.strip()));
        } catch (NumberFormatException e) {
            return v.strip();
        }
    }

    /** Every setting with the library default filled in: what the usage does at runtime. */
    private static Map<String, String> effective(Family fam, Map<String, String> values) {
        Map<String, String> out = new TreeMap<>();
        fam.knobs.forEach(k -> out.put(k.key(), values.getOrDefault(k.key(), k.libDefault())));
        return out;
    }

    /** The settings block of a usage written as literals: main settings always, the others where written. */
    private static Map<String, String> written(Family fam, Map<String, String> literals) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Knob k : fam.knobs) {
            if (literals.containsKey(k.key()) || k.main()) {
                out.put(k.key(), literals.getOrDefault(k.key(), k.libDefault()));
            }
        }
        return out;
    }

    // ---- the work file -------------------------------------------------------------------------------------------

    static Map<String, Mark> marks(Path root) {
        Path p = root.resolve(WORK_FILE);
        Map<String, Mark> out = new HashMap<>();
        if (!Files.exists(p)) {
            return out;
        }
        try {
            JsonNode n = new ObjectMapper(new YAMLFactory()).readTree(Files.readString(p));
            if (n == null) {
                return out;
            }
            // usages first; a candidate's specific / values (#109) count for the usage its "retry: yes" creates, in the
            // same apply, so its settings need no second round
            for (String section : List.of("usages", "candidates")) {
                if (!n.path(section).isObject()) {
                    continue;
                }
                n.get(section).fields().forEachRemaining(e -> {
                    JsonNode v = e.getValue();
                    JsonNode s = v == null ? null : v.get("specific");
                    Boolean specific = s == null || s.isNull() || s.asText().isBlank() ? null : s.asBoolean();
                    Map<String, String> values = new LinkedHashMap<>();
                    if (v != null && v.path("values").isObject()) {
                        v.get("values").fields().forEachRemaining(x -> values.put(x.getKey(), x.getValue().asText()));
                    }
                    if (section.equals("usages") || specific != null || !values.isEmpty()) {
                        out.putIfAbsent(e.getKey(), new Mark(specific, values));
                    }
                });
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out;
    }

    /** The retry usages of the service as the work file lists them. */
    public static List<Usage> usages(Analysis a) {
        String prefix = a.supportPackage + ".";
        List<MethodInfo> methods = a.index.methods().stream().filter(m -> !m.owner().fqn().startsWith(prefix)).toList();
        return sites(a, methods).stream().map(s -> new Usage(s.label(), s.family().label, s.m().file().relPath(), s.m().line(),
                s.block() != null, s.specific())).toList();
    }

    /**
     * Writes .logast/decisions/retry.yaml (#104, #109): every retry decision of the service in one place. {@code usages}:
     * every existing retry, with what the person marked kept and the current state where nothing is marked (a usage
     * that retries inside another retry says so, from NESTED_RETRY). {@code candidates}: every call RETRY_RECOMMENDED
     * recommends retrying ({@code retry: yes} adds the retry on the next apply, {@code no} stops the recommendation),
     * and the ones answered "no" earlier. Written when there is something to list; returns true when created now.
     */
    public static boolean writeWorkFile(Path root, Analysis a, List<dev.logast.orders.WorkOrder> orders) {
        List<Usage> usages = usages(a);
        List<Candidate> candidates = new ArrayList<>();
        Map<String, String> nested = new HashMap<>();
        Set<String> bypassed = new HashSet<>();
        for (dev.logast.orders.WorkOrder o : orders) {
            Object method = o.data() == null ? null : o.data().get("method");
            if (method == null) {
                continue;
            }
            if (o.rule().equals(Rules.RETRY_RECOMMENDED)) {
                Object via = o.data().get("via");
                candidates.add(new Candidate(method.toString(), String.valueOf(o.data().get("caller")),
                        String.valueOf(o.data().get("from")), o.file(), o.line(), via == null ? null : via.toString(),
                        !"no".equals(o.data().get("annotatable"))));
            } else if (o.rule().equals(Rules.NESTED_RETRY)) {
                nested.put(method.toString(), String.valueOf(o.data().get("inner")));
            } else if (o.rule().equals(Rules.RETRY_BYPASSED)) {
                bypassed.add(method.toString());
            }
        }
        Map<String, String> answers = candidateMarks(root);
        Set<String> usageLabels = new HashSet<>();
        usages.forEach(u -> usageLabels.add(u.label()));
        Set<String> listedCandidates = new HashSet<>();
        candidates.forEach(c -> listedCandidates.add(c.method()));
        // answered "no": no longer recommended, still listed so the answer can be changed back
        List<String> declined = answers.entrySet().stream().filter(e -> e.getValue().equals("no") && !listedCandidates.contains(e.getKey())
                && !usageLabels.contains(e.getKey())).map(Map.Entry::getKey).sorted().toList();
        Path p = root.resolve(WORK_FILE);
        if (usages.isEmpty() && candidates.isEmpty() && declined.isEmpty()) {
            return false;
        }
        Map<String, Mark> marks = marks(root);
        StringBuilder sb = new StringBuilder()
                .append("# Optional. Every retry decision of the service. Then run: logast apply .\n")
                .append("# Left blank: a candidate gets no retry; a usage keeps logast's choice (below).\n")
                .append("#\n")
                .append("# usages: the existing retries (@Retryable). By default a usage reads the shared block of its kind in\n")
                .append("# application.yml: resilience.retry.defaults (Spring Framework) or resilience.retry.spring-retry-defaults\n")
                .append("# (Spring Retry). specific: true gives it its own block, resilience.retry.usages.<class>.<method>, starting\n")
                .append("# from the shared values or from values: given here; the next `logast apply` moves it. false points it back to\n")
                .append("# the shared block (its own block stays in application.yml, unused). Empty: logast decides (a usage whose\n")
                .append("# settings differ from the shared ones keeps them in a block of its own).\n")
                .append("#\n")
                .append("# candidates: idempotent HTTP calls nothing retries (GET / HEAD / PUT / DELETE / OPTIONS, no retry on any path to\n")
                .append("# them). retry: yes adds @Retryable (a request: one quick retry; a job or consumer: retries with backoff; the\n")
                .append("# settings then move into application.yml like any usage's); no: not recommended again. Say no when a gateway\n")
                .append("# or service mesh already retries, or the remote side must not see the call twice. specific: and values:\n")
                .append("# work here as under usages and are taken over by the usage yes creates.\n");
        sb.append("usages:").append(usages.isEmpty() ? " {}\n" : "\n");
        Set<String> listed = new HashSet<>();
        for (Usage u : usages) {
            listed.add(u.label());
            Mark mark = marks.get(u.label());
            Boolean specific = mark != null && mark.specific() != null ? mark.specific() : u.managed() ? u.specific() : null;
            sb.append("  ").append(u.label()).append(":    # ").append(u.family()).append(", ").append(u.file()).append(':')
                    .append(u.line()).append('\n');
            if (bypassed.contains(u.label())) {
                sb.append("    # never runs: private / static / final, or only called from its own class, which skips the Spring ")
                        .append("proxy (RETRY_BYPASSED); move it where other beans call\n");
            }
            if (nested.containsKey(u.label())) {
                sb.append("    # retries inside ").append(nested.get(u.label())).append(", which retries too: the attempts multiply ")
                        .append("(NESTED_RETRY); keep one of them\n");
            }
            sb.append("    specific: ").append(specific == null ? "" : specific).append('\n');
            if (mark != null && !mark.values().isEmpty()) {
                sb.append("    values:\n");
                mark.values().forEach((k, v) -> sb.append("      ").append(k).append(": ").append(v).append('\n'));
            }
        }
        Set<String> candidateNames = new HashSet<>(answers.keySet());
        candidates.forEach(c -> candidateNames.add(c.method()));
        List<String> gone = marks.keySet().stream().filter(l -> !listed.contains(l) && !candidateNames.contains(l)).sorted().toList();
        if (!gone.isEmpty()) {
            sb.append("# no longer a retry usage logast manages (kept for reference):\n");
            gone.forEach(l -> sb.append("#  ").append(l).append(": specific ").append(marks.get(l).specific()).append('\n'));
        }
        sb.append("candidates:").append(candidates.isEmpty() && declined.isEmpty() ? " {}\n" : "\n");
        candidates.sort(Comparator.comparing(Candidate::method));
        for (Candidate c : candidates) {
            sb.append("  ").append(c.method()).append(":    # ").append(c.file()).append(':').append(c.line()).append(", reached from ")
                    .append(c.from()).append('\n');
            if (c.via() != null) {
                sb.append("    # makes the call through ").append(c.via()).append(", which it calls on itself: the Spring proxy skips ")
                        .append("that call, so the retry goes here\n");
            }
            if (!c.annotatable()) {
                sb.append("    # @Retryable cannot work on this path (self-invocation, or a private / static / final method): ")
                        .append("retry the call in code (RetryTemplate), or move it into a bean other beans call\n");
                continue;
            }
            sb.append("    retry: \"").append(answers.getOrDefault(c.method(), "")).append("\"    # yes: ")
                    .append(c.caller().equals("request") ? "one quick retry (maxRetries 1, delay 100 ms)"
                            : "retries with backoff (maxRetries 3, delay 1 s, multiplier 2)").append(" · no\n");
            settings(sb, marks.get(c.method()));
        }
        for (String d : declined) {
            sb.append("  ").append(d).append(":\n    retry: \"no\"\n");
            settings(sb, marks.get(d));
        }
        try {
            boolean created = !Files.exists(p);
            if (created || !Files.readString(p).equals(sb.toString())) {
                Files.createDirectories(p.getParent());
                Files.writeString(p, sb.toString());
            }
            return created;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** A candidate's specific / values as the person wrote them: kept until "yes" makes it a usage. */
    private static void settings(StringBuilder sb, Mark mark) {
        if (mark == null) {
            return;
        }
        if (mark.specific() != null) {
            sb.append("    specific: ").append(mark.specific()).append('\n');
        }
        if (!mark.values().isEmpty()) {
            sb.append("    values:\n");
            mark.values().forEach((k, v) -> sb.append("      ").append(k).append(": ").append(v).append('\n'));
        }
    }

    // ---- the rule ------------------------------------------------------------------------------------------------

    void run() {
        List<Site> sites = new ArrayList<>(sites(ctx.a, ctx.methods()));
        sites.removeIf(s -> ctx.ignored(s.m().file(), s.m().line(), Rules.RETRY_SETTINGS_HARDCODED));
        if (sites.isEmpty()) {
            return;
        }
        Map<String, Mark> marks = marks(ctx.a.project.root());
        // the shared block of each family: what application.yml has, else the most common literal settings
        Map<Family, Map<String, String>> shared = new EnumMap<>(Family.class);
        Map<Family, Boolean> sharedExists = new EnumMap<>(Family.class);
        for (Family fam : Family.values()) {
            Map<String, String> inYml = block(fam, fam.shared);
            sharedExists.put(fam, !inYml.isEmpty());
            if (!inYml.isEmpty()) {
                shared.put(fam, inYml);
                continue;
            }
            Map<Map<String, String>, Integer> counts = new LinkedHashMap<>();
            Map<Map<String, String>, Map<String, String>> blockOf = new HashMap<>();
            for (Site s : sites) {
                Mark mark = marks.get(s.label());
                if (s.family() == fam && s.block() == null && (mark == null || !Boolean.TRUE.equals(mark.specific()))) {
                    Map<String, String> eff = effective(fam, s.literals());
                    counts.merge(eff, 1, Integer::sum);
                    blockOf.putIfAbsent(eff, written(fam, s.literals()));
                }
            }
            Map<String, String> best = counts.entrySet().stream().max(Comparator.comparingInt(Map.Entry::getValue))
                    .map(e -> blockOf.get(e.getKey())).orElse(written(fam, Map.of()));
            shared.put(fam, best);
        }

        List<String> changes = new ArrayList<>();
        Map<String, String> yml = new LinkedHashMap<>();   // dotted key -> value, merged without overwriting
        List<Edit> edits = new ArrayList<>();
        Set<SourceFileInfo> needBackoff = new LinkedHashSet<>();
        List<Site> changed = new ArrayList<>();
        Set<Family> sharedUsed = EnumSet.noneOf(Family.class);
        boolean managed = false;
        for (Site s : sites) {
            Family fam = s.family();
            Mark mark = marks.get(s.label());
            Boolean want = mark == null ? null : mark.specific();
            Map<String, String> overrides = new LinkedHashMap<>();
            if (mark != null) {
                mark.values().forEach((k, v) -> {
                    Knob knob = fam.knob(k);
                    if (knob != null) {
                        overrides.put(k, canonFromConfig(knob, v));
                    }
                });
            }
            String target;
            Map<String, String> values;   // canonical values the target block starts with (own blocks only)
            if (s.block() == null) {
                Map<String, String> mine = written(fam, s.literals());
                boolean same = effective(fam, mine).equals(effective(fam, shared.get(fam)));
                if (Boolean.TRUE.equals(want) || want == null && !same) {
                    target = s.own();
                    values = new LinkedHashMap<>(mine);
                    values.putAll(overrides);
                    changes.add(s.label() + " → " + PREFIX + target + (Boolean.TRUE.equals(want) ? " (marked specific in " + WORK_FILE + ")"
                            : " (its settings differ from the shared " + PREFIX + fam.shared + ": kept as they are)"));
                } else {
                    target = fam.shared;
                    values = null;
                    changes.add(s.label() + " → " + PREFIX + fam.shared + (same ? "" : " (marked not specific in " + WORK_FILE
                            + ": its own settings " + describe(fam, mine) + " give way to the shared ones)"));
                }
            } else if (!s.specific() && Boolean.TRUE.equals(want)) {
                target = s.own();
                values = new LinkedHashMap<>(shared.get(fam));
                values.putAll(overrides);
                changes.add(s.label() + " → " + PREFIX + target + " (marked specific in " + WORK_FILE + ")");
            } else if (s.specific() && Boolean.FALSE.equals(want)) {
                target = fam.shared;
                values = null;
                changes.add(s.label() + " → " + PREFIX + fam.shared + " (marked not specific in " + WORK_FILE + "; "
                        + PREFIX + s.block() + " stays in the configuration, unused)");
            } else {
                managed = true;
                if (!s.specific()) {
                    sharedUsed.add(fam);
                }
                continue;
            }
            managed = true;
            Set<String> keys = new LinkedHashSet<>();
            if (values == null) {
                sharedUsed.add(fam);
                keys.addAll(shared.get(fam).keySet());
            } else {
                Map<String, String> existing = block(fam, target);
                for (Knob k : fam.knobs) {
                    if (values.containsKey(k.key()) && !existing.containsKey(k.key())) {
                        yml.put(PREFIX + target + "." + k.key(), fam.yml(k, values.get(k.key())));
                    }
                    if (values.containsKey(k.key()) || existing.containsKey(k.key())) {
                        keys.add(k.key());
                    }
                }
            }
            SourceFileInfo f = s.m().file();
            String text = f.text();
            int start = Pos.start(s.a());
            int end = Pos.end(s.a());
            String rewritten = rewrite(text, s, target, keys);
            if (!rewritten.equals(text.substring(start, end))) {
                edits.add(Edit.replace(f.relPath(), start, end, rewritten));
                changed.add(s);
                if (s.family() == Family.SPRING_RETRY && s.backoff() == null) {
                    needBackoff.add(f);
                }
            }
        }
        Map<String, String> sharedYml = new LinkedHashMap<>();   // the shared blocks first, then the usages' own
        for (Family fam : sharedUsed) {
            if (!sharedExists.get(fam)) {
                for (Knob k : fam.knobs) {
                    String v = shared.get(fam).get(k.key());
                    if (v != null) {
                        sharedYml.put(PREFIX + fam.shared + "." + k.key(), fam.yml(k, v));
                    }
                }
            }
        }
        sharedYml.putAll(yml);
        yml = sharedYml;
        if (changed.isEmpty() && yml.isEmpty() && !managed) {
            return;
        }
        ClassInfo app = ctx.a.index.classes().stream()
                .filter(c -> c.decl().getLeadingAnnotations().stream().anyMatch(a -> Annotations.is(a, ResilienceRules.SPRING_BOOT_APPLICATION)))
                .min(Comparator.comparing(c -> c.file().relPath())).orElse(null);
        List<Edit> setup = app == null ? List.of() : settingsClass(app);
        if (changed.isEmpty() && yml.isEmpty() && (setup == null || setup.isEmpty())) {
            return;
        }
        Site first = changed.isEmpty() ? sites.getFirst() : changed.getFirst();
        OrderBuilder b = ctx.order(Rules.RETRY_SETTINGS_HARDCODED, "retry-settings").at(first.m().file().relPath(), first.m().line())
                .title("Retry settings written into @Retryable (" + String.join(", ", changed.stream().map(Site::label).toList())
                        + "): changing attempts or delays needs a rebuild and a release")
                .doneWhen("every @Retryable reads its settings from " + PREFIX + "* (the String / expression attributes), "
                        + "as " + WORK_FILE + " marks it");
        changed.stream().skip(1).forEach(s -> b.location(s.m().file().relPath(), s.m().line(), Rules.RETRY_SETTINGS_HARDCODED));
        int[] boot = SetupRules.springBootVersion(ctx);
        if (app == null || setup == null || boot[0] > 0 && boot[0] < 3) {
            b.suggestion("Move the settings into application.yml (" + PREFIX + "*) and reference them from the annotation "
                    + "(maxRetriesString = \"${…}\", or maxAttemptsExpression = \"#{${…}}\" for Spring Retry). Not applied "
                    + "automatically: " + (app == null ? "no @SpringBootApplication class found."
                    : setup == null ? "a class named " + SETTINGS_CLASS + " already exists next to it."
                    : "Spring Boot " + boot[0] + " cannot bind the generated record."));
            return;
        }
        b.suggestion("Moves them into application.yml, bound by " + SETTINGS_CLASS + " (@ConfigurationProperties, typed and "
                + "documented); the annotations read them through placeholders, so a changed value takes effect on restart. "
                + String.join("; ", changes) + ". Mark usages that need settings of their own in " + WORK_FILE + ".");
        edits.forEach(b::edit);
        needBackoff.forEach(f -> b.edits(JavaEdits.imports(f, ResilienceRules.SPRING_RETRY_PKG + "Backoff")));
        setup.forEach(b::edit);
        if (!yml.isEmpty()) {
            ServiceYaml.merge(ctx, b, nested(yml));
        }
    }

    private static String describe(Family fam, Map<String, String> values) {
        List<String> out = new ArrayList<>();
        values.forEach((k, v) -> out.add(k + " " + fam.yml(fam.knob(k), v)));
        return String.join(", ", out);
    }

    /** The settings application.yml has under {@code resilience.retry.<block>}, canonical. */
    private Map<String, String> block(Family fam, String block) {
        Map<String, String> out = new LinkedHashMap<>();
        String prefix = PREFIX + block + ".";
        for (Knob k : fam.knobs) {
            String v = ctx.a.springProperties.get(prefix + k.key());
            if (v != null) {
                out.put(k.key(), canonFromConfig(k, v));
            }
        }
        return out;
    }

    /** The annotation with its settings referencing {@code block}: other attributes kept as written. */
    private static String rewrite(String text, Site s, String block, Set<String> keys) {
        Family fam = s.family();
        List<String> kept = new ArrayList<>();
        for (Expression arg : args(s.a())) {
            String n = name(arg);
            if (fam == Family.SPRING_RETRY && n.equals("backoff")) {
                continue;
            }
            if (fam.knobs.stream().noneMatch(k -> !k.backoff() && (k.attrs().contains(n) || k.twin().equals(n)))) {
                String src = text.substring(Pos.start(arg), Pos.end(arg));
                kept.add(arg instanceof J.Assignment ? src : "value = " + src);
            }
        }
        for (Knob k : fam.knobs) {
            if (!k.backoff() && keys.contains(k.key())) {
                kept.add(k.twin() + " = \"" + fam.ref(block, k.key()) + "\"");
            }
        }
        if (fam == Family.SPRING_RETRY) {
            List<String> inner = new ArrayList<>();
            if (s.backoff() != null) {
                for (Expression arg : args(s.backoff())) {
                    String n = name(arg);
                    if (fam.knobs.stream().noneMatch(k -> k.backoff() && (k.attrs().contains(n) || k.twin().equals(n)))) {
                        inner.add(text.substring(Pos.start(arg), Pos.end(arg)));
                    }
                }
            }
            for (Knob k : fam.knobs) {
                if (k.backoff() && keys.contains(k.key())) {
                    inner.add(k.twin() + " = \"" + fam.ref(block, k.key()) + "\"");
                }
            }
            if (!inner.isEmpty()) {
                String type = s.backoff() != null ? s.backoff().getAnnotationType().printTrimmed() : "Backoff";
                kept.add("backoff = @" + type + "(" + String.join(", ", inner) + ")");
            }
        }
        return "@" + s.a().getAnnotationType().printTrimmed() + (kept.isEmpty() ? "" : "(" + String.join(", ", kept) + ")");
    }

    /** Dotted keys (in order) as nested YAML. */
    static String nested(Map<String, String> flat) {
        StringBuilder sb = new StringBuilder();
        List<String> previous = List.of();
        for (Map.Entry<String, String> e : flat.entrySet()) {
            List<String> parts = List.of(e.getKey().split("\\."));
            int same = 0;
            while (same < previous.size() - 1 && same < parts.size() - 1 && previous.get(same).equals(parts.get(same))) {
                same++;
            }
            for (int i = same; i < parts.size(); i++) {
                sb.append("  ".repeat(i)).append(parts.get(i)).append(':');
                sb.append(i == parts.size() - 1 ? " " + e.getValue() + "\n" : "\n");
            }
            previous = parts;
        }
        return sb.toString();
    }

    /**
     * The generated settings class and its registration on the application class: nothing when both are there, null
     * when a class of that name exists that logast did not write.
     */
    private List<Edit> settingsClass(ClassInfo app) {
        List<Edit> out = new ArrayList<>();
        String pkg = app.fqn().contains(".") ? app.fqn().substring(0, app.fqn().lastIndexOf('.')) : "";
        String dir = app.file().relPath().contains("/") ? app.file().relPath().substring(0, app.file().relPath().lastIndexOf('/') + 1) : "";
        String path = dir + SETTINGS_CLASS + ".java";
        ClassInfo existing = ctx.a.index.clazz(pkg.isEmpty() ? SETTINGS_CLASS : pkg + "." + SETTINGS_CLASS);
        if (existing != null || Files.exists(ctx.a.project.root().resolve(path))) {
            String src = existing != null ? existing.file().text() : SetupRules.read(ctx.a.project.root().resolve(path));
            if (!src.contains("@ConfigurationProperties(\"resilience.retry\")")) {
                return null;
            }
        } else {
            out.add(Edit.create(path, source(pkg)));
        }
        boolean registered = app.decl().getLeadingAnnotations().stream().anyMatch(a -> Annotations.is(a, SCAN_PROPS))
                || ctx.a.index.classes().stream().flatMap(c -> c.decl().getLeadingAnnotations().stream())
                .anyMatch(a -> Annotations.is(a, ENABLE_PROPS) && a.printTrimmed().contains(SETTINGS_CLASS + ".class"));
        if (registered) {
            return out;
        }
        SourceFileInfo f = app.file();
        J.Annotation enable = app.decl().getLeadingAnnotations().stream().filter(a -> Annotations.is(a, ENABLE_PROPS)).findFirst().orElse(null);
        if (enable == null) {
            J.Annotation boot = app.decl().getLeadingAnnotations().stream()
                    .filter(a -> Annotations.is(a, ResilienceRules.SPRING_BOOT_APPLICATION)).findFirst().orElseThrow();
            int at = Pos.start(boot);
            out.addAll(JavaEdits.imports(f, ENABLE_PROPS));
            out.add(Edit.insert(f.relPath(), at, "@EnableConfigurationProperties(" + SETTINGS_CLASS + ".class)\n" + Text.indentAt(f.text(), at)));
            return out;
        }
        List<Expression> args = args(enable);
        if (args.isEmpty()) {
            out.add(Edit.replace(f.relPath(), Pos.start(enable), Pos.end(enable),
                    "@" + enable.getAnnotationType().printTrimmed() + "(" + SETTINGS_CLASS + ".class)"));
        } else {
            Expression v = value(args.getFirst());
            if (v instanceof J.NewArray arr) {
                boolean empty = arr.getInitializer() == null || arr.getInitializer().stream().allMatch(e -> e instanceof J.Empty);
                out.add(Edit.insert(f.relPath(), Pos.end(arr) - 1, (empty ? "" : ", ") + SETTINGS_CLASS + ".class"));
            } else {
                out.add(Edit.replace(f.relPath(), Pos.start(v), Pos.end(v),
                        "{" + f.text().substring(Pos.start(v), Pos.end(v)) + ", " + SETTINGS_CLASS + ".class}"));
            }
        }
        return out;
    }

    private static String source(String pkg) {
        return (pkg.isEmpty() ? "" : "package " + pkg + ";\n\n") + """
                import java.time.Duration;
                import java.util.Map;
                import org.springframework.boot.context.properties.ConfigurationProperties;

                /**
                 * Retry settings of the service's @Retryable methods (application.yml, resilience.retry.*). The annotations read
                 * the same keys through placeholders, so a changed value takes effect on restart, without a rebuild; this class
                 * makes them typed and lists them in one place.
                 *
                 * @param defaults            what Spring Framework's @Retryable usages share
                 * @param springRetryDefaults what Spring Retry's @Retryable usages share
                 * @param usages              usages with settings of their own: class, then method (kebab case); which ones is
                 *                            decided in .logast/decisions/retry.yaml
                 */
                @ConfigurationProperties("resilience.retry")
                public record RetrySettings(Retry defaults, Retry springRetryDefaults, Map<String, Map<String, Retry>> usages) {
                    /**
                     * One block of settings.
                     *
                     * @param maxRetries  Spring Framework: retries after the first call
                     * @param maxAttempts Spring Retry: calls in all, the first one included
                     * @param delay       the delay before the first retry (Spring Retry: plain milliseconds)
                     * @param jitter      Spring Framework: a random part added to or taken from each delay
                     * @param multiplier  each delay is the previous one times this
                     * @param maxDelay    the longest delay (Spring Retry: plain milliseconds)
                     * @param timeout     Spring Framework: the longest time all attempts together may take
                     */
                    public record Retry(Long maxRetries, Integer maxAttempts, Duration delay, Duration jitter, Double multiplier,
                                        Duration maxDelay, Duration timeout) {
                    }
                }
                """;
    }
}
