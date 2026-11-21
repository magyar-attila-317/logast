package dev.logast.trace;

import dev.logast.model.ClassInfo;
import org.openrewrite.java.tree.J;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * #217: the configuration bound onto a {@code @ConfigurationProperties} class the way Spring binds it: each field (its
 * own and inherited) from the key below the prefix whose name matches it in relaxed form ({@code execution-plans},
 * {@code executionPlans}, {@code execution_plans}), scalars as literals that remember their key, enums as constants,
 * lists from {@code [i]} indexes (or a comma-separated value), maps from the keys below, nested types recursively.
 */
public final class Binding {
    private static final Set<String> SCALARS = Set.of("String", "Integer", "int", "Long", "long", "Boolean", "boolean", "Double",
            "double", "Float", "float", "Short", "short", "Byte", "byte", "Character", "char", "BigDecimal", "BigInteger", "Duration",
            "Period", "DataSize", "LocalDate", "LocalTime", "LocalDateTime", "ZoneId", "TimeZone", "URI", "URL", "Object", "CharSequence");
    private static final Set<String> LISTS = Set.of("List", "ArrayList", "LinkedList", "Collection", "Set", "LinkedHashSet",
            "HashSet", "SortedSet", "TreeSet", "Deque", "ArrayDeque", "Queue", "Iterable");
    private static final Set<String> MAPS = Set.of("Map", "HashMap", "LinkedHashMap", "TreeMap", "SortedMap", "EnumMap");
    private static final Pattern INDEX = Pattern.compile("^\\[(\\d+)]");

    private final Types types;
    private final Map<String, String> config;

    public Binding(Types types, Map<String, String> config) {
        this.types = types;
        this.config = config;
    }

    /** Spring's relaxed form of a name: lower case, without {@code -} and {@code _}. */
    public static String canonical(String s) {
        return s.toLowerCase(Locale.ROOT).replace("-", "").replace("_", "");
    }

    /** The prefix a class binds ({@code @ConfigurationProperties("x")}, {@code prefix =}/{@code value =}), else null. */
    public static String prefix(ClassInfo c) {
        for (J.Annotation an : c.decl().getLeadingAnnotations()) {
            if (an.getSimpleName().equals("ConfigurationProperties") && an.getArguments() != null) {
                for (var arg : an.getArguments()) {
                    String name = arg instanceof J.Assignment as ? as.getVariable().printTrimmed() : "value";
                    var v = arg instanceof J.Assignment as ? as.getAssignment() : arg;
                    if ((name.equals("prefix") || name.equals("value")) && v instanceof J.Literal l && l.getValue() instanceof String s) {
                        return s;
                    }
                }
            }
        }
        return null;
    }

    /** A {@code @ConfigurationProperties} class bound at its prefix. */
    public Value.Obj bind(ClassInfo c) {
        String p = prefix(c);
        return bind(c, p == null ? "" : p);
    }

    /** {@code c} bound from the keys below {@code key}. */
    public Value.Obj bind(ClassInfo c, String key) {
        Value.Obj o = new Value.Obj(c.fqn());
        o.from = key;
        Map<String, String> below = below(key);
        for (Types.Field f : types.fields(c)) {
            if (f.isStatic()) {
                continue;
            }
            String seg = segment(below.keySet(), f.name());
            if (seg == null) {
                continue;
            }
            Value v = value(f.type(), c, join(key, seg));
            if (v != null) {
                o.fields.put(f.name(), v);
            }
        }
        return o;
    }

    /** What a key binds to as {@code type} (written in {@code ctx}); null when the configuration sets nothing there. */
    Value value(String type, ClassInfo ctx, String key) {
        String raw = Types.raw(type);
        if (LISTS.contains(raw)) {
            String el = Types.args(type).isEmpty() ? "String" : Types.args(type).getFirst();
            Value.ListV list = new Value.ListV();
            list.from = key;
            Set<Integer> idx = new TreeSet<>();
            for (String k : below(key).keySet()) {
                Matcher m = INDEX.matcher(k);
                if (m.find()) {
                    idx.add(Integer.parseInt(m.group(1)));
                }
            }
            if (idx.isEmpty() && config.get(key) != null) {   // a comma-separated value
                for (String part : config.get(key).split(",")) {
                    if (!part.isBlank()) {
                        list.items.add(scalar(el, ctx, part.strip(), key));
                    }
                }
                return list;
            }
            for (int i : idx) {
                Value v = value(el, ctx, key + "[" + i + "]");
                if (v != null) {
                    list.items.add(v);
                }
            }
            return idx.isEmpty() ? null : list;
        }
        if (MAPS.contains(raw)) {
            String el = Types.args(type).size() < 2 ? "String" : Types.args(type).get(1);
            Value.MapV map = new Value.MapV();
            Set<String> keys = new TreeSet<>();
            for (String k : below(key).keySet()) {
                String head = k.startsWith("[") ? k.substring(0, k.indexOf(']') + 1) : k.split("[.\\[]", 2)[0];
                keys.add(head);
            }
            for (String k : keys) {
                String name = k.startsWith("[") ? k.substring(1, k.length() - 1) : k;
                Value v = value(el, ctx, k.startsWith("[") ? key + k : join(key, k));
                if (v != null) {
                    map.put(new Value.Lit(name, key), v);
                }
            }
            return keys.isEmpty() ? null : map;
        }
        ClassInfo c = SCALARS.contains(raw) ? null : types.resolve(type, ctx);
        if (c != null && !types.isEnum(c)) {
            return below(key).isEmpty() ? null : bind(c, key);
        }
        String v = config.get(key);
        return v == null ? null : scalar(type, ctx, v, key);
    }

    private Value scalar(String type, ClassInfo ctx, String v, String key) {
        ClassInfo c = SCALARS.contains(Types.raw(type)) ? null : types.resolve(type, ctx);
        if (types.isEnum(c)) {
            String name = Tracer.enumConstant(c, v);
            if (name != null) {
                return new Value.EnumConst(c.fqn(), name);
            }
        }
        return new Value.Lit(v, key);
    }

    /** The keys below {@code key}, without it: {@code a.b} → {@code c}, {@code [0].d}, … */
    private Map<String, String> below(String key) {
        Map<String, String> out = new TreeMap<>();
        String dot = key.isEmpty() ? "" : key + ".";
        for (var e : config.entrySet()) {
            String k = e.getKey();
            if (!key.isEmpty() && k.startsWith(key + "[")) {
                out.put(k.substring(key.length()), e.getValue());
            } else if (k.startsWith(dot) && k.length() > dot.length()) {
                out.put(k.substring(dot.length()), e.getValue());
            }
        }
        return out;
    }

    /** The first segment of {@code keys} that binds to a field so named (relaxed), as the configuration writes it. */
    private static String segment(Set<String> keys, String field) {
        String want = canonical(field);
        for (String k : keys) {
            String head = k.split("[.\\[]", 2)[0];
            if (!head.isEmpty() && canonical(head).equals(want)) {
                return head;
            }
        }
        return null;
    }

    private static String join(String a, String b) {
        return a.isEmpty() ? b : a + "." + b;
    }
}
