package dev.logast.trace;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * #217: what the tracer knows of a value: a literal (with the configuration key it was bound from), an enum constant,
 * a class literal, an object (a project class's, a library's, or one bound from the configuration) with its fields, a
 * list, a map, an {@code Optional}, a function (a lambda or method reference), or nothing known.
 */
public sealed interface Value permits Value.Lit, Value.EnumConst, Value.ClassRef, Value.Obj, Value.ListV, Value.MapV,
        Value.Opt, Value.Fn, Value.Unknown {

    /** @param from the configuration key it was bound from; null when written in the code */
    record Lit(Object value, String from) implements Value {
        public static final Lit NULL = new Lit(null, null);

        public static Lit of(Object v) {
            return new Lit(v, null);
        }

        public String text() {
            return value == null ? null : String.valueOf(value);
        }
    }

    /** @param type the enum's fully qualified name */
    record EnumConst(String type, String name) implements Value {
        public String simple() {
            String t = type.substring(Math.max(type.lastIndexOf('.'), type.lastIndexOf('$')) + 1);
            return t + "." + name;
        }
    }

    record ClassRef(String fqn) implements Value {
    }

    /** No value the tracer can know (a runtime value, a library's internals); {@code why} says which. */
    record Unknown(String why) implements Value {
        public Unknown {
            Diagnostics.unknown(why);
        }
    }

    /** Present or not ({@code inner} null: empty). */
    record Opt(Value inner) implements Value {
    }

    /** A lambda or a method reference, with what it closes over. */
    record Fn(org.openrewrite.java.tree.J tree, Object closure) implements Value {
    }

    /** An object: mutable, as the code mutates it (setters, builders). */
    final class Obj implements Value {
        /** a project class's fully qualified name, else a library type's simple name */
        public final String type;
        public final Map<String, Value> fields = new LinkedHashMap<>();
        public final List<Value> args = new ArrayList<>();
        /** the configuration prefix it was bound from, when it was */
        public String from;
        /** the {@code @Bean} method that made it, when one did */
        public String bean;

        public Obj(String type) {
            this.type = type;
        }

        public String simpleType() {
            return type.substring(Math.max(type.lastIndexOf('.'), type.lastIndexOf('$')) + 1);
        }

        @Override
        public String toString() {
            return "Obj[" + simpleType() + (from == null ? "" : " " + from) + "]";
        }
    }

    /** A list: mutable, in order ({@code pollFirst()} takes from it). */
    final class ListV implements Value {
        public final List<Value> items = new ArrayList<>();
        public String from;

        @Override
        public String toString() {
            return "List" + items;
        }
    }

    /** A map whose keys the tracer can compare (literals, enum constants, class literals); others by identity. */
    final class MapV implements Value {
        public final List<Map.Entry<Value, Value>> entries = new ArrayList<>();

        public Value get(Value key) {
            for (var e : entries) {
                if (same(e.getKey(), key)) {
                    return e.getValue();
                }
            }
            return null;
        }

        public void put(Value key, Value value) {
            for (int i = 0; i < entries.size(); i++) {
                if (same(entries.get(i).getKey(), key)) {
                    entries.set(i, Map.entry(entries.get(i).getKey(), value));
                    return;
                }
            }
            entries.add(Map.entry(key, value));
        }
    }

    /** The same key: equal literals, the same enum constant (by its simple type name too), the same class; else identity. */
    static boolean same(Value a, Value b) {
        if (a instanceof Lit x && b instanceof Lit y) {
            return Objects.equals(x.value(), y.value());
        }
        if (a instanceof EnumConst x && b instanceof EnumConst y) {
            return x.name().equals(y.name()) && x.simple().equals(y.simple());
        }
        if (a instanceof ClassRef x && b instanceof ClassRef y) {
            return x.fqn().equals(y.fqn());
        }
        return a == b;
    }
}
