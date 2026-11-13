package dev.logast;

import dev.logast.policy.KeyStyle;
import dev.logast.policy.Policy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Collects a first-draft vocabulary from what the code already says: MDC keys, {@code [k=v]} / {@code k={}} keys in
 * log messages and id-like variable names ({@code orderId}, {@code tableUuid}). Variants of one identifier
 * ({@code orderId, order_id, order.id}) become one term with a canonical key and aliases, so the user only reorganises.
 * Text-based on purpose: it needs no classpath and runs in a second.
 */
public final class VocabularyHarvest {
    private static final Pattern MDC_CALL = Pattern.compile(
            "\\b(?:MDC\\s*\\.\\s*(?:put|putCloseable|get|remove)|MdcScope\\s*\\.\\s*of)\\s*\\(\\s*\"([A-Za-z][\\w.\\-]*)\"");
    private static final Pattern MDC_SCOPE_PAIR = Pattern.compile(",\\s*\"([A-Za-z][\\w.\\-]*)\"\\s*,");
    private static final Pattern LOG_CALL = Pattern.compile("\\.(?:trace|debug|info|warn|error)\\s*\\(");
    /** {@code k={}}, {@code k: {}}, and {@code k=} closing a literal that is concatenated with the value. */
    private static final Pattern LOG_KEY = Pattern.compile("(?<![\\w.\\-])([A-Za-z][\\w.\\-]*)(?:\\s*=\\s*(?:\\{}|$)|:\\s*\\{})");
    private static final Pattern DECLARED = Pattern.compile(
            "\\b(?:String|UUID|java\\.util\\.UUID|Long|long|Integer|int)\\s+([a-z][A-Za-z0-9]*(?:Id|Uuid|ID|UUID))\\b(?!\\s*\\()");   // not getOrderId() / countByVendorId()
    /** {@code orderId, order_id, order.uuid, ORDER_ID}: the suffix must start a new word, so "paid" or "valid" are not ids. */
    static final Pattern ID_SUFFIX = Pattern.compile(
            "(?:[._\\-](?i:id|uuid|number|no)|(?<=[a-z0-9])(?:Id|ID|Uuid|UUID|Number|No))$");
    private static final Set<String> AMBIENT = Set.of("traceid", "spanid", "trace.id", "span.id", "traceparent", "tracestate");
    private static final Set<String> SKIP_DIRS = Set.of("build", "out", "target", ".gradle", ".git", ".logast", "node_modules", "test");
    private static final Set<String> NOT_A_KEY = Set.of("id", "uuid", "key", "value", "name", "type", "e", "ex", "error",
            "http", "https", "url", "uri", "path");

    /** One spelling of a key and where it was seen. */
    public static final class Usage {
        public int mdc, log, declared;
        public final Set<String> files = new TreeSet<>();

        public int total() {
            return mdc + log + declared;
        }
    }

    /** A business term: its canonical MDC key and the other spellings found. */
    public record Term(String name, String key, List<String> aliases, List<String> params, int mdc, int log, int declared,
                       int files, String similarTo, List<String> roles) {
        boolean used() {
            return mdc > 0 || log > 0;
        }
    }

    /** A {@code [k=v]} key that is a value, not an identifier: shown so inconsistent spellings can be merged. */
    public record ValueKey(String dotted, Map<String, Integer> spellings) {
    }

    /**
     * @param terms     identifiers the code logs or puts in MDC: the draft vocabulary
     * @param unused    identifiers only declared (never logged, never in MDC), for the user to pick from
     */
    public record Result(List<Term> terms, List<Term> unused, List<ValueKey> valueKeys, int filesScanned, KeyStyle style) {
    }

    private VocabularyHarvest() {
    }

    /**
     * @param known      merged policy (company layers): spellings it already maps are left out
     * @param minDeclared declared-only identifiers (never logged, never in MDC) need this many declarations
     */
    public static Result run(Path root, Policy known, int minDeclared) {
        return run(root, known, known == null ? KeyStyle.CAMEL : known.logging().style(), minDeclared);
    }

