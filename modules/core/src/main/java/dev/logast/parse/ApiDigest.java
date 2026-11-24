package dev.logast.parse;

import org.openrewrite.InMemoryExecutionContext;
import org.openrewrite.SourceFile;
import org.openrewrite.java.JavaParser;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.Statement;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

/**
 * What other files see of a Java file, from its syntax alone (no types needed): its classes with their modifiers,
 * annotations, supertypes and type parameters; their non-private methods and constructors (modifiers, annotations,
 * return type, name, parameters, throws); and their fields except private static ones. Instance fields count even when
 * private: Lombok's constructors and accessors are made from them. A body edit (a log line, an MDC scope, a private
 * helper, a logger) keeps the digest.
 *
 * <p>#308: an incremental round keeps the other files' trees only while it holds. The apply's test check (#279) compiles
 * the tests of a class only when its digest changed: otherwise no test can have stopped compiling.
 */
public final class ApiDigest {
    private ApiDigest() {
    }

    public static String of(J.CompilationUnit cu) {
        StringBuilder sb = new StringBuilder();
        for (J.ClassDeclaration c : cu.getClasses()) {
            of(c, sb);
        }
        return sb.toString();
    }

    /** The digest of a source text; null when it does not parse. */
    public static String of(String text) {
        List<SourceFile> out = JavaParser.fromJavaVersion().build()
                .parseInputs(List.of(new org.openrewrite.Parser.Input(Path.of("Digest.java"),
                        () -> new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)))), null, new InMemoryExecutionContext())
                .toList();
        return out.size() == 1 && out.getFirst() instanceof J.CompilationUnit cu ? of(cu) : null;
    }

    private static void of(J.ClassDeclaration c, StringBuilder sb) {
        sb.append("class ").append(c.getSimpleName()).append(' ').append(c.getKind()).append(mods(c.getModifiers()));
        c.getLeadingAnnotations().forEach(a -> sb.append(" @").append(a.printTrimmed()));
        if (c.getTypeParameters() != null) {
            c.getTypeParameters().forEach(t -> sb.append(" <").append(t.printTrimmed()).append('>'));
        }
        if (c.getExtends() != null) {
            sb.append(" extends ").append(c.getExtends().printTrimmed());
        }
        if (c.getImplements() != null) {
            c.getImplements().forEach(i -> sb.append(" implements ").append(i.printTrimmed()));
        }
        if (c.getPrimaryConstructor() != null) {   // a record's components
            c.getPrimaryConstructor().forEach(p -> sb.append(" component ").append(p.printTrimmed()));
        }
        sb.append('\n');
        for (Statement s : c.getBody().getStatements()) {
            if (s instanceof J.MethodDeclaration md) {
                if (md.hasModifier(J.Modifier.Type.Private)) {
                    continue;
                }
                sb.append("  method").append(mods(md.getModifiers()));
                md.getLeadingAnnotations().forEach(a -> sb.append(" @").append(a.printTrimmed()));
                if (md.getTypeParameters() != null) {
                    md.getTypeParameters().forEach(t -> sb.append(" <").append(t.printTrimmed()).append('>'));
                }
                sb.append(' ').append(md.getReturnTypeExpression() == null ? "" : md.getReturnTypeExpression().printTrimmed())
                        .append(' ').append(md.getSimpleName()).append('(');
                md.getParameters().forEach(p -> sb.append(p instanceof J.Empty ? "" : p.printTrimmed()).append(','));
                sb.append(')');
                if (md.getThrows() != null) {
                    md.getThrows().forEach(t -> sb.append(" throws ").append(t.printTrimmed()));
                }
                sb.append('\n');
            } else if (s instanceof J.VariableDeclarations vd) {
                boolean isStatic = vd.hasModifier(J.Modifier.Type.Static);
                if (vd.hasModifier(J.Modifier.Type.Private) && isStatic) {
                    continue;   // a logger, a constant: no other file sees it, no Lombok constructor takes it
                }
                sb.append("  field").append(mods(vd.getModifiers()));
                vd.getLeadingAnnotations().forEach(a -> sb.append(" @").append(a.printTrimmed()));
                sb.append(' ').append(vd.getTypeExpression() == null ? "?" : vd.getTypeExpression().printTrimmed())
                        .append(vd.getVariables().stream().map(v -> " " + v.getSimpleName()
                                // an instance field's initializer decides whether Lombok's required-args constructor takes it
                                + (!isStatic && v.getInitializer() != null ? "=" : "")).toList()).append('\n');
            } else if (s instanceof J.ClassDeclaration inner) {
                of(inner, sb);
            }
        }
    }

    private static String mods(List<J.Modifier> modifiers) {
        StringBuilder sb = new StringBuilder();
        modifiers.forEach(m -> sb.append(' ').append(m.getType().name().toLowerCase(java.util.Locale.ROOT)));
        return sb.toString();
    }
}
