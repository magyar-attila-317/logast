package dev.logast.graph;

import org.openrewrite.java.tree.JavaType;

import java.util.stream.Collectors;

public final class Types {
    private Types() {
    }

    /** Stable method key: {@code com.x.Owner#name(java.lang.String,int)} with erased parameter types. */
    public static String key(JavaType.Method mt) {
        return fqn(mt.getDeclaringType()) + "#" + mt.getName() + "("
                + mt.getParameterTypes().stream().map(Types::erasure).collect(Collectors.joining(",")) + ")";
    }

    public static String fqn(JavaType t) {
        if (t instanceof JavaType.Parameterized p) {
            return p.getType().getFullyQualifiedName();
        }
        if (t instanceof JavaType.FullyQualified fq) {
            return fq.getFullyQualifiedName();
        }
        return t == null ? "?" : t.toString();
    }

    public static String erasure(JavaType t) {
        if (t instanceof JavaType.Parameterized p) {
            return p.getType().getFullyQualifiedName();
        }
        if (t instanceof JavaType.FullyQualified fq) {
            return fq.getFullyQualifiedName();
        }
        if (t instanceof JavaType.Primitive p) {
            return p.getKeyword();
        }
        if (t instanceof JavaType.Array a) {
            return erasure(a.getElemType()) + "[]";
        }
        if (t instanceof JavaType.GenericTypeVariable g) {
            return g.getBounds().isEmpty() ? "java.lang.Object" : erasure(g.getBounds().getFirst());
        }
        return "?";
    }

    public static String simple(String fqn) {
        int i = Math.max(fqn.lastIndexOf('.'), fqn.lastIndexOf('$'));
        return fqn.substring(i + 1);
    }

    /** Short display form of a type, e.g. {@code List<OrderLine>} becomes {@code List}. */
    public static String display(JavaType t) {
        if (t instanceof JavaType.Primitive p) {
            return p.getKeyword();
        }
        if (t instanceof JavaType.Array a) {
            return display(a.getElemType()) + "[]";
        }
        return simple(erasure(t));
    }
}
