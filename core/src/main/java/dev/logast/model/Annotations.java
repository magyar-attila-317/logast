package dev.logast.model;

import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.TypeUtils;

import java.util.List;
import java.util.Optional;

public final class Annotations {
    private Annotations() {
    }

    /** Matches by fully qualified name, falling back to the simple name when the type is unresolved. */
    public static boolean is(J.Annotation a, String fqn) {
        JavaType.FullyQualified t = TypeUtils.asFullyQualified(a.getType());
        if (t != null && !(a.getType() instanceof JavaType.Unknown)) {
            return t.getFullyQualifiedName().equals(fqn);
        }
        String simple = fqn.substring(fqn.lastIndexOf('.') + 1);
        return a.getSimpleName().equals(simple);
    }

    /** Value of attribute {@code name} (or the implicit {@code value}) as source-level string, if it is a literal. */
    public static Optional<String> stringAttribute(J.Annotation a, String... names) {
        List<Expression> args = a.getArguments();
        if (args == null) {
            return Optional.empty();
        }
        for (Expression arg : args) {
            if (arg instanceof J.Assignment as && as.getVariable() instanceof J.Identifier id) {
                for (String n : names) {
                    if (id.getSimpleName().equals(n)) {
                        return literal(as.getAssignment());
                    }
                }
            } else if (!(arg instanceof J.Assignment) && List.of(names).contains("value")) {
                return literal(arg);
            }
        }
        return Optional.empty();
    }

    /** Text of attribute {@code name} as written, e.g. {@code RequestMethod.GET}. */
    public static Optional<String> rawAttribute(J.Annotation a, String name) {
        if (a.getArguments() == null) {
            return Optional.empty();
        }
        for (Expression arg : a.getArguments()) {
            if (arg instanceof J.Assignment as && as.getVariable() instanceof J.Identifier id
                    && id.getSimpleName().equals(name)) {
                return Optional.of(as.getAssignment().printTrimmed());
            }
            if (!(arg instanceof J.Assignment) && !(arg instanceof J.Empty) && name.equals("value")) {
                return Optional.of(arg.printTrimmed());   // @EventListener(ApplicationReadyEvent.class)
            }
        }
        return Optional.empty();
    }

    private static Optional<String> literal(Expression e) {
        if (e instanceof J.Literal l && l.getValue() instanceof String s) {
            return Optional.of(s);
        }
        if (e instanceof J.NewArray na && na.getInitializer() != null && !na.getInitializer().isEmpty()) {
            return literal(na.getInitializer().getFirst());
        }
        return Optional.empty();
    }
}
