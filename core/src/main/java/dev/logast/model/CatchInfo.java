package dev.logast.model;

import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;

import java.util.ArrayList;
import java.util.List;

/** Facts about one catch clause, filled in while the method body is visited. */
public final class CatchInfo {
    public final J.Try tryStatement;
    public final J.Try.Catch clause;
    public final String param;
    public final List<JavaType> types;
    public final List<LogCall> logs = new ArrayList<>();
    public final List<J.Throw> throwsList = new ArrayList<>();
    public boolean rethrowsCause;
    public boolean passesException;
    /** Restores the interrupt flag ({@code Thread.currentThread().interrupt()}). */
    public boolean restoresInterrupt;

    public CatchInfo(J.Try tryStatement, J.Try.Catch clause, String param, List<JavaType> types) {
        this.tryStatement = tryStatement;
        this.clause = clause;
        this.param = param;
        this.types = types;
    }

    public boolean swallows() {
        return logs.isEmpty() && throwsList.isEmpty() && !passesException && !restoresInterrupt;
    }

    public String typeNames() {
        return String.join(" | ", types.stream().map(t -> {
            String s = String.valueOf(t);
            return s.substring(s.lastIndexOf('.') + 1);
        }).toList());
    }
}