    /** @param style spelling of the canonical keys ({@code logging.keyStyle}) */
    public static Result run(Path root, Policy known, KeyStyle style, int minDeclared) {
        Map<String, Usage> usages = new TreeMap<>();
        List<Path> files = javaFiles(root);
        for (Path p : files) {
            String rel = root.relativize(p).toString().replace('\\', '/');
            String text = read(p);
            Matcher m = MDC_CALL.matcher(text);
            while (m.find()) {
                use(usages, m.group(1), rel).mdc++;
                if (text.startsWith("MdcScope", m.start())) {
                    Matcher pair = MDC_SCOPE_PAIR.matcher(callText(text, text.indexOf('(', m.start()) + 1));
                    while (pair.find()) {
                        use(usages, pair.group(1), rel).mdc++;
                    }
                }
            }
            Matcher lc = LOG_CALL.matcher(text);
            while (lc.find()) {
                for (String literal : literals(callText(text, lc.end()))) {
                    Matcher k = LOG_KEY.matcher(literal);
                    while (k.find()) {
                        use(usages, k.group(1), rel).log++;
                    }
                }
            }
            Matcher d = DECLARED.matcher(text);
            while (d.find()) {
                use(usages, d.group(1), rel).declared++;
            }
        }
        usages.keySet().removeIf(k -> AMBIENT.contains(k.toLowerCase(Locale.ROOT)) || NOT_A_KEY.contains(k.toLowerCase(Locale.ROOT)));

        Set<String> knownSpellings = new HashSet<>();
        Set<String> knownTerms = new HashSet<>();
        if (known != null) {
            knownSpellings.addAll(known.mdcAliases().keySet());
            knownSpellings.addAll(known.mdcRegistry().keySet());
            knownTerms.addAll(known.terms());
        }

        Map<String, Map<String, Usage>> byTerm = new TreeMap<>();
        Map<String, Map<String, Integer>> values = new TreeMap<>();
        usages.forEach((key, u) -> {
            boolean identifier = u.mdc > 0 || ID_SUFFIX.matcher(key).find();
            if (identifier) {
                byTerm.computeIfAbsent(term(key), t -> new TreeMap<>()).put(key, u);
            } else if (u.log > 0) {
                values.computeIfAbsent(dotted(key), t -> new TreeMap<>(String.CASE_INSENSITIVE_ORDER)).merge(key, u.log, Integer::sum);
            }
        });

        List<Term> terms = new ArrayList<>();
        byTerm.forEach((name, spellings) -> {
            int mdc = sum(spellings, x -> x.mdc), log = sum(spellings, x -> x.log), declared = sum(spellings, x -> x.declared);
            if (mdc == 0 && log == 0 && declared < minDeclared) {
                return;
            }
            boolean idLike = spellings.keySet().stream().anyMatch(k -> ID_SUFFIX.matcher(k).find());
            KeyStyle ks = style == KeyStyle.KEEP ? KeyStyle.CAMEL : style;
            // the most used spelling, in the configured style ("order.id" → "orderId" for camel)
            String top = spellings.keySet().stream().max(Comparator.comparingInt(k -> spellings.get(k).total())).orElseThrow();
            String key = idLike && !ID_SUFFIX.matcher(top).find() ? ks.apply(name + ".id")
                    : style == KeyStyle.KEEP ? top : ks.apply(top.matches("(?i).*uuid$") ? name + ".id" : top);
            List<String> aliases = spellings.keySet().stream().filter(k -> !k.equals(key) && !knownSpellings.contains(k))
                    .sorted(Comparator.comparingInt((String k) -> -spellings.get(k).total()).thenComparing(k -> k)).toList();
            if (knownSpellings.contains(key) && aliases.isEmpty() || knownTerms.contains(name) && aliases.isEmpty()) {
                return;
            }
            List<String> params = spellings.entrySet().stream()
                    .filter(e -> e.getKey().matches("[a-z][A-Za-z0-9]*") && e.getValue().declared > 0)
                    .map(Map.Entry::getKey).toList();
            Set<String> inFiles = new TreeSet<>();
            spellings.values().forEach(x -> inFiles.addAll(x.files));
            terms.add(new Term(name, key, aliases, params, mdc, log, declared, inFiles.size(), null, List.of()));
        });

        // compounds: "opened.by.user" is a role of "user" (another field, not an alias); "orders" is probably "order"
        Set<String> names = terms.stream().map(Term::name).collect(Collectors.toSet());
        names.addAll(knownTerms);
        Map<String, List<String>> rolesOf = new TreeMap<>();
        List<Term> kept = new ArrayList<>();
        for (Term t : terms) {
            String base = baseTerm(t.name(), names);
            String singular = t.name().endsWith("s") ? t.name().substring(0, t.name().length() - 1) : null;
            if (base != null && t.mdc() == 0 && t.log() < 2) {
                rolesOf.computeIfAbsent(base, b -> new ArrayList<>()).addAll(t.aliases().isEmpty() ? List.of(t.key()) : t.aliases());
                continue;
            }
            kept.add(new Term(t.name(), t.key(), t.aliases(), t.params(), t.mdc(), t.log(), t.declared(), t.files(),
                    base != null ? base : singular != null && names.contains(singular) ? singular : null, List.of()));
        }
        List<Term> withRoles = kept.stream().map(t -> new Term(t.name(), t.key(), t.aliases(), t.params(), t.mdc(), t.log(),
                t.declared(), t.files(), t.similarTo(), rolesOf.getOrDefault(t.name(), List.of()).stream().sorted().toList())).toList();
        Comparator<Term> weight = Comparator.comparingInt((Term t) -> -(t.mdc() * 10 + t.log() * 3 + t.declared())).thenComparing(Term::name);
        List<Term> used = withRoles.stream().filter(Term::used).sorted(weight).toList();
        List<Term> unused = withRoles.stream().filter(t -> !t.used()).sorted(weight).toList();

        List<ValueKey> valueKeys = values.entrySet().stream().map(e -> new ValueKey(e.getKey(), e.getValue())).toList();
        return new Result(used, unused, valueKeys, files.size(), style);
    }

