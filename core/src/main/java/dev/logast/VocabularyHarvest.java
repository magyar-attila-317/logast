package dev.logast;

import dev.logast.policy.KeyStyle;
import dev.logast.policy.Policy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.ToIntFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Collects a first-draft vocabulary from what the code already says: MDC keys, {@code [k=v]} / {@code k={}} keys in
 * log messages and id-like variable names ({@code orderId}, {@code tableUuid}). It only collects: every key becomes a term of
 * its own, spelled as in the code. Merging spellings, renaming terms to business words and dropping technical keys is
 * the user's call; the output says which keys look alike, in comments, and shows how with a short example (#86).
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
    /** Plain scalars YAML reads as booleans or null. */
    private static final Set<String> YAML_WORDS = Set.of("y", "n", "yes", "no", "on", "off", "true", "false", "null");
    private static final String VOCABULARY_GUIDE = "https://github.com/magyar-attila-317/logast/blob/main/docs/vocabulary.md";

    /** One spelling of a key and where it was seen. */
    public static final class Usage {
        public int mdc, log, declared;
        public final Set<String> files = new TreeSet<>();

        public int total() {
            return mdc + log + declared;
        }
    }

    /**
     * One key exactly as the code writes it; it becomes a term of the same name. Organising is left to the user:
     * {@code sameAs} and {@code kindOf} are only hints, written as comments.
     *
     * @param sameAs other keys that spell the same identifier ({@code orderId, order_id, orderUuid}); an id and a
     *               number ({@code supplierId, supplierNumber}) never do
     * @param kindOf a key this one is a role of ({@code openedByUserId} → {@code userId}), or null
     */
    public record Term(String key, List<String> params, int mdc, int log, int declared, int files, List<String> sameAs,
                       String kindOf) {
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
     * @param known      merged policy (company layers): keys and terms it already has are left out
     * @param minDeclared declared-only identifiers (never logged, never in MDC) need this many declarations
     */
    public static Result run(Path root, Policy known, int minDeclared) {
        return run(root, known, known == null ? KeyStyle.CAMEL : known.logging().style(), minDeclared);
    }

    /** @param style {@code logging.keyStyle}, named in the comment on value keys spelled more than one way */
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

        Set<String> knownKeys = new TreeSet<>();
        Set<String> knownSpellings = new HashSet<>();
        Set<String> knownTerms = new HashSet<>();
        if (known != null) {
            knownKeys.addAll(known.mdcRegistry().keySet());
            knownSpellings.addAll(known.mdcAliases().keySet());
            knownSpellings.addAll(knownKeys);
            knownTerms.addAll(known.terms());
        }

        // one term per key, as written: nothing is grouped, renamed or restyled
        Map<String, Usage> ids = new TreeMap<>();
        Map<String, Map<String, Integer>> values = new TreeMap<>();
        usages.forEach((key, u) -> {
            boolean identifier = u.mdc > 0 || ID_SUFFIX.matcher(key).find();
            if (identifier) {
                if (!knownSpellings.contains(key) && !knownTerms.contains(key) && (u.mdc > 0 || u.log > 0 || u.declared >= minDeclared)) {
                    ids.put(key, u);
                }
            } else if (u.log > 0) {
                values.computeIfAbsent(dotted(key), t -> new TreeMap<>(String.CASE_INSENSITIVE_ORDER)).merge(key, u.log, Integer::sum);
            }
        });

        // hints only: spellings of one identifier, and roles ("openedByUserId" is a kind of "userId"); the policy's keys count too
        ToIntFunction<String> weight = k -> ids.containsKey(k) ? ids.get(k).mdc * 10 + ids.get(k).log * 3 + ids.get(k).declared : -1;
        Comparator<String> byWeight = Comparator.comparingInt((String k) -> -weight.applyAsInt(k)).thenComparing(k -> k);
        Map<String, List<String>> byIdentity = new HashMap<>();
        Stream.concat(ids.keySet().stream(), knownKeys.stream()).distinct()
                .forEach(k -> byIdentity.computeIfAbsent(identity(k), i -> new ArrayList<>()).add(k));
        byIdentity.values().forEach(l -> l.sort(byWeight));

        List<Term> all = new ArrayList<>();
        ids.forEach((key, u) -> {
            String identity = identity(key);
            String kind = identity.substring(identity.indexOf('#'));
            List<String> sameAs = byIdentity.get(identity).stream().filter(k -> !k.equals(key)).toList();
            String kindOf = null;
            String[] words = identity.substring(0, identity.indexOf('#')).split("\\.");
            for (int i = 1; i < words.length && kindOf == null; i++) {      // the longest known ending wins
                List<String> base = byIdentity.get(String.join(".", Arrays.copyOfRange(words, i, words.length)) + kind);
                kindOf = base == null ? null : base.getFirst();
            }
            List<String> params = key.matches("[a-z][A-Za-z0-9]*") && u.declared > 0 ? List.of(key) : List.of();
            all.add(new Term(key, params, u.mdc, u.log, u.declared, u.files.size(), sameAs, kindOf));
        });
        all.sort(Comparator.comparing(Term::key, byWeight));
        List<Term> used = all.stream().filter(Term::used).toList();
        List<Term> unused = all.stream().filter(t -> !t.used()).toList();

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

    /** The {@code vocabulary:} block (plus commented hints), ready to paste into or append to a policy. */
    public static String yaml(Result r) {
        StringBuilder sb = new StringBuilder();
        sb.append("# Harvested from ").append(r.filesScanned()).append(" source files: MDC keys, [k=v] keys in log messages and")
                .append(" id-like variables,\n# one term per key, written as in the code. Counts: mdc / log / declared, files.")
                .append(" \"same value as\" and\n# \"a kind of\" are only hints: nothing is merged or renamed until you edit it.\n");
        if (r.terms().isEmpty()) {
            sb.append("# (nothing found)\n");
            return sb.toString();
        }
        sb.append("""
                #
                # How to organise it (%s):
                # a term is the business word, its mdc.key the key every log line carries.
                #   order:                             # renamed from orderId: name the term with the business word
                #     aliases: [purchase]              # banned words: "purchase" in a message becomes "order"
                #     mdc: { key: orderId, aliases: [order_id, orderUuid] }  # same value: its other spellings merged
                #   supplier:                          # supplierId and supplierNumber are two values: two terms
                #     mdc: { key: supplierId }
                #   supplierNumber:
                #     mdc: { key: supplierNumber }
                #   approver:                          # a role: another field of the same kind as userId,
                #     mdc: { key: approverUserId }     # so its own term, never an alias of it
                # Delete the keys that are not business terms (technical ones like retryCount).
                """.formatted(VOCABULARY_GUIDE));
        sb.append("vocabulary:\n");
        int width = r.terms().stream().mapToInt(t -> yamlKey(t.key()).length()).max().orElse(10) + 4;
        for (Term t : r.terms()) {
            String head = "  " + yamlKey(t.key()) + ":";
            sb.append(head).append(" ".repeat(Math.max(1, width - head.length()))).append("# ").append(counts(t))
                    .append("\n    mdc: ").append(mdc(t)).append('\n');
        }
        if (!r.unused().isEmpty()) {
            sb.append("\n# Identifiers declared in the code that no log or MDC uses yet; remove the \"#\" to add one:\n");
            r.unused().stream().limit(60).forEach(t -> sb.append("#  ").append(yamlKey(t.key())).append(": { mdc: ").append(mdc(t))
                    .append(" }   # ").append(counts(t)).append('\n'));
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

    private static String counts(Term t) {
        String s = t.mdc() + " / " + t.log() + " / " + t.declared() + ", " + t.files() + (t.files() == 1 ? " file" : " files");
        if (!t.sameAs().isEmpty()) {
            s += "; same value as " + String.join(", ", t.sameAs().stream().limit(6).toList())
                    + (t.sameAs().size() > 6 ? ", +" + (t.sameAs().size() - 6) : "") + "?";
        }
        return t.kindOf() == null ? s : s + "; a kind of " + t.kindOf() + "?";
    }

    private static String mdc(Term t) {
        return "{ key: " + yamlKey(t.key())
                + (t.params().isEmpty() ? "" : ", sources: { params: [" + String.join(", ", t.params()) + "] }") + " }";
    }

    /**
     * The identifier a key names, for the "same value as" hint: {@code orderId, order_id, order.id, ORDER_ID, orderUuid}
     * → {@code order#id}; {@code supplierNumber, supplier_no} → {@code supplier#number}, never the same as an id. A key
     * without such a suffix ({@code tenant}) counts as an id.
     */
    static String identity(String key) {
        String words = dotted(key);
        int dot = words.lastIndexOf('.');
        String last = words.substring(dot + 1);
        boolean number = last.equals("number") || last.equals("no");
        boolean suffix = dot > 0 && (number || last.equals("id") || last.equals("uuid"));
        return (suffix ? words.substring(0, dot) : words) + (number ? "#number" : "#id");
    }

    static String dotted(String key) {
        return key.replaceAll("([a-z0-9])([A-Z])", "$1.$2").replaceAll("[_\\-]+", ".").toLowerCase(Locale.ROOT);
    }

    /** The key as written; quoted only when YAML would read it as something else. */
    private static String yamlKey(String key) {
        return key.matches("[A-Za-z][\\w.\\-]*") && !YAML_WORDS.contains(key.toLowerCase(Locale.ROOT)) ? key : "\"" + key + "\"";
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
            return dev.logast.parse.SourceText.read(p);
        } catch (IOException e) {
            return "";
        }
    }
}
