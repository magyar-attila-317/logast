package dev.logast.export;

import dev.logast.Analysis;
import dev.logast.facts.Edge;
import dev.logast.facts.Evidence;
import dev.logast.facts.FactSink;
import dev.logast.facts.Node;
import dev.logast.graph.CallGraph;
import dev.logast.model.ClassInfo;
import dev.logast.model.MethodInfo;
import dev.logast.parse.Pos;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * #255: the code's structure as facts ({@code export --code-level}), from what the analysis resolved with types: each
 * class with its whole supertype chain (library types included, as names), each method, the calls the call graph
 * resolved, and which entrypoints reach each class. An external tool that reads code by names can join these by
 * {@code evidence.file} and the class's simple name.
 */
final class CodeStructure {
    private final Analysis a;
    private final String svc;

    CodeStructure(Analysis a, String svc) {
        this.a = a;
        this.svc = svc;
    }

    /** @param reach each entrypoint id (and profile job) → the keys of the methods its runs reach */
    void extract(Map<String, Set<String>> reach, FactSink sink) {
        // how many entrypoints reach each class, to rank what runs through it: a count, not an edge per pair (on a
        // service of 2,300 files those edges were 100,000 lines, two thirds of the facts). Which ones follows from the
        // calls edges and each entrypoint's handler
        Map<String, Set<String>> reachedBy = new TreeMap<>();
        reach.forEach((entry, keys) -> {
            for (String k : keys) {
                MethodInfo m = a.index.method(k);
                if (m != null) {
                    reachedBy.computeIfAbsent(m.owner().fqn(), x -> new TreeSet<>()).add(entry);
                }
            }
        });
        for (ClassInfo c : a.index.classes()) {
            classNode(c, reachedBy.getOrDefault(c.fqn(), Set.of()), sink);
        }
        for (MethodInfo m : a.index.methods()) {
            sink.node(Node.of("method", methodName(m), attrs("line", m.line(), "endLine", Pos.endLine(m.decl())), at(m)));
            for (CallGraph.Edge e : a.graph.out(m)) {
                Map<String, Object> ea = new TreeMap<>();
                if (e.ambiguous()) {
                    ea.put("ambiguous", true);   // the site dispatches to more than one implementation in the code
                }
                if (e.async() != CallGraph.Async.NONE) {
                    ea.put("async", e.async().name());
                }
                sink.edge(Edge.extracted(methodId(m), "calls", methodId(e.to()), ea,
                        Evidence.at(m.file().relPath(), line(Pos.line(e.site().tree())))));
            }
        }
    }

