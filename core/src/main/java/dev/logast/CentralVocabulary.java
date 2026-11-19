package dev.logast;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * The central vocabulary across services (#113): every key the services use, with its evidence, in one worksheet the
 * owner decides on ({@code same_as}, {@code term}, {@code local}); the decided terms are published to a generated
 * {@code company.yaml} that services {@code extends:}. Possible same-key pairs are only pointed out, never merged.
 * Text-based like {@link VocabularyHarvest}: no classpath; value types are resolved from the service's own sources
 * where a declaration says them (a parameter, a local, a record component or an accessor's return type).
 */
public final class CentralVocabulary {
    public static final String WORKSHEET = "vocabulary-worksheet.yaml";
    public static final String COMPANY = "company.yaml";

    private static final Pattern MDC_PUT = Pattern.compile("\\b(?:MDC\\s*\\.\\s*(?:put|putCloseable)|MdcScope\\s*\\.\\s*of)\\s*\\(");
    private static final Pattern ADD_KEY_VALUE = Pattern.compile("\\.addKeyValue\\s*\\(");
    private static final Pattern LITERAL = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"");
    private static final Pattern KEY_LITERAL = Pattern.compile("\"([A-Za-z][\\w.\\-]*)\"");
    private static final Pattern ACCESSOR = Pattern.compile("(?:this\\.)?([a-z]\\w*)\\.(\\w+)\\(\\s*\\)");
    private static final Pattern NAME = Pattern.compile("(?:this\\.)?([a-z]\\w*)");
    private static final Pattern TO_TEXT = Pattern.compile("String\\.valueOf\\((.*)\\)|(.*)\\.toString\\(\\s*\\)");
    private static final String TYPE = "((?:[a-z]\\w*\\.)*[A-Z]\\w*(?:<[^;{}()=]*?>)?|long|int|short|byte|boolean|char|double|float)(?:\\[])?";
    private static final Pattern PACKAGE = Pattern.compile("(?m)^\\s*package\\s+([\\w.]+)\\s*;");
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());

    private CentralVocabulary() {
    }

    /**
     * One place a service uses a key.
     *
     * @param kind     {@code mdc} (MDC.put / MdcScope.of), {@code kv} ({@code [k={}]} in a message) or {@code addKeyValue}
     * @param value    the value expression as written ({@code order.id()})
     * @param from     the accessor it comes from ({@code Order.id()}), null when the value is a plain name or unknown
     * @param type     the value's type as declared ({@code Long}), null when no declaration says it
     * @param typeFqn  the accessor's owner, fully qualified (for {@code sources.types}), or null
     * @param accessor the accessor call ({@code id()}), or null
     */
    public record Use(String key, String service, String file, int line, String kind, String value, String from,
                      String type, String typeFqn, String accessor) {
        String entity() {
            return from == null ? null : from.substring(0, from.indexOf('.'));
        }

        /** A plain name ({@code orderId}, {@code this.orderId}): a parameter or variable, a {@code sources.params} entry. */
        String param() {
            Matcher m = NAME.matcher(value);
            return m.matches() ? m.group(1) : null;
        }
    }

    /**
     * What the owner wrote for a key; logast keeps it across runs.
     *
     * @param area with {@code term}: the area the term is published under ({@code areas.<area>.vocabulary}, #121)
     */
    public record Decision(String term, String sameAs, boolean local, String owner, String area) {
        static final Decision NONE = new Decision(null, null, false, null, null);

        public Decision(String term, String sameAs, boolean local, String owner) {
            this(term, sameAs, local, owner, null);
        }

        boolean any() {
            return term != null || sameAs != null || local;
        }
    }

    /** The worksheet of the previous run: its decisions and the keys it listed (to tell new keys). */
    public record Previous(Map<String, Decision> decisions, Set<String> keys, Map<String, List<String>> areaPackages) {
        public static final Previous NONE = new Previous(Map.of(), Set.of(), Map.of());

        public Previous(Map<String, Decision> decisions, Set<String> keys) {
            this(decisions, keys, Map.of());
        }
    }

    /** An area a service's own logast.yaml defines ({@code Policy.Area}): listed in the worksheet to decide with (#121). */
    public record KnownArea(String service, String area, List<String> packages, List<String> vocabulary, List<String> requiredMdc) {
    }

    /**
     * The result of one run: the two files' text and the summary lines (also at the top of the worksheet).
     *
     * @param conflicts the undecided keys' warnings and similar pairs, one line each (the fleet overview, #124)
     */
    public record Result(String worksheet, String company, List<String> summary, int terms, List<String> conflicts) {
    }

    // ---- harvest ------------------------------------------------------------------------------------------------

    /** Every use of an identifier key in the service's main sources, with its evidence. */
    public static List<Use> uses(Path root, String service) {
        List<Path> files = VocabularyHarvest.javaFiles(root);
        Map<String, List<Path>> types = new HashMap<>();
        for (Path p : files) {
            String n = p.getFileName().toString();
            types.computeIfAbsent(n.substring(0, n.length() - ".java".length()), k -> new ArrayList<>()).add(p);
        }
        Map<Path, String> texts = new HashMap<>();
        List<Use> out = new ArrayList<>();
        for (Path p : files) {
            String text = texts.computeIfAbsent(p, VocabularyHarvest::read);
            String rel = root.relativize(p).toString().replace('\\', '/');
            Resolver resolver = new Resolver(text, types, texts);
            Matcher m = MDC_PUT.matcher(text);
            while (m.find()) {
                List<String> args = args(VocabularyHarvest.callText(text, m.end()));
                for (int i = 0; i + 1 < args.size(); i += 2) {
                    Matcher k = KEY_LITERAL.matcher(args.get(i));
                    if (k.matches()) {
                        add(out, k.group(1), "mdc", args.get(i + 1), service, rel, text, m.start(), resolver);
                    }
                }
            }
            Matcher kv = ADD_KEY_VALUE.matcher(text);
            while (kv.find()) {
                List<String> args = args(VocabularyHarvest.callText(text, kv.end()));
                Matcher k = args.size() == 2 ? KEY_LITERAL.matcher(args.getFirst()) : null;
                if (k != null && k.matches()) {
                    add(out, k.group(1), "addKeyValue", args.get(1), service, rel, text, kv.start(), resolver);
                }
            }
            Matcher lc = VocabularyHarvest.LOG_CALL.matcher(text);
            while (lc.find()) {
                List<String> args = args(VocabularyHarvest.callText(text, lc.end()));
                int t = !args.isEmpty() && args.getFirst().startsWith("\"") ? 0 : args.size() > 1 && args.get(1).startsWith("\"") ? 1 : -1;
                if (t < 0) {
                    continue;
                }
                List<String> parts = split(args.get(t), '+');
                int placeholders = 0;
                for (int pi = 0; pi < parts.size(); pi++) {
                    Matcher lit = LITERAL.matcher(parts.get(pi));
                    if (!lit.matches()) {
                        continue;
                    }
                    String literal = lit.group(1);
                    Matcher k = VocabularyHarvest.LOG_KEY.matcher(literal);
                    while (k.find()) {
                        String value = null;
                        if (k.group().endsWith("{}")) {
                            int index = placeholders + count(literal.substring(0, k.start()), "{}");
                            value = t + 1 + index < args.size() ? args.get(t + 1 + index) : null;
                        } else if (pi + 1 < parts.size() && !parts.get(pi + 1).startsWith("\"")) {
                            value = parts.get(pi + 1);   // "… orderId=" + orderId
                        }
                        add(out, k.group(1), "kv", value, service, rel, text, lc.start(), resolver);
                    }
                    placeholders += count(literal, "{}");
                }
            }
        }
        return out;
    }

    private static void add(List<Use> out, String key, String kind, String value, String service, String file, String text,
                            int at, Resolver resolver) {
        String lower = key.toLowerCase(Locale.ROOT);
        if (VocabularyHarvest.AMBIENT.contains(lower) || VocabularyHarvest.NOT_A_KEY.contains(lower)
                || !kind.equals("mdc") && !VocabularyHarvest.ID_SUFFIX.matcher(key).find()) {
            return;
        }
        String v = value == null ? "?" : value.strip().replaceAll("\\s+", " ");
        Resolver.Resolved r = value == null ? Resolver.UNKNOWN : resolver.resolve(v, at);
        out.add(new Use(key, service, file, line(text, at), kind, v, r.from(), r.type(), r.fqn(), r.accessor()));
    }

    /** Resolves a value's type from declarations in the file and in the service's other sources. */
    private record Resolver(String text, Map<String, List<Path>> types, Map<Path, String> texts) {
        record Resolved(String from, String type, String fqn, String accessor) {
        }

        static final Resolved UNKNOWN = new Resolved(null, null, null, null);

        Resolved resolve(String value, int at) {
            if (LITERAL.matcher(value).matches()) {
                return new Resolved(null, "String", null, null);
            }
            Matcher toText = TO_TEXT.matcher(value);
            if (toText.matches()) {
                // MDC takes text: String.valueOf(order.id()) carries order.id(), a Long
                Resolved inner = resolve((toText.group(1) != null ? toText.group(1) : toText.group(2)).strip(), at);
                return inner;
            }
            Matcher acc = ACCESSOR.matcher(value);
            if (acc.matches()) {
                String owner = declaredType(text, at, acc.group(1));
                if (owner == null) {
                    return UNKNOWN;
                }
                String simple = simpleName(owner.replaceAll("<.*", ""));
                Path source = types.getOrDefault(simple, List.of()).stream().findFirst().orElse(null);
                String ownerText = source == null ? null : texts.computeIfAbsent(source, VocabularyHarvest::read);
                String type = ownerText == null ? null : memberType(ownerText, simple, acc.group(2));
                String fqn = null;
                if (ownerText != null) {
                    Matcher pkg = PACKAGE.matcher(ownerText);
                    fqn = pkg.find() ? pkg.group(1) + "." + simple : simple;
                }
                return new Resolved(simple + "." + acc.group(2) + "()", type, fqn, acc.group(2) + "()");
            }
            Matcher name = NAME.matcher(value);
            if (name.matches()) {
                return new Resolved(null, declaredType(text, at, name.group(1)), null, null);
            }
            return UNKNOWN;
        }
    }

    /** The declared type of {@code name}: the nearest declaration before {@code at}, else the first one in the file. */
    static String declaredType(String text, int at, String name) {
        Matcher m = Pattern.compile("(?<![\\w.])" + TYPE + "\\s+" + Pattern.quote(name) + "\\b(?=\\s*[,)=;:])").matcher(text);
        String before = null;
        String after = null;
        while (m.find()) {
            if (m.start() < at) {
                before = m.group(1);
            } else if (after == null) {
                after = m.group(1);
            }
        }
        String t = before != null ? before : after;
        return t == null || t.equals("return") ? null : normalised(t);
    }

    /** The type {@code owner.member()} returns: a record component, a method, or the field a getter reads. */
    static String memberType(String ownerText, String owner, String member) {
        String field = member.matches("(?:get|is)[A-Z]\\w*")
                ? Character.toLowerCase(member.charAt(member.startsWith("is") ? 2 : 3)) + member.substring(member.startsWith("is") ? 3 : 4)
                : member;
        Matcher record = Pattern.compile("\\brecord\\s+" + Pattern.quote(owner) + "\\s*(?:<[^>]*>)?\\s*\\(([^)]*)\\)").matcher(ownerText);
        if (record.find()) {
            for (String component : split(record.group(1), ',')) {
                String c = component.replaceAll("@\\w+(?:\\([^)]*\\))?", "").strip();
                int space = c.lastIndexOf(' ');
                if (space > 0 && c.substring(space + 1).equals(member)) {
                    return normalised(c.substring(0, space).strip());
                }
            }
        }
        Matcher method = Pattern.compile("(?<![\\w.])" + TYPE + "\\s+" + Pattern.quote(member) + "\\s*\\(\\s*\\)").matcher(ownerText);
        if (method.find()) {
            return normalised(method.group(1));
        }
        return declaredType(ownerText, ownerText.length(), field);
    }

    private static String normalised(String type) {
        String t = type.replaceAll("\\s+", "");
        return switch (t) {
            case "long" -> "Long";
            case "int" -> "Integer";
            case "short" -> "Short";
            case "byte" -> "Byte";
            case "boolean" -> "Boolean";
            case "char" -> "Character";
            case "double" -> "Double";
            case "float" -> "Float";
            default -> t.contains("<") ? t : simpleName(t);
        };
    }

    private static String simpleName(String type) {
        return type.substring(type.lastIndexOf('.') + 1);
    }

    /** A call's arguments, split at top-level commas. */
    static List<String> args(String call) {
        return split(call, ',').stream().map(String::strip).filter(s -> !s.isEmpty()).toList();
    }

    /** {@code code} split at {@code sep} outside parentheses, brackets, braces and string literals. */
    private static List<String> split(String code, char sep) {
        List<String> out = new ArrayList<>();
        int depth = 0;
        int angle = 0;
        int start = 0;
        for (int i = 0; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '"') {
                for (i++; i < code.length() && code.charAt(i) != '"'; i++) {
                    if (code.charAt(i) == '\\') {
                        i++;
                    }
                }
            } else if (c == '\'') {
                for (i++; i < code.length() && code.charAt(i) != '\''; i++) {
                    if (code.charAt(i) == '\\') {
                        i++;
                    }
                }
            } else if (c == '(' || c == '[' || c == '{') {
                depth++;
            } else if (c == ')' || c == ']' || c == '}') {
                depth = Math.max(0, depth - 1);
            } else if (c == '<' && Character.isUpperCase(prevWordStart(code, i))) {
                angle++;   // a generic type's arguments: Map<String, Long>
            } else if (c == '>' && angle > 0) {
                angle--;
            } else if (c == sep && depth == 0 && angle == 0) {
                out.add(code.substring(start, i).strip());
                start = i + 1;
            }
        }
        out.add(code.substring(start).strip());
        return out;
    }

    /** First character of the word just before {@code i} (a generic type's name before its {@code <}). */
    private static char prevWordStart(String code, int i) {
        int j = i;
        while (j > 0 && Character.isJavaIdentifierPart(code.charAt(j - 1))) {
            j--;
        }
        return j < i ? code.charAt(j) : ' ';
    }

    private static int count(String s, String what) {
        int n = 0;
        for (int i = s.indexOf(what); i >= 0; i = s.indexOf(what, i + what.length())) {
            n++;
        }
        return n;
    }

    private static int line(String text, int offset) {
        int n = 1;
        for (int i = 0; i < offset && i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                n++;
            }
        }
        return n;
    }

    // ---- worksheet ----------------------------------------------------------------------------------------------

    /** The decisions and keys of an earlier worksheet; {@link Previous#NONE} when there is none. */
    public static Previous read(Path worksheet) {
        if (!Files.exists(worksheet)) {
            return Previous.NONE;
        }
        JsonNode root;
        try {
            root = YAML.readTree(Files.readString(worksheet));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + worksheet + ": " + e.getMessage(), e);
        }
        Map<String, Decision> decisions = new TreeMap<>();
        Set<String> keys = new TreeSet<>();
        Map<String, List<String>> areaPackages = new TreeMap<>();
        if (root != null) {
            root.path("keys").fields().forEachRemaining(e -> {
                keys.add(e.getKey());
                JsonNode n = e.getValue();
                Decision d = new Decision(text(n.get("term")), text(n.get("same_as")),
                        n.path("local").asBoolean(false) || "true".equals(text(n.get("local"))), text(n.get("owner")),
                        text(n.get("area")));
                if (d.any() || d.owner() != null || d.area() != null) {
                    decisions.put(e.getKey(), d);
                }
            });
            root.path("areas").fields().forEachRemaining(e -> {
                List<String> packages = strings(e.getValue().path("packages"));
                if (!packages.isEmpty()) {
                    areaPackages.put(e.getKey(), packages);
                }
            });
        }
        return new Previous(decisions, keys, areaPackages);
    }

    /** A YAML list (or map: its keys, or a single scalar) as strings. */
    private static List<String> strings(JsonNode n) {
        List<String> out = new ArrayList<>();
        if (n.isArray()) {
            n.forEach(x -> {
                if (!x.asText().isBlank()) {
                    out.add(x.asText().strip());
                }
            });
        } else if (n.isObject()) {
            n.fieldNames().forEachRemaining(out::add);
        } else if (!n.isMissingNode() && !n.isNull() && !n.asText().isBlank()) {
            out.add(n.asText().strip());
        }
        return out;
    }

    /** The areas a service's own logast.yaml defines (not its parents': the central ones come from the worksheet). */
    public static List<KnownArea> knownAreas(Path root, String service) {
        Path file = dev.logast.policy.PolicyLoader.defaultFile(root);
        if (!Files.exists(file)) {
            return List.of();
        }
        JsonNode node;
        try {
            node = YAML.readTree(Files.readString(file));
        } catch (IOException e) {
            return List.of();
        }
        List<KnownArea> out = new ArrayList<>();
        if (node != null) {
            node.path("areas").fields().forEachRemaining(e -> out.add(new KnownArea(service, e.getKey(),
                    strings(e.getValue().path("packages")), strings(e.getValue().path("vocabulary")),
                    strings(e.getValue().path("requiredMdc")))));
        }
        return out;
    }

    private static String text(JsonNode n) {
        return n == null || n.isNull() || n.asText().isBlank() ? null : n.asText().strip();
    }

    /** Harvests every service, merges with the earlier worksheet's decisions and writes both files into {@code dir}. */
    public static Result run(Path dir, Map<String, Path> services) {
        List<Use> uses = new ArrayList<>();
        List<KnownArea> known = new ArrayList<>();
        services.forEach((name, root) -> {
            uses.addAll(uses(root, name));
            known.addAll(knownAreas(root, name));
        });
        Path worksheet = dir.resolve(WORKSHEET);
        Result r = build(uses, services.keySet(), read(worksheet), known);
        try {
            Files.createDirectories(dir);
            Files.writeString(worksheet, r.worksheet());
            writeCompany(dir.resolve(COMPANY), r.company());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return r;
    }

    static final String BEGIN = "# >>> logast: generated from vocabulary-worksheet.yaml, rewritten on every run: decide in the "
            + "worksheet, do not edit down to the <<< line";
    static final String END = "# <<< logast: end of the generated part. Settings of your own for every service (packs, logging, "
            + "rules, …) go outside it and are kept";

    /**
     * Writes the generated vocabulary into company.yaml between the {@link #BEGIN} and {@link #END} lines, keeping
     * everything outside them as written (#124: the company's own policy lives in the same file). A file without the
     * lines was wholly generated (before #124) and is replaced.
     */
    public static void writeCompany(Path file, String generated) throws IOException {
        String existing = Files.exists(file) ? Files.readString(file) : null;
        Files.createDirectories(file.toAbsolutePath().getParent());
        Files.writeString(file, mergeCompany(existing, generated));
    }

    static String mergeCompany(String existing, String generated) {
        String block = BEGIN + "\n" + generated + (generated.endsWith("\n") ? "" : "\n") + END + "\n";
        if (existing != null) {
            int b = existing.indexOf("# >>> logast");
            int e = existing.indexOf("# <<< logast");
            if (b >= 0 && e > b) {
                int eol = existing.indexOf('\n', e);
                return existing.substring(0, b) + block + (eol < 0 ? "" : existing.substring(eol + 1));
            }
        }
        return block;
    }

    /** The worksheet and company.yaml for these uses and the earlier decisions. */
    public static Result build(List<Use> uses, Collection<String> serviceNames, Previous previous) {
        return build(uses, serviceNames, previous, List.of());
    }

    /** The same, also listing the areas the services' own policies define ({@code known}, #121). */
    public static Result build(List<Use> uses, Collection<String> serviceNames, Previous previous, List<KnownArea> known) {
        Map<String, List<Use>> byKey = new TreeMap<>();
        uses.forEach(u -> byKey.computeIfAbsent(u.key(), k -> new ArrayList<>()).add(u));
        byKey.values().forEach(l -> l.sort(Comparator.comparing(Use::service).thenComparing(Use::file).thenComparingInt(Use::line)));
        Map<String, Decision> decisions = previous.decisions();
        Set<String> keys = new TreeSet<>(byKey.keySet());
        keys.addAll(decisions.keySet());   // a decision stays, also when no service uses its key any more

        // groups: same_as followed to the key that is not an alias of another
        List<String> problems = new ArrayList<>();
        Map<String, String> root = new TreeMap<>();
        for (String k : keys) {
            String r = rootOf(k, decisions, keys);
            if (r == null) {
                problems.add(k + ": same_as " + decisions.get(k).sameAs()
                        + (keys.contains(decisions.get(k).sameAs()) ? " goes round in a circle" : " names no key of this worksheet"));
            } else {
                root.put(k, r);
            }
        }
        Map<String, String> termOwners = new TreeMap<>();
        Set<String> clashing = new TreeSet<>();
        for (String k : keys) {
            Decision d = decisions.getOrDefault(k, Decision.NONE);
            if (d.term() != null && d.sameAs() != null) {
                problems.add(k + ": term and same_as both set; keep one");
            }
            if (d.local() && (d.term() != null || d.sameAs() != null)) {
                problems.add(k + ": local together with " + (d.term() != null ? "term" : "same_as") + "; keep one");
            }
            if (d.area() != null && d.term() == null) {
                problems.add(k + ": area " + d.area() + " without term: an area goes next to term: (its same_as keys follow it)");
            }
            if (d.term() != null && d.sameAs() == null && !d.local()) {
                String other = termOwners.putIfAbsent(d.term(), k);
                if (other != null) {
                    problems.add("term " + d.term() + " is decided for both " + other + " and " + k + "; one key per term "
                            + "(make the other same_as " + other + ")");
                    clashing.add(d.term());
                }
            }
            String r = root.get(k);
            if (d.sameAs() != null && r != null) {
                Decision rd = decisions.getOrDefault(r, Decision.NONE);
                if (rd.local()) {
                    problems.add(k + ": same_as " + r + ", which is local: not published");
                } else if (rd.term() == null) {
                    problems.add(k + ": same_as " + r + ", which has no term yet: give " + r + " a term: to publish both");
                }
            }
        }

        // the summary: what to decide, what is decided but no longer used, what the code still does against a decision
        List<String> toDecide = new ArrayList<>();
        int fresh = 0;
        List<String> unused = new ArrayList<>();
        List<String> against = new ArrayList<>();
        for (String k : keys) {
            Decision d = decisions.getOrDefault(k, Decision.NONE);
            List<Use> u = byKey.getOrDefault(k, List.of());
            if (!d.any() && !u.isEmpty()) {
                boolean isNew = !previous.keys().contains(k);
                fresh += isNew ? 1 : 0;
                toDecide.add(k + (isNew ? " (new)" : ""));
            }
            if (d.term() != null && d.sameAs() == null && root.containsValue(k)
                    && root.entrySet().stream().filter(e -> e.getValue().equals(k)).allMatch(e -> !byKey.containsKey(e.getKey()))) {
                unused.add("term " + d.term() + " (" + k + ")");
            } else if (d.any() && d.term() == null && u.isEmpty()) {
                unused.add(k + " (" + (d.local() ? "local" : "same_as " + d.sameAs()) + ")");
            }
            if (d.sameAs() != null && !u.isEmpty()) {
                against.add(k + " (same_as " + d.sameAs() + ") still in " + String.join(", ", services(u))
                        + ": apply renames it in services that extend company.yaml");
            }
            if (d.local() && services(u).size() > 1) {
                against.add(k + " (local) is used by " + services(u).size() + " services: " + String.join(", ", services(u)));
            }
        }
        // published terms: a key with term:, its same_as keys as aliases
        Map<String, String> published = new TreeMap<>();   // term -> root key
        termOwners.forEach((term, k) -> {
            if (!clashing.contains(term)) {
                published.put(term, k);
            }
        });

        // areas (#121): the ones decided here, the ones the services' own policies define, and their published terms
        Map<String, List<String>> areaTerms = new TreeMap<>();
        published.forEach((term, k) -> {
            String area = decisions.getOrDefault(k, Decision.NONE).area();
            if (area != null) {
                areaTerms.computeIfAbsent(area, a -> new ArrayList<>()).add(term);
            }
        });
        Map<String, List<KnownArea>> knownBy = new TreeMap<>();
        known.forEach(a -> knownBy.computeIfAbsent(a.area(), x -> new ArrayList<>()).add(a));
        Set<String> areaNames = new TreeSet<>(areaTerms.keySet());
        areaNames.addAll(previous.areaPackages().keySet());
        areaNames.addAll(knownBy.keySet());
        decisions.values().stream().map(Decision::area).filter(Objects::nonNull).forEach(areaNames::add);

        List<String> summary = new ArrayList<>();
        summary.add("services: " + serviceNames.size() + " (" + String.join(", ", new TreeSet<>(serviceNames)) + "), keys: "
                + byKey.size() + ", decided: " + keys.stream().filter(k -> decisions.getOrDefault(k, Decision.NONE).any()).count());
        summary.add("to decide: " + (toDecide.isEmpty() ? "-" : toDecide.size() + " (" + fresh + " new): " + String.join(", ", toDecide)));
        summary.add("decided, no service uses it any more: " + (unused.isEmpty() ? "-" : String.join(", ", unused)));
        summary.add("still used against a decision:" + (against.isEmpty() ? " -" : ""));
        against.forEach(a -> summary.add("  " + a));
        if (!areaNames.isEmpty()) {
            summary.add("areas: " + areaNames.stream().map(a -> {
                int n = areaTerms.getOrDefault(a, List.of()).size();
                List<String> notes = new ArrayList<>();
                notes.add(n + (n == 1 ? " term" : " terms"));
                if (knownBy.containsKey(a)) {
                    notes.add("in the logast.yaml of " + String.join(", ", knownBy.get(a).stream().map(KnownArea::service).distinct().sorted().toList()));
                }
                if (n > 0 && !previous.areaPackages().containsKey(a)
                        && knownBy.getOrDefault(a, List.of()).stream().allMatch(k -> k.packages().isEmpty())) {
                    notes.add("no packages: its terms' words apply nowhere; give it packages: in the areas section");
                }
                return a + " (" + String.join("; ", notes) + ")";
            }).collect(Collectors.joining(", ")));
        }
        if (!problems.isEmpty()) {
            summary.add("decisions to fix (not published until fixed):");
            problems.forEach(p -> summary.add("  " + p));
        }

        StringBuilder ws = new StringBuilder();
        ws.append("""
                # logast vocabulary worksheet (#113): every key the services use, with its evidence. Decide here, then run
                # `logast vocabulary --central <this folder> <service>…` again: it keeps your decisions, refreshes the evidence
                # and publishes the decided terms to company.yaml next to this file (never edit that one).
                # Decisions, per key (logast never decides them):
                #   term: <name>      publish the key as the term <name>; the key is its canonical MDC / [k=v] key
                #   same_as: <key>    the same value as <key>: published as an alias of <key>'s term (apply renames it)
                #   local: true       stays in its service, not published
                #   owner: <team>     optional: who to ask about the term (a comment in company.yaml)
                #   area: <name>      optional, with term: published under areas.<name>.vocabulary in company.yaml
                # Per area (section `areas`): packages: [..] is your decision, published with the area's terms.
                # Everything else (services, types, evidence, similar, suggest, terms, known_in) is rewritten on each run.
                #
                # Summary of this run:
                """);
        summary.forEach(s -> ws.append("#   ").append(s).append('\n'));
        if (!areaNames.isEmpty()) {
            ws.append("areas:\n");
            for (String a : areaNames) {
                ws.append("  ").append(VocabularyHarvest.yamlKey(a)).append(":\n");
                if (previous.areaPackages().containsKey(a)) {
                    ws.append("    packages: ").append(list(previous.areaPackages().get(a))).append('\n');
                }
                if (areaTerms.containsKey(a)) {
                    ws.append("    terms: ").append(list(areaTerms.get(a))).append('\n');
                }
                if (knownBy.containsKey(a)) {
                    ws.append("    known_in:\n");
                    for (KnownArea k : knownBy.get(a)) {
                        ws.append("      - {service: ").append(q(k.service()));
                        if (!k.packages().isEmpty()) {
                            ws.append(", packages: ").append(list(k.packages()));
                        }
                        if (!k.vocabulary().isEmpty()) {
                            ws.append(", vocabulary: ").append(list(k.vocabulary()));
                        }
                        if (!k.requiredMdc().isEmpty()) {
                            ws.append(", requiredMdc: ").append(list(k.requiredMdc()));
                        }
                        ws.append("}\n");
                    }
                }
            }
        }
        ws.append("keys:\n");
        List<String> conflicts = new ArrayList<>();
        Set<String> pairs = new HashSet<>();
        for (String k : keys) {
            Decision d = decisions.getOrDefault(k, Decision.NONE);
            List<Use> u = byKey.getOrDefault(k, List.of());
            ws.append("  ").append(VocabularyHarvest.yamlKey(k)).append(":\n");
            if (d.term() != null) {
                ws.append("    term: ").append(VocabularyHarvest.yamlKey(d.term())).append('\n');
            }
            if (d.sameAs() != null) {
                ws.append("    same_as: ").append(VocabularyHarvest.yamlKey(d.sameAs())).append('\n');
            }
            if (d.local()) {
                ws.append("    local: true\n");
            }
            if (d.owner() != null) {
                ws.append("    owner: ").append(q(d.owner())).append('\n');
            }
            if (d.area() != null) {
                ws.append("    area: ").append(VocabularyHarvest.yamlKey(d.area())).append('\n');
            }
            ws.append("    services: [").append(String.join(", ", services(u))).append("]\n");
            Map<String, Set<String>> typeServices = typeServices(u);
            if (!typeServices.isEmpty()) {
                ws.append("    types: [").append(typeServices.keySet().stream().map(CentralVocabulary::q).collect(Collectors.joining(", "))).append("]\n");
            }
            if (typeServices.size() > 1) {
                ws.append("    warning: ").append(q("one name, different types: " + typeServices.entrySet().stream()
                        .map(e -> e.getKey() + " (" + String.join(", ", e.getValue()) + ")").collect(Collectors.joining(", "))
                        + ": possibly NOT the same value; if so, give one of them another key")).append('\n');
            }
            List<String[]> similar = similar(k, keys, byKey, root);
            if (!d.any() && !u.isEmpty()) {
                if (typeServices.size() > 1) {
                    conflicts.add(k + ": one name, different types: " + typeServices.entrySet().stream()
                            .map(e -> e.getKey() + " (" + String.join(", ", e.getValue()) + ")").collect(Collectors.joining(", ")));
                }
                similar.stream().filter(s -> pairs.add(k.compareTo(s[0]) < 0 ? k + "\0" + s[0] : s[0] + "\0" + k))
                        .forEach(s -> conflicts.add(k + " ~ " + s[0] + ": " + s[1]));
            }
            if (!similar.isEmpty()) {
                ws.append("    similar:\n");
                similar.forEach(s -> ws.append("      - ").append(q(s[0] + ": " + s[1])).append('\n'));
            }
            String suggest = d.any() || u.isEmpty() ? null : suggestion(k, u, similar, decisions, root);
            if (suggest != null) {
                ws.append("    suggest: ").append(q(suggest)).append('\n');
            }
            if (!u.isEmpty()) {
                ws.append("    evidence:\n");
                for (Use e : u) {
                    ws.append("      - {service: ").append(q(e.service())).append(", at: ").append(q(e.file() + ":" + e.line()))
                            .append(", use: ").append(e.kind()).append(", value: ").append(q(e.value()));
                    if (e.from() != null) {
                        ws.append(", from: ").append(q(e.from()));
                    }
                    if (e.type() != null) {
                        ws.append(", type: ").append(q(e.type()));
                    }
                    ws.append("}\n");
                }
            }
        }
        if (keys.isEmpty()) {
            ws.append("  {}\n");
        }
        return new Result(ws.toString(), company(published, decisions, root, byKey, previous.areaPackages()), summary, published.size(),
                conflicts);
    }

    /** The key {@code k} is an alias of, following same_as; {@code k} itself when it is no alias; null on a cycle or unknown key. */
    private static String rootOf(String k, Map<String, Decision> decisions, Set<String> keys) {
        Set<String> seen = new HashSet<>();
        String cur = k;
        while (true) {
            Decision d = decisions.getOrDefault(cur, Decision.NONE);
            if (d.sameAs() == null) {
                return cur;
            }
            if (!seen.add(cur) || !keys.contains(d.sameAs())) {
                return null;
            }
            cur = d.sameAs();
        }
    }

    private static List<String> services(List<Use> uses) {
        return uses.stream().map(Use::service).distinct().sorted().toList();
    }

    /** Resolved type → the services where the key carries it. */
    private static Map<String, Set<String>> typeServices(List<Use> uses) {
        Map<String, Set<String>> out = new TreeMap<>();
        uses.stream().filter(u -> u.type() != null).forEach(u -> out.computeIfAbsent(u.type(), t -> new TreeSet<>()).add(u.service()));
        return out;
    }

    /**
     * Keys that may name the same value: the same words in another style ({@code orderId}, {@code order_id},
     * {@code order.id}), with what is the same and what differs. Pointed out only; keys already merged are left out.
     */
    private static List<String[]> similar(String k, Set<String> keys, Map<String, List<Use>> byKey, Map<String, String> root) {
        String identity = VocabularyHarvest.identity(k);
        List<String[]> out = new ArrayList<>();        for (String o : keys) {
            if (o.equals(k) || !VocabularyHarvest.identity(o).equals(identity)
                    || root.get(k) != null && root.get(k).equals(root.get(o))) {
                continue;
            }
            List<Use> a = byKey.getOrDefault(k, List.of());
            List<Use> b = byKey.getOrDefault(o, List.of());
            List<String> same = new ArrayList<>();
            List<String> differs = new ArrayList<>();
            Set<String> ta = typeServices(a).keySet();
            Set<String> tb = typeServices(b).keySet();
            if (!ta.isEmpty() && !tb.isEmpty()) {
                if (ta.equals(tb)) {
                    same.add("type " + String.join("/", ta));
                } else {
                    differs.add("type " + String.join("/", ta) + " vs " + String.join("/", tb) + ": possibly NOT the same value");
                }
            }
            Set<String> fa = a.stream().map(Use::from).filter(Objects::nonNull).collect(Collectors.toCollection(TreeSet::new));
            Set<String> fb = b.stream().map(Use::from).filter(Objects::nonNull).collect(Collectors.toCollection(TreeSet::new));
            Set<String> common = new TreeSet<>(fa);
            common.retainAll(fb);
            Set<String> ea = a.stream().map(Use::entity).filter(Objects::nonNull).collect(Collectors.toCollection(TreeSet::new));
            Set<String> eb = b.stream().map(Use::entity).filter(Objects::nonNull).collect(Collectors.toCollection(TreeSet::new));
            if (!common.isEmpty()) {
                same.add("source " + String.join(", ", common));
            } else if (!ea.isEmpty() && !eb.isEmpty()) {
                Set<String> ce = new TreeSet<>(ea);
                ce.retainAll(eb);
                if (!ce.isEmpty()) {
                    same.add("entity " + String.join(", ", ce));
                }
                differs.add("source " + String.join(", ", fa) + " vs " + String.join(", ", fb));
            }
            List<String> sa = services(a);
            List<String> sb = services(b);
            if (sa.equals(sb) && !sa.isEmpty()) {
                same.add("services " + String.join(", ", sa));
            } else {
                differs.add("services " + (sa.isEmpty() ? "-" : String.join(", ", sa)) + " vs " + (sb.isEmpty() ? "-" : String.join(", ", sb)));
            }
            out.add(new String[]{o, (same.isEmpty() ? "" : "same " + String.join(", ", same) + "; ")
                    + (differs.isEmpty() ? "nothing differs" : "differs: " + String.join("; ", differs))});
        }
        return out;
    }

    /** A hint for an undecided key: merge into a decided look-alike of the same type, or keep a one-service key local. */
    private static String suggestion(String k, List<Use> u, List<String[]> similar, Map<String, Decision> decisions, Map<String, String> root) {
        for (String[] s : similar) {
            String r = root.get(s[0]);
            boolean decided = r != null && decisions.getOrDefault(r, Decision.NONE).term() != null;
            if (decided && !s[1].contains("NOT the same")) {
                return "same_as: " + r + "? (" + s[0] + (r.equals(s[0]) ? "" : ", an alias of " + r) + " looks alike)";
            }
        }
        List<String> svc = services(u);
        if (svc.size() == 1 && similar.isEmpty()) {
            return "local: true? (only " + svc.getFirst() + " uses it)";
        }
        return null;
    }

    /** company.yaml: only the decided terms, with their aliases (same_as) and the sources the evidence shows. */
    private static String company(Map<String, String> published, Map<String, Decision> decisions, Map<String, String> root,
                                  Map<String, List<Use>> byKey, Map<String, List<String>> areaPackages) {
        StringBuilder sb = new StringBuilder("""
                # GENERATED by `logast vocabulary --central` / `logast fleet <name> --vocabulary` from the worksheet (#113):
                # decide there and run the command again. Services take these terms with `fleet: <name>` (a named fleet)
                # or extends: [<path or pinned URL of this file>] in their logast.yaml; apply then renames the aliases in
                # their code (MDC_KEY_ALIAS). The vocabulary and areas keys belong to this part: no second one outside it.
                """);
        if (published.isEmpty()) {
            return sb.append("vocabulary: {}\n").toString();
        }
        // #121: a term decided with an area goes under areas.<area>.vocabulary, the others stay global
        Map<String, String> global = new TreeMap<>();
        Map<String, Map<String, String>> byArea = new TreeMap<>();
        published.forEach((term, key) -> {
            String area = decisions.getOrDefault(key, Decision.NONE).area();
            (area == null ? global : byArea.computeIfAbsent(area, a -> new TreeMap<>())).put(term, key);
        });
        if (!global.isEmpty()) {
            sb.append("vocabulary:\n");
            global.forEach((term, key) -> term(sb, "  ", term, key, decisions, root, byKey));
        }
        if (!byArea.isEmpty()) {
            sb.append("areas:\n");
            byArea.forEach((area, terms) -> {
                sb.append("  ").append(VocabularyHarvest.yamlKey(area)).append(":\n");
                if (areaPackages.containsKey(area)) {
                    sb.append("    packages: ").append(list(areaPackages.get(area))).append('\n');
                }
                sb.append("    vocabulary:\n");
                terms.forEach((term, key) -> term(sb, "      ", term, key, decisions, root, byKey));
            });
        }
        return sb.toString();
    }

    /** One published term: the canonical key, its aliases (same_as) and the sources the evidence shows. */
    private static void term(StringBuilder sb, String indent, String term, String key, Map<String, Decision> decisions,
                             Map<String, String> root, Map<String, List<Use>> byKey) {
        List<String> group = root.entrySet().stream().filter(e -> e.getValue().equals(key)).map(Map.Entry::getKey).toList();
        List<String> aliases = group.stream().filter(k -> !k.equals(key)).sorted().toList();
        List<Use> u = group.stream().flatMap(k -> byKey.getOrDefault(k, List.of()).stream()).toList();
        Set<String> params = u.stream().map(Use::param).filter(Objects::nonNull).collect(Collectors.toCollection(TreeSet::new));
        Map<String, String> types = new TreeMap<>();
        u.stream().filter(x -> x.typeFqn() != null && x.typeFqn().contains(".")).forEach(x -> types.putIfAbsent(x.typeFqn(), x.accessor()));
        String owner = decisions.getOrDefault(key, Decision.NONE).owner();
        sb.append(indent).append(VocabularyHarvest.yamlKey(term)).append(":").append(owner == null ? "" : "   # owner: " + owner).append('\n');
        sb.append(indent).append("  mdc:\n").append(indent).append("    key: ").append(VocabularyHarvest.yamlKey(key)).append('\n');
        if (!aliases.isEmpty()) {
            sb.append(indent).append("    aliases: ").append(list(aliases)).append('\n');
        }
        if (!params.isEmpty() || !types.isEmpty()) {
            sb.append(indent).append("    sources:\n");
            if (!params.isEmpty()) {
                sb.append(indent).append("      params: [").append(String.join(", ", params)).append("]\n");
            }
            if (!types.isEmpty()) {
                sb.append(indent).append("      types:\n");
                types.forEach((t, a) -> sb.append(indent).append("        ").append(t).append(": ").append(q(a)).append('\n'));
            }
        }
    }

    private static String list(List<String> items) {
        return "[" + items.stream().map(VocabularyHarvest::yamlKey).collect(Collectors.joining(", ")) + "]";
    }

    /** A key the code uses that the policy makes an alias: apply renames it to {@code to} (MDC_KEY_ALIAS). */
    public record Rename(String from, String to, List<Use> places) {
    }

    /** What the policy's vocabulary aliases would rename in these uses (fleet --vocabulary). */
    public static List<Rename> renames(List<Use> uses, dev.logast.policy.Policy policy) {
        Map<String, String> aliases = policy.mdcAliases();
        Map<String, List<Use>> byKey = new TreeMap<>();
        uses.stream().filter(u -> aliases.containsKey(u.key())).forEach(u -> byKey.computeIfAbsent(u.key(), k -> new ArrayList<>()).add(u));
        return byKey.entrySet().stream().map(e -> new Rename(e.getKey(), aliases.get(e.getKey()), e.getValue())).toList();
    }

    private static String q(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