    /**
     * The key style the code already uses most: counted over MDC keys and [k=v] keys with more than one word
     * ({@code orderId} camel, {@code order.id} dotted, {@code order_id} snake, {@code OrderId} pascal). Null when undecided.
     */
    public static KeyStyle detectStyle(Path root) {
        Map<KeyStyle, Integer> votes = new EnumMap<>(KeyStyle.class);
        for (Path p : javaFiles(root)) {
            String text = read(p);
            List<String> keys = new ArrayList<>();
            Matcher m = MDC_CALL.matcher(text);
            while (m.find()) {
                keys.add(m.group(1));
            }
            Matcher lc = LOG_CALL.matcher(text);
            while (lc.find()) {
                for (String literal : literals(callText(text, lc.end()))) {
                    Matcher k = LOG_KEY.matcher(literal);
                    while (k.find()) {
                        keys.add(k.group(1));
                    }
                }
            }
            for (String k : keys) {
                KeyStyle s = k.contains(".") ? KeyStyle.DOTTED : k.contains("_") ? KeyStyle.SNAKE
                        : k.matches("[a-z][a-z0-9]*[A-Z].*") ? KeyStyle.CAMEL : k.matches("[A-Z][a-z0-9]+[A-Z].*") ? KeyStyle.PASCAL : null;
                if (s != null && !AMBIENT.contains(k.toLowerCase(Locale.ROOT))) {
                    votes.merge(s, 1, Integer::sum);
                }
            }
        }
        return votes.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
    }

