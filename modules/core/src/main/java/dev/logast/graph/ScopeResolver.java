package dev.logast.graph;

import dev.logast.model.MethodInfo;
import dev.logast.parse.Pos;
import org.openrewrite.Cursor;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.Statement;
import org.openrewrite.java.tree.TypeUtils;

import java.util.LinkedHashMap;
import java.util.Map;

/** Variables visible at a source offset inside a method: fields, parameters, then locals declared before it. */
public final class ScopeResolver {
    private ScopeResolver() {
    }

    public static Map<String, JavaType> varsAt(MethodInfo m, int offset) {
        return varsAt(m, offset, true);
    }

    public static Map<String, JavaType> varsAt(MethodInfo m, int offset, boolean includeFields) {
        Map<String, JavaType> vars = new LinkedHashMap<>();
        for (Statement s : includeFields ? m.owner().decl().getBody().getStatements() : java.util.List.<Statement>of()) {
            if (s instanceof J.VariableDeclarations vd) {
                for (J.VariableDeclarations.NamedVariable v : vd.getVariables()) {
                    if (!TypeUtils.isAssignableTo("org.slf4j.Logger", v.getType())) {
                        vars.put(v.getSimpleName(), v.getType());
                    }
                }
            }
        }
        new JavaIsoVisitor<Map<String, JavaType>>() {
            @Override
            public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration c, Map<String, JavaType> p) {
                return c;
            }

            @Override
            public J.VariableDeclarations.NamedVariable visitVariable(J.VariableDeclarations.NamedVariable v,
                                                                      Map<String, JavaType> p) {
                J container = container(getCursor());
                boolean isParam = container instanceof J.MethodDeclaration;
                if (container != null && (isParam || Pos.end(v) <= offset)
                        && Pos.start(container) <= offset && offset < Pos.end(container)) {
                    p.put(v.getSimpleName(), v.getType());
                }
                return super.visitVariable(v, p);
            }
        }.visit(m.decl(), vars, new Cursor(null, Cursor.ROOT_VALUE));
        return vars;
    }

    private static J container(Cursor cursor) {
        Cursor c = cursor.getParent();
        while (c != null) {
            Object v = c.getValue();
            if (v instanceof J.Block || v instanceof J.Lambda || v instanceof J.Try || v instanceof J.Try.Catch
                    || v instanceof J.ForEachLoop || v instanceof J.ForLoop || v instanceof J.MethodDeclaration) {
                return (J) v;
            }
            c = c.getParent();
        }
        return null;
    }
}
