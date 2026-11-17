package dev.logast.graph;

import dev.logast.model.Annotations;
import dev.logast.model.ClassInfo;
import org.openrewrite.java.tree.Flag;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.Statement;
import org.openrewrite.java.tree.TypeUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The loggers a class's code can name (#53), nearest first, the way Java resolves the name: the class's own fields and
 * Lombok logger, the non-private fields it inherits, then the same for each enclosing class (static fields only once a
 * static nested class is crossed). Read from the source declarations, so the answer does not depend on Lombok being on
 * the classpath. Only SLF4J loggers ({@code org.slf4j.Logger}, Lombok's {@code @Slf4j} / {@code @XSlf4j}) get SLF4J
 * edits; a class whose nearest logger uses another API gets none.
 */
public final class LoggerScope {
    /**
     * @param name      the field name code uses
     * @param api       null for SLF4J, else the other API ("Log4j2", "java.util.logging", …)
     * @param isStatic  a static field (Lombok's loggers are)
     * @param qualified the field named so that a local variable or parameter of the same name cannot hide it (#83):
     *                  {@code Owner.log} for a static field, {@code this.log} / {@code Outer.this.log} for an instance
     *                  field; null when there is no such name (an anonymous class)
     */
    public record Logger(String name, String api, boolean isStatic, String qualified) {
        Logger(String name, String api, boolean isStatic) {
            this(name, api, isStatic, null);
        }

        public boolean slf4j() {
            return api == null;
        }

        /** Qualified by {@code type}, the class it is seen through; {@code own}: the class whose code names it. */
        Logger qualifiedBy(String type, boolean own) {
            String q = isStatic ? type : own ? "this" : type.isEmpty() ? "" : type + ".this";
            return new Logger(name, api, isStatic, q.isEmpty() ? null : q + "." + name);
        }
    }

    /** Lombok's logger annotations and the API each generates; only these two are SLF4J. */
    private static final Map<String, String> LOMBOK = Map.of(
            "lombok.extern.slf4j.Slf4j", "", "lombok.extern.slf4j.XSlf4j", "",
            "lombok.extern.log4j.Log4j2", "Log4j2", "lombok.extern.log4j.Log4j", "Log4j",
            "lombok.extern.apachecommons.CommonsLog", "Commons Logging", "lombok.extern.java.Log", "java.util.logging",
            "lombok.extern.flogger.Flogger", "Flogger", "lombok.extern.jbosslog.JBossLog", "JBoss Logging",
            "lombok.CustomLog", "a custom logger (@CustomLog)");
    /** Logger types of other APIs, by field type. */
    private static final Map<String, String> OTHER_TYPES = Map.of(
            "java.util.logging.Logger", "java.util.logging", "java.lang.System$Logger", "System.Logger",
            "org.apache.logging.log4j.Logger", "Log4j2", "org.apache.log4j.Logger", "Log4j",
            "org.apache.commons.logging.Log", "Commons Logging", "com.google.common.flogger.FluentLogger", "Flogger",
            "org.jboss.logging.Logger", "JBoss Logging");

    private final CodeIndex index;
    private final Map<String, List<Logger>> cache = new HashMap<>();

    LoggerScope(CodeIndex index) {
        this.index = index;
    }

    /** The logger code in {@code owner} gets by default: the nearest one in scope, or null when there is none. */
    public Logger nearest(ClassInfo owner) {
        List<Logger> all = inScope(owner);
        return all.isEmpty() ? null : all.getFirst();
    }

    /** The logger {@code name} means in {@code owner}'s code (the nearest declaration of that name), or null. */
    public Logger named(ClassInfo owner, String name) {
        return inScope(owner).stream().filter(l -> l.name().equals(name)).findFirst().orElse(null);
    }

    /** Every logger in scope, nearest first. */
    public List<Logger> inScope(ClassInfo owner) {
        return cache.computeIfAbsent(owner.fqn(), k -> {
            List<Logger> out = new ArrayList<>();
            boolean staticOnly = false;
            JavaType.FullyQualified t = owner.type();
            Set<String> seen = new HashSet<>();
            boolean own = true;
            while (t != null && seen.add(t.getFullyQualifiedName())) {
                int from = out.size();
                ClassInfo c = index.clazz(t.getFullyQualifiedName());
                if (c != null) {
                    own(c, staticOnly, out);
                } else {
                    members(t, staticOnly, out);
                }
                inherited(t.getSupertype(), staticOnly, owner, seen, out);
                for (JavaType.FullyQualified i : t.getInterfaces()) {
                    inherited(i, staticOnly, owner, seen, out);   // interface constants
                }
                String type = c != null ? c.simpleName() : simpleName(t);
                for (int i = from; i < out.size(); i++) {
                    out.set(i, out.get(i).qualifiedBy(type, own));
                }
                own = false;
                staticOnly |= c == null ? t.hasFlags(Flag.Static) : isStatic(c.decl());
                t = t.getOwningClass();
            }
            return out;
        });
    }

    private void inherited(JavaType.FullyQualified t, boolean staticOnly, ClassInfo from, Set<String> seen, List<Logger> out) {
        while (t != null && !t.getFullyQualifiedName().equals("java.lang.Object") && seen.add(t.getFullyQualifiedName())) {
            ClassInfo c = index.clazz(t.getFullyQualifiedName());
            if (c != null) {
                boolean samePackage = packageOf(c.fqn()).equals(packageOf(from.fqn()));
                out.addAll(fields(c.decl(), staticOnly, true, samePackage));
            } else {
                members(t, staticOnly, out);
            }
            t = t.getSupertype();
        }
    }

    /** A source class's own loggers: its logger fields, then a Lombok logger annotation. */
    private void own(ClassInfo c, boolean staticOnly, List<Logger> out) {
        out.addAll(fields(c.decl(), staticOnly, false, true));
        for (J.Annotation a : c.decl().getLeadingAnnotations()) {
            for (Map.Entry<String, String> e : LOMBOK.entrySet()) {
                if (Annotations.is(a, e.getKey())) {
                    out.add(new Logger(lombokFieldName(c.file().absPath()), e.getValue().isEmpty() ? null : e.getValue(), true));
                }
            }
        }
    }

    /**
     * Logger fields declared in source. An inherited field is visible unless it is private, or package-private in
     * another package; the class's own (and its nest's) are all visible.
     */
    private static List<Logger> fields(J.ClassDeclaration decl, boolean staticOnly, boolean inherited, boolean samePackage) {
        List<Logger> out = new ArrayList<>();
        boolean iface = decl.getKind() == J.ClassDeclaration.Kind.Type.Interface;
        for (Statement s : decl.getBody().getStatements()) {
            if (!(s instanceof J.VariableDeclarations vd) || vd.getTypeExpression() == null) {
                continue;
            }
            boolean isStatic = iface || vd.hasModifier(J.Modifier.Type.Static);
            boolean exported = iface || vd.hasModifier(J.Modifier.Type.Public) || vd.hasModifier(J.Modifier.Type.Protected);
            boolean visible = !inherited || exported || samePackage && !vd.hasModifier(J.Modifier.Type.Private);
            if (staticOnly && !isStatic || !visible) {
                continue;
            }
            String api = api(vd.getType(), vd.getTypeExpression().printTrimmed());
            if (api != null) {
                vd.getVariables().forEach(v -> out.add(new Logger(v.getSimpleName(), api.isEmpty() ? null : api, isStatic)));
            }
        }
        return out;
    }

    /** Loggers of a compiled (library) type: its public and protected fields. */
    private static void members(JavaType.FullyQualified t, boolean staticOnly, List<Logger> out) {
        for (JavaType.Variable v : t.getMembers()) {
            if (!v.hasFlags(Flag.Public) && !v.hasFlags(Flag.Protected) || staticOnly && !v.hasFlags(Flag.Static)) {
                continue;
            }
            String api = api(v.getType(), null);
            if (api != null) {
                out.add(new Logger(v.getName(), api.isEmpty() ? null : api, v.hasFlags(Flag.Static)));
            }
        }
    }

    /** "" for SLF4J, the API's name for another logging API, null for a field that is no logger. */
    private static String api(JavaType type, String printed) {
        if (TypeUtils.isAssignableTo("org.slf4j.Logger", type)) {
            return "";
        }
        JavaType.FullyQualified fq = TypeUtils.asFullyQualified(type);
        if (fq != null && !(type instanceof JavaType.Unknown)) {
            return OTHER_TYPES.get(fq.getFullyQualifiedName());
        }
        // the type did not resolve: a field typed ...Logger counts as SLF4J, as log calls on it are recognised by name
        return printed != null && printed.endsWith("Logger") ? "" : null;
    }

    private static String simpleName(JavaType.FullyQualified t) {
        String fqn = t.getFullyQualifiedName();
        return fqn.substring(Math.max(fqn.lastIndexOf('.'), fqn.lastIndexOf('$')) + 1);
    }

    private static boolean isStatic(J.ClassDeclaration decl) {
        return decl.getKind() != J.ClassDeclaration.Kind.Type.Class || decl.hasModifier(J.Modifier.Type.Static);
    }

    private static String packageOf(String fqn) {
        int dot = fqn.lastIndexOf('.');
        return dot < 0 ? "" : fqn.substring(0, dot);
    }

    /**
     * The name Lombok gives its logger field for a source file: {@code lombok.log.fieldName} from the lombok.config files
     * of its folder and the folders above (until {@code config.stopBubbling}), the nearest setting winning; "log" by
     * default.
     */
    public static String lombokFieldName(Path source) {
        List<Path> configs = new ArrayList<>();
        for (Path dir = source.toAbsolutePath().getParent(); dir != null; dir = dir.getParent()) {
            Path cfg = dir.resolve("lombok.config");
            if (Files.isRegularFile(cfg)) {
                configs.add(cfg);
                if ("true".equalsIgnoreCase(setting(cfg, "config.stopBubbling", null))) {
                    break;
                }
            }
        }
        String name = "log";
        for (int i = configs.size() - 1; i >= 0; i--) {
            name = setting(configs.get(i), "lombok.log.fieldName", name);
        }
        return name;
    }

    /** The value a lombok.config file gives {@code key} ({@code clear} resets it to "log"), else {@code current}. */
    private static String setting(Path cfg, String key, String current) {
        try {
            for (String raw : Files.readAllLines(cfg)) {
                String line = raw.strip();
                if (line.startsWith("#")) {
                    continue;
                }
                if (line.regionMatches(true, 0, "clear ", 0, 6) && line.substring(6).strip().equalsIgnoreCase(key)) {
                    current = key.equals("lombok.log.fieldName") ? "log" : null;
                    continue;
                }
                int eq = line.indexOf('=');
                if (eq > 0 && line.charAt(eq - 1) != '+' && line.charAt(eq - 1) != '-'
                        && line.substring(0, eq).strip().equalsIgnoreCase(key)) {
                    current = line.substring(eq + 1).strip();
                }
            }
        } catch (IOException e) {
            // unreadable: Lombok would fail the build, keep what we have
        }
        return current;
    }
}
