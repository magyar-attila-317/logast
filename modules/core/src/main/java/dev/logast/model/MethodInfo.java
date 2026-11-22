package dev.logast.model;

import dev.logast.parse.Pos;
import dev.logast.parse.SourceFileInfo;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;

/** A method (or constructor) declared in project sources. */
public final class MethodInfo {
    private final String key;
    private final J.MethodDeclaration decl;
    private final JavaType.Method type;
    private final ClassInfo owner;
    private MethodFacts facts;

    public MethodInfo(String key, J.MethodDeclaration decl, JavaType.Method type, ClassInfo owner) {
        this.key = key;
        this.decl = decl;
        this.type = type;
        this.owner = owner;
    }

    public String key() {
        return key;
    }

    public J.MethodDeclaration decl() {
        return decl;
    }

    public JavaType.Method type() {
        return type;
    }

    public ClassInfo owner() {
        return owner;
    }

    public SourceFileInfo file() {
        return owner.file();
    }

    public MethodFacts facts() {
        return facts;
    }

    public void facts(MethodFacts facts) {
        this.facts = facts;
    }

    public String name() {
        return decl.getSimpleName();
    }

    public boolean hasBody() {
        return decl.getBody() != null;
    }

    /** Line of the method name (annotations above it do not count). */
    public int line() {
        return Pos.line(decl.getName());
    }

    /** Short human label, e.g. {@code OrderService.place}. */
    public String label() {
        return owner.simpleName() + "." + name();
    }

    public boolean hasAnnotation(String fqn) {
        return decl.getLeadingAnnotations().stream().anyMatch(a -> Annotations.is(a, fqn))
                || owner.decl().getLeadingAnnotations().stream().anyMatch(a -> Annotations.is(a, fqn));
    }

    @Override
    public String toString() {
        return key;
    }
}
