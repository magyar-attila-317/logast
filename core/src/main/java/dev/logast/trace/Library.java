package dev.logast.trace;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * #217: what the tracer knows of the JDK and libraries: {@code Optional}, lists and streams, maps, {@code Objects},
 * strings; a library object keeps what is set on it ({@code setX(v)}, a fluent builder's calls) and gives it back
 * ({@code getX()}); a factory bean is its object; a {@code Scheduler}'s calls are recorded.
 */
final class Library {
    /** The type of a Lombok builder of a project class: this prefix and the class. */
    static final String BUILDER = "builder:";
    private static final Set<String> LIST_TYPES = Set.of("ArrayList", "LinkedList", "ArrayDeque", "LinkedHashSet", "HashSet",
            "TreeSet", "CopyOnWriteArrayList", "Vector", "Stack");
    private static final Set<String> MAP_TYPES = Set.of("HashMap", "LinkedHashMap", "TreeMap", "EnumMap", "JobDataMap",
            "ConcurrentHashMap", "Hashtable", "Properties");
    private static final Set<String> UTILITIES = Set.of("Optional", "Objects", "List", "Set", "Arrays", "Collections", "Stream",
            "String", "TimeZone", "ZoneId", "TriggerBuilder", "JobBuilder", "CronScheduleBuilder", "SimpleScheduleBuilder", "Map");

    private Library() {
    }

    static boolean isStaticUtility(String type) {
        return UTILITIES.contains(type);
    }

    static Value statics(Tracer t, String type, String name, List<Value> a) {
        Value first = a.isEmpty() ? null : a.getFirst();
        switch (type) {
            case "Optional" -> {
                return switch (name) {
                    case "ofNullable", "of" -> first == null || isNull(first) ? new Value.Opt(null) : new Value.Opt(first);
                    case "empty" -> new Value.Opt(null);
                    default -> new Value.Unknown("Optional." + name);
                };
            }
            case "Objects" -> {
                return switch (name) {
                    case "isNull" -> first instanceof Value.Unknown ? first : Value.Lit.of(isNull(first));
                    case "nonNull" -> first instanceof Value.Unknown ? first : Value.Lit.of(!isNull(first));
                    case "requireNonNull" -> first;
                    case "requireNonNullElse" -> a.size() == 2 && isNull(first) ? a.get(1) : first;
                    case "equals" -> a.size() == 2 && a.get(0) instanceof Value.Lit x && a.get(1) instanceof Value.Lit y
                            ? Value.Lit.of(java.util.Objects.equals(x.value(), y.value())) : new Value.Unknown("equals");
                    default -> new Value.Unknown("Objects." + name);
                };
            }
            case "List", "Set", "Arrays", "Stream" -> {
                if (name.equals("of") || name.equals("asList")) {
                    Value.ListV l = new Value.ListV();
                    l.items.addAll(a);
                    return l;
                }
                return new Value.Unknown(type + "." + name);
            }
            case "TimeZone", "ZoneId" -> {
                return (name.equals("getTimeZone") || name.equals("of")) && first instanceof Value.Lit ? first : new Value.Unknown(type + "." + name);
            }
            case "String" -> {
                return name.equals("valueOf") && first instanceof Value.Lit l ? Value.Lit.of(l.text()) : new Value.Unknown("String." + name);
            }
            case "TriggerBuilder", "JobBuilder", "CronScheduleBuilder", "SimpleScheduleBuilder" -> {
                Value.Obj b = new Value.Obj(type);
                if (first != null) {
                    b.fields.put(name, first);   // newJob(X.class), cronSchedule("…")
                }
                t.made(b);
                return b;
            }
            default -> {
                return new Value.Unknown(type + "." + name);
            }
        }
    }

    static boolean isNull(Value v) {
        return v == null || v instanceof Value.Lit l && l.value() == null;
    }

    static Value construct(Tracer t, String type, List<Value> a) {
        Value first = a.isEmpty() ? null : a.getFirst();
        if (LIST_TYPES.contains(type)) {
            Value.ListV l = new Value.ListV();
            if (first instanceof Value.ListV from) {
                l.items.addAll(from.items);
            }
            return l;
        }
        if (MAP_TYPES.contains(type)) {
            Value.MapV m = new Value.MapV();
            if (first instanceof Value.MapV from) {
                from.entries.forEach(e -> m.put(e.getKey(), e.getValue()));
            }
            return m;
        }
        Value.Obj o = new Value.Obj(type);
        o.args.addAll(a);
        t.made(o);
        return o;
    }

    static Value optional(Tracer t, Value.Opt o, String name, List<Value> a) {
        Value fn = a.isEmpty() ? null : a.getFirst();
        Value in = o.inner();
        switch (name) {
            case "map" -> {
                if (in == null || !(fn instanceof Value.Fn f)) {
                    return in == null ? o : new Value.Unknown("map");
                }
                Value r = t.apply(f, List.of(in));
                return isNull(r) && !(r instanceof Value.Opt) ? new Value.Opt(null) : new Value.Opt(r);
            }
            case "flatMap" -> {
                if (in == null || !(fn instanceof Value.Fn f)) {
                    return in == null ? o : new Value.Unknown("flatMap");
                }
                Value r = t.apply(f, List.of(in));
                return r instanceof Value.Opt ? r : new Value.Opt(r);
            }
            case "filter" -> {
                if (in == null || !(fn instanceof Value.Fn f)) {
                    return o;
                }
                Value r = t.apply(f, List.of(in));
                return r instanceof Value.Lit l && Boolean.FALSE.equals(l.value()) ? new Value.Opt(null) : o;
            }
            case "ifPresent" -> {
                if (in != null && fn instanceof Value.Fn f) {
                    t.apply(f, List.of(in));
                }
                return Value.Lit.NULL;
            }
            case "ifPresentOrElse" -> {
                if (in != null && fn instanceof Value.Fn f) {
                    t.apply(f, List.of(in));
                } else if (in == null && a.size() > 1 && a.get(1) instanceof Value.Fn g) {
                    t.apply(g, List.of());
                }
                return Value.Lit.NULL;
            }
            case "orElseThrow", "get" -> {
                return in == null ? new Value.Unknown("an empty Optional") : in;
            }
            case "orElse" -> {
                return in == null ? (fn == null ? Value.Lit.NULL : fn) : in;
            }
            case "orElseGet" -> {
                return in != null ? in : fn instanceof Value.Fn f ? t.apply(f, List.of()) : new Value.Unknown("orElseGet");
            }
            case "isPresent" -> {
                return Value.Lit.of(in != null);
            }
            case "isEmpty" -> {
                return Value.Lit.of(in == null);
            }
            default -> {
                return new Value.Unknown("Optional." + name);
            }
        }
    }

    static Value list(Tracer t, Value.ListV l, String name, List<Value> a) {
        Value first = a.isEmpty() ? null : a.getFirst();
        switch (name) {
            case "stream", "parallelStream", "sequential", "parallel", "sorted", "distinct", "unmodifiableList", "toArray" -> {
                return l;
            }
            case "toList", "collect" -> {
                Value.ListV copy = new Value.ListV();
                copy.items.addAll(l.items);
                return copy;
            }
            case "map" -> {
                Value.ListV out = new Value.ListV();
                if (first instanceof Value.Fn f) {
                    for (Value v : new ArrayList<>(l.items)) {
                        out.items.add(t.apply(f, List.of(v)));
                    }
                }
                return out;
            }
            case "flatMap" -> {
                Value.ListV out = new Value.ListV();
                if (first instanceof Value.Fn f) {
                    for (Value v : new ArrayList<>(l.items)) {
                        Value r = t.apply(f, List.of(v));
                        if (r instanceof Value.ListV rl) {
                            out.items.addAll(rl.items);
                        }
                    }
                }
                return out;
            }
            case "filter" -> {
                Value.ListV out = new Value.ListV();
                if (first instanceof Value.Fn f) {
                    for (Value v : new ArrayList<>(l.items)) {
                        Value r = t.apply(f, List.of(v));
                        if (!(r instanceof Value.Lit lit && Boolean.FALSE.equals(lit.value()))) {
                            out.items.add(v);
                        }
                    }
                }
                return out;
            }
            case "forEach", "forEachOrdered" -> {
                if (first instanceof Value.Fn f) {
                    for (Value v : new ArrayList<>(l.items)) {
                        t.apply(f, List.of(v));
                    }
                }
                return Value.Lit.NULL;
            }
            case "findFirst", "findAny" -> {
                return new Value.Opt(l.items.isEmpty() ? null : l.items.getFirst());
            }
            case "get" -> {
                return first instanceof Value.Lit i && i.value() instanceof Number n && n.intValue() < l.items.size()
                        ? l.items.get(n.intValue()) : new Value.Unknown("get");
            }
            case "getFirst", "peekFirst", "peek", "element", "first" -> {
                return l.items.isEmpty() ? (name.startsWith("peek") ? Value.Lit.NULL : new Value.Unknown("empty")) : l.items.getFirst();
            }
            case "getLast", "peekLast", "last" -> {
                return l.items.isEmpty() ? Value.Lit.NULL : l.items.getLast();
            }
            case "pollFirst", "poll", "removeFirst", "pop", "remove" -> {
                if (name.equals("remove") && !a.isEmpty()) {
                    return new Value.Unknown("remove");
                }
                return l.items.isEmpty() ? Value.Lit.NULL : l.items.removeFirst();
            }
            case "pollLast", "removeLast" -> {
                return l.items.isEmpty() ? Value.Lit.NULL : l.items.removeLast();
            }
            case "isEmpty" -> {
                return Value.Lit.of(l.items.isEmpty());
            }
            case "size", "count" -> {
                return Value.Lit.of(l.items.size());
            }
            case "add", "addLast", "offer", "offerLast" -> {
                if (first != null) {
                    l.items.add(first);
                }
                return Value.Lit.of(true);
            }
            case "addFirst", "push", "offerFirst" -> {
                if (first != null) {
                    l.items.addFirst(first);
                }
                return Value.Lit.NULL;
            }
            case "addAll" -> {
                if (first instanceof Value.ListV more) {
                    l.items.addAll(more.items);
                }
                return Value.Lit.of(true);
            }
            default -> {
                return new Value.Unknown("list " + name);
            }
        }
    }

    static Value map(Value.MapV m, String name, List<Value> a) {
        Value first = a.isEmpty() ? null : a.getFirst();
        switch (name) {
            case "put", "putIfAbsent" -> {
                if (a.size() == 2 && (name.equals("put") || m.get(first) == null)) {
                    m.put(first, a.get(1));
                }
                return Value.Lit.NULL;
            }
            case "get", "getString", "getInt", "getLong", "getBoolean", "getBooleanValue", "getIntValue", "getLongValue", "getOrDefault" -> {
                Value v = first == null ? null : m.get(first);
                return v != null ? v : name.equals("getOrDefault") && a.size() == 2 ? a.get(1) : Value.Lit.NULL;
            }
            case "containsKey" -> {
                return Value.Lit.of(first != null && m.get(first) != null);
            }
            case "putAll" -> {
                if (first instanceof Value.MapV more) {
                    more.entries.forEach(e -> m.put(e.getKey(), e.getValue()));
                }
                return Value.Lit.NULL;
            }
            case "values" -> {
                Value.ListV l = new Value.ListV();
                m.entries.forEach(e -> l.items.add(e.getValue()));
                return l;
            }
            case "keySet" -> {
                Value.ListV l = new Value.ListV();
                m.entries.forEach(e -> l.items.add(e.getKey()));
                return l;
            }
            case "isEmpty" -> {
                return Value.Lit.of(m.entries.isEmpty());
            }
            case "size" -> {
                return Value.Lit.of(m.entries.size());
            }
            default -> {
                return new Value.Unknown("map " + name);
            }
        }
    }

    static Value literal(Value.Lit l, String name, List<Value> a) {
        Value first = a.isEmpty() ? null : a.getFirst();
        return switch (name) {
            case "equals", "equalsIgnoreCase" -> first instanceof Value.Lit o && l.value() != null && o.value() != null
                    ? Value.Lit.of(name.equals("equals") ? String.valueOf(l.value()).equals(String.valueOf(o.value()))
                    : String.valueOf(l.value()).equalsIgnoreCase(String.valueOf(o.value())))
                    : new Value.Unknown("equals");
            case "toString", "strip", "trim", "intern" -> l.value() == null ? new Value.Unknown("null") : new Value.Lit(String.valueOf(l.value()).strip(), l.from());
            case "isEmpty", "isBlank" -> l.value() instanceof String s ? Value.Lit.of(s.isBlank()) : new Value.Unknown(name);
            case "intValue", "longValue" -> l.value() instanceof Number ? l : new Value.Unknown(name);
            case "booleanValue" -> l.value() instanceof Boolean ? l : new Value.Unknown(name);
            default -> new Value.Unknown("literal " + name);
        };
    }

    /** A call on a library object (or a Lombok builder of a project class). */
    static Value object(Tracer t, Value.Obj o, String name, List<Value> a) {
        Value first = a.isEmpty() ? null : a.getFirst();
        if (o.type.startsWith(BUILDER)) {
            if (name.equals("build") && a.isEmpty()) {
                return t.build(o.type.substring(BUILDER.length()), o);
            }
            if (a.size() == 1) {
                o.fields.put(name, first);
            }
            return o;
        }
        if (o.type.equals("Scheduler")) {
            t.record(o.type, name, a);
            return Value.Lit.NULL;
        }
        if (o.type.endsWith("Builder") && !o.type.endsWith("FactoryBean")) {   // a library's fluent builder
            if (name.equals("build") && a.isEmpty()) {
                Value.Obj built = new Value.Obj(o.type.substring(0, o.type.length() - "Builder".length()));
                built.fields.putAll(o.fields);
                t.made(built);
                return built;
            }
            o.fields.put(name, first == null ? Value.Lit.of(true) : first);
            return o;
        }
        if (name.equals("getObject") && a.isEmpty()) {
            return o;   // a factory bean: what it makes is what it was told
        }
        if (name.startsWith("set") && name.length() > 3 && a.size() == 1) {
            o.fields.put(Tracer.decap(name.substring(3)), first);
            return Value.Lit.NULL;
        }
        String prop = Tracer.property(name);
        if (prop != null && a.isEmpty()) {
            Value v = o.fields.get(prop);
            if (v != null) {
                return v;
            }
            if (prop.endsWith("DataMap") || prop.equals("jobData")) {
                Value.MapV m = new Value.MapV();
                o.fields.put(prop, m);
                return m;
            }
            if (prop.equals("key")) {
                Value.Obj k = new Value.Obj("Key");
                k.fields.put("of", o);
                o.fields.put(prop, k);
                return k;
            }
            if (prop.equals("scheduler")) {
                Value.Obj s = new Value.Obj("Scheduler");
                o.fields.put(prop, s);
                return s;
            }
            return new Value.Unknown(o.simpleType() + "." + name);
        }
        return new Value.Unknown(o.simpleType() + "." + name);
    }
}
