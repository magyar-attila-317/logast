package dev.logast.model;

import dev.logast.parse.SourceFileInfo;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;

public record ClassInfo(String fqn, J.ClassDeclaration decl, JavaType.FullyQualified type, SourceFileInfo file) {
    public boolean isInterface() {
        return decl.getKind() == J.ClassDeclaration.Kind.Type.Interface;
    }

    public String simpleName() {
        return decl.getSimpleName();
    }
}
