package dev.logast.graph;

import dev.logast.model.ClassInfo;
import dev.logast.model.MethodInfo;
import dev.logast.parse.ParsedProject;
import dev.logast.parse.SourceFileInfo;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.Flag;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;

import java.util.*;

/** All source classes and methods, the subtype relation, and per-method facts. */
public final class CodeIndex {
    private final Map<String, ClassInfo> classes = new LinkedHashMap<>();
    private final Map<String, MethodInfo> methods = new LinkedHashMap<>();
    private final Map<String, List<MethodInfo>> byClass = new HashMap<>();
    private final Map<String, Set<String>> subtypes = new HashMap<>();
    private final ParsedProject project;

    private CodeIndex(ParsedProject project) {
        this.project = project;
    }

    public static CodeIndex build(ParsedProject project) {
        CodeIndex index = new CodeIndex(project);
        for (SourceFileInfo file : project.files()) {
            index.collect(file);
        }
        for (ClassInfo c : index.classes.values()) {
            index.registerAncestors(c.fqn(), c.type(), new HashSet<>());
        }
        for (MethodInfo m : index.methods.values()) {
            m.facts(FactsExtractor.extract(m));
        }
        return index;
    }

    private void collect(SourceFileInfo file) {
        new JavaIsoVisitor<Integer>() {
            private final Deque<ClassInfo> stack = new ArrayDeque<>();

            @Override
            public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration c, Integer p) {
                if (c.getType() == null) {
                    return super.visitClassDeclaration(c, p);
                }
                ClassInfo info = new ClassInfo(c.getType().getFullyQualifiedName(), c, c.getType(), file);
                classes.put(info.fqn(), info);
                stack.push(info);
                J.ClassDeclaration r = super.visitClassDeclaration(c, p);
                stack.pop();
                return r;
            }

            @Override
            public J.MethodDeclaration visitMethodDeclaration(J.MethodDeclaration md, Integer p) {
                JavaType.Method mt = md.getMethodType();
                ClassInfo owner = stack.peek();
                // Methods of anonymous classes have a different declaring type than the enclosing class: skip them.
                if (mt != null && owner != null && Types.fqn(mt.getDeclaringType()).equals(owner.fqn())) {
                    MethodInfo m = new MethodInfo(Types.key(mt), md, mt, owner);
                    methods.put(m.key(), m);
                    byClass.computeIfAbsent(owner.fqn(), k -> new ArrayList<>()).add(m);
                }
                return super.visitMethodDeclaration(md, p);
            }
        }.visit(file.cu(), 0);
    }

    private void registerAncestors(String sub, JavaType.FullyQualified t, Set<String> seen) {
        if (t == null) {
            return;
        }
        List<JavaType.FullyQualified> parents = new ArrayList<>(t.getInterfaces());
        if (t.getSupertype() != null) {
            parents.add(t.getSupertype());
        }
        for (JavaType.FullyQualified parent : parents) {
            String fqn = parent.getFullyQualifiedName();
            if (seen.add(fqn)) {
                subtypes.computeIfAbsent(fqn, k -> new TreeSet<>()).add(sub);
                registerAncestors(sub, parent, seen);
            }
        }
    }

    /**
     * Source methods a call may dispatch to: the declared method (when it has a body) plus every
     * override in source subtypes. Calls to library types resolve to nothing.
     */
    public List<MethodInfo> resolve(JavaType.Method mt) {
        String owner = Types.fqn(mt.getDeclaringType());
        if (!classes.containsKey(owner)) {
            return List.of();
        }
        List<MethodInfo> out = new ArrayList<>();
        MethodInfo direct = find(owner, mt);
        if (direct != null && direct.hasBody()) {
            out.add(direct);
        }
        boolean virtual = !mt.hasFlags(Flag.Static) && !mt.hasFlags(Flag.Private) && !"<constructor>".equals(mt.getName());
        if (virtual) {
            for (String sub : subtypes.getOrDefault(owner, Set.of())) {
                MethodInfo o = find(sub, mt);
                if (o != null && o.hasBody() && !out.contains(o)) {
                    out.add(o);
                }
            }
        }
        return out;
    }

    /** Exact erased signature first, then a unique same-name/same-arity method (generic signatures). */
    private MethodInfo find(String classFqn, JavaType.Method mt) {
        String exact = classFqn + Types.key(mt).substring(Types.fqn(mt.getDeclaringType()).length());
        MethodInfo m = methods.get(exact);
        if (m != null) {
            return m;
        }
        List<MethodInfo> candidates = byClass.getOrDefault(classFqn, List.of()).stream()
                .filter(c -> c.name().equals(mt.getName())
                        && c.type().getParameterTypes().size() == mt.getParameterTypes().size())
                .toList();
        return candidates.size() == 1 ? candidates.getFirst() : null;
    }

    public Collection<MethodInfo> methods() {
        return methods.values();
    }

    public MethodInfo method(String key) {
        return methods.get(key);
    }

    public Collection<ClassInfo> classes() {
        return classes.values();
    }

    public ClassInfo clazz(String fqn) {
        return classes.get(fqn);
    }

    public List<MethodInfo> methodsOf(String classFqn) {
        return byClass.getOrDefault(classFqn, List.of());
    }

    public ParsedProject project() {
        return project;
    }
}
