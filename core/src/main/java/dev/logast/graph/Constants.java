package dev.logast.graph;

import dev.logast.model.ClassInfo;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.Statement;
import org.openrewrite.java.tree.TypeUtils;

/** Compile-time string values: literals and {@code static final String} constants of project classes. */
public final class Constants {
    private Constants() {
    }

    /** {@code "orderId"}, {@code ORDER_ID}, {@code MdcKeys.ORDER_ID} → the string; null when not a known constant. */
    public static String string(CodeIndex index, Expression e) {
        if (e instanceof J.Literal l) {
            return l.getValue() instanceof String s ? s : null;
        }
        JavaType.Variable field = e instanceof J.Identifier id ? id.getFieldType()
                : e instanceof J.FieldAccess fa ? fa.getName().getFieldType() : null;
        if (field == null || !(field.getOwner() instanceof JavaType.FullyQualified owner) || !TypeUtils.isString(field.getType())) {
            return null;
        }
        ClassInfo c = index.clazz(owner.getFullyQualifiedName());
        if (c == null) {
            return null;
        }
        for (Statement s : c.decl().getBody().getStatements()) {
            if (s instanceof J.VariableDeclarations vd) {
                for (J.VariableDeclarations.NamedVariable v : vd.getVariables()) {
                    if (v.getSimpleName().equals(field.getName()) && v.getInitializer() != null) {
                        return v.getInitializer() == e ? null : string(index, v.getInitializer());
                    }
                }
            }
        }
        return null;
    }
}