    private void classNode(ClassInfo c, Set<String> reachedBy, FactSink sink) {
        Map<String, Object> attrs = new TreeMap<>();
        attrs.put("reachedByCount", reachedBy.size());
        J.ClassDeclaration.Kind.Type kind = c.decl().getKind();
        attrs.put("kind", kind.name().toLowerCase(Locale.ROOT));
        if (c.decl().hasModifier(J.Modifier.Type.Abstract) && kind == J.ClassDeclaration.Kind.Type.Class) {
            attrs.put("abstract", true);
        }
        boolean[] complete = {true};
        List<String> chain = new ArrayList<>();
        JavaType.FullyQualified t = c.type();
        if (kind != J.ClassDeclaration.Kind.Type.Interface && kind != J.ClassDeclaration.Kind.Type.Annotation && t != null) {
            for (JavaType.FullyQualified s = t.getSupertype(); s != null; s = s.getSupertype()) {
                if (s instanceof JavaType.Unknown) {
                    complete[0] = false;   // a supertype not on the classpath: the chain stops here
                    break;
                }
                chain.add(s.getFullyQualifiedName());
            }
            if (!chain.isEmpty() && !chain.getLast().equals("java.lang.Object")) {
                complete[0] = false;
            }
        }
        attrs.put("superChain", chain);
        Map<String, JavaType.FullyQualified> interfaces = new LinkedHashMap<>();
        if (t != null) {
            interfaces(t, interfaces, complete, new LinkedHashSet<>());
        }
        attrs.put("interfaces", new ArrayList<>(new TreeSet<>(interfaces.keySet())));
        if (!complete[0]) {
            attrs.put("superChainComplete", false);
        }
        long declared = a.index.methodsOf(c.fqn()).stream().filter(m -> !m.decl().isConstructor()).count();
        attrs.put("methodCount", declared);
        if (kind == J.ClassDeclaration.Kind.Type.Interface) {
            // what an implementer has to provide or inherits: the superinterfaces' instance methods, by signature
            Set<String> own = new TreeSet<>();
            a.index.methodsOf(c.fqn()).forEach(m -> own.add(signature(m.type())));
            Set<String> inherited = new TreeSet<>();
            for (JavaType.FullyQualified i : interfaces.values()) {
                for (JavaType.Method m : i.getMethods()) {
                    if (!m.hasFlags(org.openrewrite.java.tree.Flag.Static) && !m.hasFlags(org.openrewrite.java.tree.Flag.Private)
                            && !own.contains(signature(m))) {
                        inherited.add(signature(m));
                    }
                }
            }
            attrs.put("inheritedMethodCount", inherited.size());
        }
        String id = "class:" + svc + ":" + c.fqn();
        sink.node(Node.of("class", svc + ":" + c.fqn(), attrs, at(c)));
        if (t != null && t.getSupertype() != null && a.index.clazz(t.getSupertype().getFullyQualifiedName()) != null) {
            sink.edge(Edge.extracted(id, "extends", "class:" + svc + ":" + t.getSupertype().getFullyQualifiedName(), null, at(c)));
        }
        if (t != null) {
            for (JavaType.FullyQualified i : t.getInterfaces()) {
                if (a.index.clazz(i.getFullyQualifiedName()) != null) {
                    // an interface extending another is "extends" in Java; a class implementing one is "implements"
                    String kindOf = kind == J.ClassDeclaration.Kind.Type.Interface ? "extends" : "implements";
                    sink.edge(Edge.extracted(id, kindOf, "class:" + svc + ":" + i.getFullyQualifiedName(), null, at(c)));
                }
            }
        }
    }

    /** Every interface {@code t} implements, through its superclasses and superinterfaces, by name. */
    private static void interfaces(JavaType.FullyQualified t, Map<String, JavaType.FullyQualified> out, boolean[] complete, Set<String> seen) {
        if (t == null || !seen.add(t.getFullyQualifiedName())) {
            return;
        }
        for (JavaType.FullyQualified i : t.getInterfaces()) {
            if (i instanceof JavaType.Unknown) {
                complete[0] = false;
                continue;
            }
            out.putIfAbsent(i.getFullyQualifiedName(), i);
            interfaces(i, out, complete, seen);
        }
        JavaType.FullyQualified s = t.getSupertype();
        if (s != null && !(s instanceof JavaType.Unknown)) {
            interfaces(s, out, complete, seen);
        }
    }

    String methodId(MethodInfo m) {
        return "method:" + methodName(m);
    }

    private String methodName(MethodInfo m) {
        return svc + ":" + m.owner().fqn() + "#" + signature(m.type());
    }

    /** {@code name(erased parameter types)}: overloads stay apart, generics do not change the name. */
    static String signature(JavaType.Method mt) {
        List<String> params = new ArrayList<>();
        for (JavaType p : mt.getParameterTypes()) {
            params.add(typeName(p));
        }
        return mt.getName() + "(" + String.join(",", params) + ")";
    }

    private static String typeName(JavaType t) {
        if (t instanceof JavaType.Primitive p) {
            return p.getKeyword();
        }
        if (t instanceof JavaType.Array arr) {
            return typeName(arr.getElemType()) + "[]";
        }
        if (t instanceof JavaType.GenericTypeVariable g) {
            return g.getBounds().isEmpty() ? "java.lang.Object" : typeName(g.getBounds().getFirst());
        }
        if (t instanceof JavaType.FullyQualified fq) {
            return fq.getFullyQualifiedName();
        }
        return String.valueOf(t);
    }

    private static Evidence at(ClassInfo c) {
        return Evidence.at(c.file().relPath(), line(Pos.line(c.decl().getName())));
    }

    private static Evidence at(MethodInfo m) {
        return Evidence.at(m.file().relPath(), line(m.line()));
    }

    private static Integer line(int line) {
        return line > 0 ? line : null;
    }

    private static Map<String, Object> attrs(Object... kv) {
        Map<String, Object> out = new TreeMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            if (kv[i + 1] instanceof Integer n && n > 0) {
                out.put((String) kv[i], n);
            }
        }
        return out;
    }
}
