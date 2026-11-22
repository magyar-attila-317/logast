package dev.logast.export;

import dev.logast.graph.CodeIndex;
import dev.logast.graph.Constants;
import dev.logast.model.MethodInfo;
import dev.logast.parse.Pos;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.TypeUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * #179: Flyway runs the service's own code makes ({@code Flyway.configure()…locations(…)}), beside the one Spring Boot
 * configures: a multi-tenant service migrates each tenant's schema itself. Per method: the constant locations, and the
 * schema when constant (null when chosen at runtime: per tenant).
 */
public final class ProgrammaticFlyway {
    private static final String CONFIG = "org.flywaydb.core.api.configuration.FluentConfiguration";

    /** A location the code migrates, the schema it names (null: chosen at runtime), and where. */
    public record CodeRun(String location, String schema, String file, int line) {
    }

    private ProgrammaticFlyway() {
    }

    public static List<CodeRun> find(CodeIndex index) {
        List<CodeRun> out = new ArrayList<>();
        for (MethodInfo m : index.methods()) {
            if (!m.hasBody()) {
                continue;
            }
            List<String> locations = new ArrayList<>();
            String[] schema = {null};
            boolean[] runtimeSchema = {false};
            int[] line = {-1};
            new JavaIsoVisitor<Integer>() {
                @Override
                public J.MethodInvocation visitMethodInvocation(J.MethodInvocation mi, Integer p) {
                    J.MethodInvocation v = super.visitMethodInvocation(mi, p);
                    JavaType.Method mt = mi.getMethodType();
                    if (mt == null || !TypeUtils.isOfClassType(mt.getDeclaringType(), CONFIG)) {
                        return v;
                    }
                    switch (mi.getSimpleName()) {
                        case "locations" -> {
                            for (Expression a : mi.getArguments()) {
                                String c = Constants.concatenated(index, a);
                                if (c != null) {
                                    locations.add(c);
                                    if (line[0] < 0) {
                                        line[0] = Pos.line(mi.getName());
                                    }
                                }
                            }
                        }
                        case "schemas", "defaultSchema" -> {
                            if (!mi.getArguments().isEmpty()) {
                                String c = Constants.concatenated(index, mi.getArguments().getFirst());
                                if (c != null) {
                                    schema[0] = c;
                                } else {
                                    runtimeSchema[0] = true;
                                }
                            }
                        }
                        default -> {
                        }
                    }
                    return v;
                }
            }.visit(m.decl().getBody(), 0);
            for (String loc : locations) {
                out.add(new CodeRun(loc, runtimeSchema[0] ? null : schema[0], m.file().relPath(), line[0]));
            }
        }
        return out;
    }
}