    /** The {@code vocabulary:} block (plus commented value keys), ready to paste into or append to a policy. */
    public static String yaml(Result r) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Harvested from ").append(r.filesScanned()).append(" source files: MDC keys, [k=v] keys in log messages and")
                .append(" id-like variables.\n# Counts: mdc / log / declared, files. Rename terms, merge duplicates (move a spelling into")
                .append(" `mdc.aliases`),\n# drop what is not a business concept, and add banned words to `aliases`.\n");
        if (r.terms().isEmpty()) {
            sb.append("# (nothing found)\n");
            return sb.toString();
        }
        sb.append("vocabulary:\n");
        int width = r.terms().stream().mapToInt(t -> yamlKey(t.name()).length()).max().orElse(10) + 4;
        for (Term t : r.terms()) {
            String head = "  " + yamlKey(t.name()) + ":";
            sb.append(head).append(" ".repeat(Math.max(1, width - head.length())))
                    .append("# ").append(t.mdc()).append(" / ").append(t.log()).append(" / ").append(t.declared())
                    .append(", ").append(t.files()).append(t.files() == 1 ? " file" : " files");
            if (t.similarTo() != null) {
                sb.append(t.name().contains(".") ? "  <- a kind of \"" : "  <- same as \"").append(t.similarTo()).append("\"?");
            }
            if (!t.roles().isEmpty()) {
                sb.append("\n    # roles in the code (other fields, not aliases): ")
                        .append(String.join(", ", t.roles().stream().limit(12).toList()))
                        .append(t.roles().size() > 12 ? ", +" + (t.roles().size() - 12) : "");
            }
            sb.append("\n    mdc: { key: ").append(t.key());
            if (!t.aliases().isEmpty()) {
                sb.append(", aliases: [").append(String.join(", ", t.aliases())).append("]");
            }
            if (!t.params().isEmpty()) {
                sb.append(", sources: { params: [").append(String.join(", ", t.params())).append("] }");
            }
            sb.append(" }\n");
        }
        if (!r.unused().isEmpty()) {
            sb.append("\n# Identifiers declared in the code that no log or MDC uses yet (declarations); add the ones that matter:\n");
            r.unused().stream().limit(60).forEach(t -> sb.append("#   ").append(t.name()).append(": ")
                    .append(String.join(", ", t.aliases().isEmpty() ? List.of(t.key()) : t.aliases()))
                    .append(" (").append(t.declared()).append(")")
                    .append(t.roles().isEmpty() ? "" : "; roles: " + String.join(", ", t.roles().stream().limit(6).toList())
                            + (t.roles().size() > 6 ? ", +" + (t.roles().size() - 6) : ""))
                    .append('\n'));
            if (r.unused().size() > 60) {
                sb.append("#   … ").append(r.unused().size() - 60).append(" more (`logast vocabulary --min-declared` raises the bar)\n");
            }
        }
        List<ValueKey> mixed = r.valueKeys().stream().filter(v -> v.spellings().size() > 1).toList();
        if (!mixed.isEmpty()) {
            sb.append("\n# Value keys written in more than one way (logast writes them in `logging.keyStyle`: ")
                    .append(r.style()).append("):\n");
            mixed.stream().limit(40).forEach(v -> sb.append("#   ").append(v.dotted()).append(" <- ")
                    .append(v.spellings().entrySet().stream().map(e -> e.getKey() + " (" + e.getValue() + ")")
                            .collect(Collectors.joining(", "))).append('\n'));
        }
        return sb.toString();
    }

    /** {@code orderId, order_id, order.id, orderUuid → order}; {@code job.name → job}; {@code activeShiftId → active.shift}. */
    public static String term(String key) {
        if (key.contains(".")) {
            return key.substring(0, key.indexOf('.')).toLowerCase(Locale.ROOT);
        }
        String base = ID_SUFFIX.matcher(key).replaceFirst("");
        return dotted(base.isEmpty() ? key : base);
    }

    static String dotted(String key) {
        return key.replaceAll("([a-z0-9])([A-Z])", "$1.$2").replaceAll("[_\\-]+", ".").toLowerCase(Locale.ROOT);
    }

    /** "opened.by.user" → "user", "order.item" → "item" when that is a term; longest known suffix wins. */
    private static String baseTerm(String name, Set<String> names) {
        String[] parts = name.split("\\.");
        for (int i = 1; i < parts.length; i++) {
            String suffix = String.join(".", Arrays.copyOfRange(parts, i, parts.length));
            if (names.contains(suffix)) {
                return suffix;
            }
        }
        return null;
    }

    private static String yamlKey(String term) {
        return term.matches("[a-z][a-z0-9_]*") ? term : "\"" + term + "\"";
    }

    private static int sum(Map<String, Usage> m, java.util.function.ToIntFunction<Usage> f) {
        return m.values().stream().mapToInt(f).sum();
    }

    private static Usage use(Map<String, Usage> usages, String key, String file) {
        Usage u = usages.computeIfAbsent(key, k -> new Usage());
        u.files.add(file);
        return u;
    }

    /** Source text of a call's arguments: from {@code start} (just after the opening parenthesis) to its match. */
    static String callText(String text, int start) {
        int depth = 1;
        for (int i = start; i < text.length() && i < start + 4000; i++) {
            char c = text.charAt(i);
            if (c == '"') {
                i = skipString(text, i);
            } else if (c == '\'') {
                i = text.indexOf('\'', i + (text.charAt(i + 1) == '\\' ? 3 : 2));
                if (i < 0) {
                    break;
                }
            } else if (c == '(') {
                depth++;
            } else if (c == ')' && --depth == 0) {
                return text.substring(start, i);
            }
        }
        return text.substring(start, Math.min(text.length(), start + 4000));
    }

    /** String literal contents in order (text blocks skipped). */
    static List<String> literals(String code) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < code.length(); i++) {
            if (code.charAt(i) == '"') {
                int end = skipString(code, i);
                if (!code.startsWith("\"\"\"", i)) {
                    out.add(code.substring(i + 1, Math.max(i + 1, end)));
                }
                i = end;
            }
        }
        return out;
    }

    private static int skipString(String s, int open) {
        if (s.startsWith("\"\"\"", open)) {
            int end = s.indexOf("\"\"\"", open + 3);
            return end < 0 ? s.length() : end + 2;
        }
        for (int i = open + 1; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\') {
                i++;
            } else if (c == '"' || c == '\n') {
                return i;
            }
        }
        return s.length();
    }

    private static List<Path> javaFiles(Path root) {
        try (Stream<Path> s = Files.walk(root)) {
            return s.filter(p -> p.toString().endsWith(".java")).filter(p -> {
                Path rel = root.relativize(p);
                for (Path seg : rel) {
                    if (SKIP_DIRS.contains(seg.toString())) {
                        return false;
                    }
                }
                return rel.toString().replace('\\', '/').contains("src/");
            }).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String read(Path p) {
        try {
            return Files.readString(p);
        } catch (IOException e) {
            return "";
        }
    }
}
