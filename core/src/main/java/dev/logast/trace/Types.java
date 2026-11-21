package dev.logast.trace;

import dev.logast.graph.CodeIndex;
import dev.logast.model.ClassInfo;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.Statement;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * #217: the project's types as the source names them: a type written in a class ({@code List<Plan>},
 * {@code Outer.Inner}) resolved to the project class it means (nested, same package, imported, else the only one so
 * named), and the fields a class declares and inherits. Works without the classpath: Lombok and library types need not
 * resolve.
 */
public final class Types {
    private final CodeIndex index;
    private final Map<String, List<ClassInfo>> bySimple = new HashMap<>();
    private final Map<String, List<Field>> fields = new HashMap<>();

    /** A field as declared: its type as written. */
    public record Field(String name, String type, J.VariableDeclarations decl, J.VariableDeclarations.NamedVariable var, ClassInfo owner) {
        public boolean isStatic() {
            return decl.hasModifier(J.Modifier.Type.Static);
        }

        public boolean isFinal() {
            return decl.hasModifier(J.Modifier.Type.Final);
        }

        public boolean annotated(String simple) {
            return decl.getLeadingAnnotations().stream().anyMatch(a -> a.getSimpleName().equals(simple));
        }
    }

    public Types(CodeIndex index) {
        this.index = index;
        for (ClassInfo c : index.classes()) {
            bySimple.computeIfAbsent(c.simpleName(), k -> new ArrayList<>()).add(c);
        }
    }

    public CodeIndex index() {
        return index;
    }

    /** The raw type: {@code java.util.List<Plan>} → {@code List}. */
    public static String raw(String type) {
        String t = type.strip();
        int lt = t.indexOf('<');
        if (lt >= 0) {
            t = t.substring(0, lt);
        }
        t = t.replace("[]", "").strip();
        return t.substring(t.lastIndexOf('.') + 1);
    }

    /** The top-level type arguments: {@code Map<String, List<X>>} → {@code [String, List<X>]}. */
    public static List<String> args(String type) {
        List<String> out = new ArrayList<>();
        int lt = type.indexOf('<');
        int gt = type.lastIndexOf('>');
        if (lt < 0 || gt < lt) {
            return out;
        }
        String inner = type.substring(lt + 1, gt);
        int depth = 0;
        StringBuilder cur = new StringBuilder();
        for (char ch : inner.toCharArray()) {
            if (ch == '<') {
                depth++;
            } else if (ch == '>') {
                depth--;
            }
            if (ch == ',' && depth == 0) {
                out.add(cur.toString().strip());
                cur.setLength(0);
            } else {
                cur.append(ch);
            }
        }
        if (!cur.isEmpty()) {
            out.add(cur.toString().strip());
        }
        return out;
    }

    /** The project class a type written in {@code context} means; null for a library type (or none so named). */
    public ClassInfo resolve(String type, ClassInfo context) {
        if (type == null) {
            return null;
        }
        String t = type.strip();
        int lt = t.indexOf('<');
        if (lt >= 0) {
            t = t.substring(0, lt);
        }
        t = t.replace("[]", "").strip();
        ClassInfo exact = index.clazz(t);
        if (exact != null) {
            return exact;
        }
        String simple = t.substring(t.lastIndexOf('.') + 1);
        List<ClassInfo> named = bySimple.getOrDefault(simple, List.of());
        if (named.isEmpty()) {
            return null;
        }
        if (named.size() == 1) {
            return named.getFirst();
        }
        if (context != null) {
            String ctx = context.fqn();
            for (String outer = ctx; !outer.isEmpty(); ) {   // nested in the context or one of its outer classes
                for (ClassInfo c : named) {
                    if (c.fqn().equals(outer + "$" + simple) || c.fqn().equals(outer + "." + simple)) {
                        return c;
                    }
                }
                int cut = Math.max(outer.lastIndexOf('$'), outer.lastIndexOf('.'));
                outer = cut < 0 ? "" : outer.substring(0, cut);
            }
            String text = context.file().text();
            for (ClassInfo c : named) {
                if (text.contains("import " + c.fqn().replace('$', '.') + ";")) {
                    return c;
                }
            }
        }
        return null;
    }

    public boolean isEnum(ClassInfo c) {
        return c != null && c.decl().getKind() == J.ClassDeclaration.Kind.Type.Enum;
    }

    public static boolean annotated(ClassInfo c, String simple) {
        return c.decl().getLeadingAnnotations().stream().anyMatch(a -> a.getSimpleName().equals(simple));
    }

    /** The fields of {@code c} (not static ones unless asked), its own first, then its source supertypes'. */
    public List<Field> fields(ClassInfo c) {
        return fields.computeIfAbsent(c.fqn(), k -> {
            List<Field> out = new ArrayList<>(declared(c));
            for (ClassInfo s : index.sourceSupertypes(c)) {
                out.addAll(declared(s));
            }
            if (out.size() == declared(c).size()) {   // no types to say: the superclass as written
                ClassInfo sup = c.decl().getExtends() == null ? null : resolve(c.decl().getExtends().printTrimmed(), c);
                Set<String> seen = new LinkedHashSet<>(List.of(c.fqn()));
                while (sup != null && seen.add(sup.fqn())) {
                    out.addAll(declared(sup));
                    sup = sup.decl().getExtends() == null ? null : resolve(sup.decl().getExtends().printTrimmed(), sup);
                }
            }
            return out;
        });
    }

    /** The fields {@code c} itself declares, static ones included, in order. */
    public List<Field> declared(ClassInfo c) {
        List<Field> out = new ArrayList<>();
        if (c.decl().getBody() == null) {
            return out;
        }
        for (Statement s : c.decl().getBody().getStatements()) {
            if (s instanceof J.VariableDeclarations vd) {
                String type = vd.getTypeExpression() == null ? "" : vd.getTypeExpression().printTrimmed();
                for (J.VariableDeclarations.NamedVariable v : vd.getVariables()) {
                    out.add(new Field(v.getSimpleName(), type, vd, v, c));
                }
            }
        }
        return out;
    }

    public Field field(ClassInfo c, String name) {
        for (Field f : fields(c)) {
            if (f.name().equals(name)) {
                return f;
            }
        }
        return null;
    }

    /** Every project class that is {@code c} or has it among its supertypes (by type, else as the source writes it). */
    public boolean isA(ClassInfo c, ClassInfo of) {
        if (c.fqn().equals(of.fqn())) {
            return true;
        }
        for (ClassInfo s : index.sourceSupertypes(c)) {
            if (s.fqn().equals(of.fqn())) {
                return true;
            }
        }
        List<J> parents = new ArrayList<>();
        if (c.decl().getExtends() != null) {
            parents.add(c.decl().getExtends());
        }
        if (c.decl().getImplements() != null) {
            parents.addAll(c.decl().getImplements());
        }
        for (J p : parents) {
            ClassInfo r = resolve(p.printTrimmed(), c);
            if (r != null && r != c && isA(r, of)) {
                return true;
            }
        }
        return false;
    }

    /** The simple names of the types {@code c} extends or implements, as written (library ones too). */
    public static List<String> parents(ClassInfo c) {
        List<String> out = new ArrayList<>();
        if (c.decl().getExtends() != null) {
            out.add(raw(c.decl().getExtends().printTrimmed()));
        }
        if (c.decl().getImplements() != null) {
            c.decl().getImplements().forEach(i -> out.add(raw(i.printTrimmed())));
        }
        return out;
    }
}
