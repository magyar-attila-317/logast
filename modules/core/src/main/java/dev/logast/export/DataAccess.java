package dev.logast.export;

import dev.logast.graph.CodeIndex;
import dev.logast.graph.Constants;
import dev.logast.model.Annotations;
import dev.logast.model.ClassInfo;
import dev.logast.model.MethodInfo;
import dev.logast.parse.Pos;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.merge.Merge;
import net.sf.jsqlparser.statement.update.Update;
import net.sf.jsqlparser.util.TablesNamesFinder;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.TypeTree;
import org.openrewrite.java.tree.TypeUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * #154: which code reads and writes which table. Entities ({@code @Entity}, {@code @Table}, Spring Boot's default
 * naming), Spring Data repositories (CRUD and derived methods by name, {@code @Query} JPQL and native,
 * {@code @Modifying}), {@code EntityManager}, and {@code JdbcTemplate} / {@code NamedParameterJdbcTemplate} /
 * {@code JdbcClient} with SQL the code states as constants (parsed with JSqlParser; schema-qualified names kept). SQL
 * built at runtime is reported where it runs, as unresolved.
 */
final class DataAccess {
    /** One read or write of a table, or (table null) a statement whose SQL is not a constant. */
    record Access(MethodInfo method, boolean write, String table, String via, String file, int line, boolean parsed) {
    }

    /** An entity class and its table. */
    record Entity(ClassInfo clazz, String name, String table) {
    }

    private static final Set<String> JDBC = Set.of("org.springframework.jdbc.core.JdbcTemplate",
            "org.springframework.jdbc.core.JdbcOperations",
            "org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate",
            "org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations");
    private static final Set<String> JDBC_METHODS = Set.of("query", "queryForObject", "queryForList", "queryForMap",
            "queryForRowSet", "queryForStream", "update", "batchUpdate", "execute");
    private static final String JDBC_CLIENT = "org.springframework.jdbc.core.simple.JdbcClient";
    private static final Pattern WRITE_PREFIX = Pattern.compile("^(save|delete|remove|insert|update|persist|merge|upsert)");
    private static final Pattern JPQL_SOURCE = Pattern.compile("(?i)\\b(?:from|join|update|into)\\s+([A-Za-z_][\\w$.]*)");
    private static final Pattern SQL_SOURCE = Pattern.compile("(?i)\\b(?:from|join|update|into|table)\\s+((?:[\"`\\[]?\\w+[\"`\\]]?\\.)?[\"`\\[]?\\w+[\"`\\]]?)");
    private static final Pattern SQL_WRITE = Pattern.compile("(?is)^\\s*(?:with\\b.*?\\)\\s*)?(insert|update|delete|merge|upsert|truncate)\\b");

    private final CodeIndex index;
    private final Map<String, Entity> byFqn = new LinkedHashMap<>();
    private final Map<String, Entity> byName = new LinkedHashMap<>();

    DataAccess(CodeIndex index) {
        this.index = index;
        for (ClassInfo c : index.classes()) {
            boolean entity = c.decl().getLeadingAnnotations().stream().anyMatch(a -> Annotations.is(a, "jakarta.persistence.Entity")
                    || Annotations.is(a, "javax.persistence.Entity"));
            if (!entity) {
                continue;
            }
            String name = c.simpleName();
            String table = snake(c.simpleName());
            String schema = null;
            for (J.Annotation a : c.decl().getLeadingAnnotations()) {
                if (Annotations.is(a, "jakarta.persistence.Entity") || Annotations.is(a, "javax.persistence.Entity")) {
                    name = Annotations.stringAttribute(a, "name").filter(s -> !s.isBlank()).orElse(name);
                }
                if (Annotations.is(a, "jakarta.persistence.Table") || Annotations.is(a, "javax.persistence.Table")) {
                    table = Annotations.stringAttribute(a, "name").filter(s -> !s.isBlank()).map(DataAccess::ident).orElse(table);
                    schema = Annotations.stringAttribute(a, "schema").filter(s -> !s.isBlank()).map(DataAccess::ident).orElse(null);
                }
            }
            Entity e = new Entity(c, name, schema == null ? table : schema + "." + table);
            byFqn.put(c.fqn(), e);
            byName.putIfAbsent(name, e);
            byName.putIfAbsent(c.simpleName(), e);
        }
    }

    List<Entity> entities() {
        return List.copyOf(byFqn.values());
    }

    List<Access> accesses() {
        List<Access> out = new ArrayList<>();
        index.eachCall((m, mi) -> access(m, mi, out));
        return out;
    }

    private void access(MethodInfo m, J.MethodInvocation mi, List<Access> out) {
        String file = m.file().relPath();
        int line = Pos.line(mi.getName());
        String name = mi.getSimpleName();
        JavaType.Method mt = mi.getMethodType();
        String declaring = mt == null ? null : mt.getDeclaringType().getFullyQualifiedName();
        String selectType = mi.getSelect() == null ? null : fqn(mi.getSelect().getType());

        // JdbcTemplate & co: the SQL is the first argument
        if ((declaring != null && JDBC.contains(declaring) || selectType != null && JDBC.contains(selectType)) && JDBC_METHODS.contains(name)) {
            sql(m, first(mi), simple(declaring != null ? declaring : selectType) + "." + name, file, line, out);
            return;
        }
        if ((JDBC_CLIENT.equals(declaring) || JDBC_CLIENT.equals(selectType)) && name.equals("sql")) {
            sql(m, first(mi), "JdbcClient.sql", file, line, out);
            return;
        }
        // EntityManager
        if ("jakarta.persistence.EntityManager".equals(declaring) || "javax.persistence.EntityManager".equals(declaring)) {
            switch (name) {
                case "persist", "merge", "remove" -> entityArgument(m, mi, true, "EntityManager." + name, file, line, out);
                case "find", "getReference" -> classArgument(m, mi, "EntityManager." + name, file, line, out);
                case "createNativeQuery" -> sql(m, first(mi), "EntityManager.createNativeQuery", file, line, out);
                case "createQuery" -> jpql(m, first(mi), "EntityManager.createQuery", file, line, out);
                default -> {
                }
            }
            return;
        }
        // Spring Data repositories
        if (selectType == null) {
            return;
        }
        Entity entity = entityOfRepository(selectType, new LinkedHashSet<>());
        if (entity == null) {
            return;
        }
        String via = simple(selectType) + "." + name;
        MethodInfo declared = mt == null ? null : index.resolve(mt).stream().findFirst().orElse(null);
        if (declared == null) {   // the method's type is not attributed (a supertype off the classpath): by name and arity
            int arity = (int) mi.getArguments().stream().filter(x -> !(x instanceof J.Empty)).count();
            declared = index.methodsOf(selectType).stream().filter(x -> x.name().equals(name)
                    && x.decl().getParameters().stream().filter(p -> !(p instanceof J.Empty)).count() == arity).findFirst().orElse(null);
        }
        J.Annotation query = declared == null ? null : annotation(declared, "org.springframework.data.jpa.repository.Query");
        if (query != null) {
            boolean modifying = annotation(declared, "org.springframework.data.jpa.repository.Modifying") != null;
            String text = Annotations.attribute(query, "value").map(e -> Constants.concatenated(index, e)).orElse(null);
            boolean nativeQuery = Annotations.rawAttribute(query, "nativeQuery").map(v -> v.strip().equals("true")).orElse(false);
            if (text == null) {
                out.add(new Access(m, modifying, null, via, file, line, false));
            } else if (nativeQuery) {
                sqlText(m, text, via, file, line, out);
            } else {
                jpqlText(m, text, via, file, line, modifying, out);
            }
            return;
        }
        out.add(new Access(m, WRITE_PREFIX.matcher(name).find(), entity.table(), via, file, line, true));
    }

    // ---- SQL ----------------------------------------------------------------------------------------------------------

    private void sql(MethodInfo m, Expression arg, String via, String file, int line, List<Access> out) {
        String text = arg == null ? null : Constants.concatenated(index, arg);
        if (text != null) {
            sqlText(m, text, via, file, line, out);
            return;
        }
        // #177: built SQL: the tables its constant parts name (a variable part stands for nothing, so a table name
        // built at runtime is not guessed); unresolved only when they name none
        String partial = arg == null ? null : partial(arg, 0);
        if (partial != null && SESSION.matcher(partial).matches()) {
            return;
        }
        if (partial != null && !partial.isBlank()) {
            List<Access> found = new ArrayList<>();
            keywords(m, partial, via, file, line, found);
            if (found.stream().anyMatch(x -> x.table() != null)) {
                out.addAll(found);
                return;
            }
        }
        out.add(new Access(m, false, null, via, file, line, false));
    }

    /** The constant text of an expression, with {@code \u0000} where the code computes a part. */
    private String partial(Expression e, int depth) {
        if (depth > 20) {
            return "\u0000";
        }
        String c = Constants.concatenated(index, e);
        if (c != null) {
            return c;
        }
        if (e instanceof J.Binary b && b.getOperator() == J.Binary.Type.Addition) {
            return partial(b.getLeft(), depth + 1) + partial(b.getRight(), depth + 1);
        }
        if (e instanceof J.Parentheses<?> p && p.getTree() instanceof Expression inner) {
            return partial(inner, depth + 1);
        }
        return "\u0000";
    }

    /** Tables from a statement's keywords alone (no parser): for SQL only partly known. */
    private static void keywords(MethodInfo m, String sql0, String via, String file, int line, List<Access> out) {
        if (SESSION.matcher(sql0).matches()) {
            return;
        }
        String sql = sql0.replace("\u0000.", "");   // a schema built at runtime (per tenant): the table, without it
        boolean write = SQL_WRITE.matcher(sql).find();
        Matcher s = SQL_SOURCE.matcher(sql);
        boolean first = true;
        String written = null;
        Set<String> reads = new LinkedHashSet<>();
        while (s.find()) {
            String n = tableName(s.group(1));
            if (write && first) {
                written = n;
            } else if (!n.equals(written)) {
                reads.add(n);
            }
            first = false;
        }
        if (written != null) {
            out.add(new Access(m, true, written, via, file, line, false));
        }
        reads.forEach(r -> out.add(new Access(m, false, r, via, file, line, false)));
    }

    /** Tables a statement reads and the one it writes; a statement JSqlParser cannot read falls back to its keywords. */
    /** Session and transaction commands: they touch no table (#177). */
    private static final Pattern SESSION = Pattern.compile("(?is)^\\s*(set|reset|show|begin|start\\s+transaction|commit|rollback|"
            + "savepoint|release|discard|listen|notify|unlisten|vacuum|analyze|checkpoint|lock\\s+table)\\b.*");

    static void sqlText(MethodInfo m, String sql, String via, String file, int line, List<Access> out) {
        if (SESSION.matcher(sql).matches()) {
            return;
        }
        Set<String> reads = new LinkedHashSet<>();
        String written = null;
        boolean parsed = true;
        try {
            Statement st = CCJSqlParserUtil.parse(sql);
            Table target = st instanceof Insert i ? i.getTable() : st instanceof Update u ? u.getTable()
                    : st instanceof Delete d ? d.getTable() : st instanceof Merge mg ? mg.getTable() : null;
            if (target != null) {
                written = tableName(target.getSchemaName(), target.getName());
            }
            for (String t : TablesNamesFinder.findTables(sql)) {
                String n = tableName(t);
                if (!n.equals(written)) {
                    reads.add(n);
                }
            }
        } catch (JSQLParserException | RuntimeException e) {
            parsed = false;
            Matcher w = SQL_WRITE.matcher(sql);
            boolean write = w.find();
            Matcher s = SQL_SOURCE.matcher(sql);
            boolean first = true;
            while (s.find()) {
                String n = tableName(s.group(1));
                if (write && first) {
                    written = n;
                } else if (!n.equals(written)) {
                    reads.add(n);
                }
                first = false;
            }
        }
        if (written != null) {
            out.add(new Access(m, true, written, via, file, line, parsed));
        }
        for (String r : reads) {
            out.add(new Access(m, false, r, via, file, line, parsed));
        }
        if (written == null && reads.isEmpty() && !parsed) {   // #177: parsed with no table (a lock, nextval, SET): no fact
            out.add(new Access(m, false, null, via, file, line, false));
        }
    }

    // ---- JPQL ---------------------------------------------------------------------------------------------------------

    private void jpql(MethodInfo m, Expression arg, String via, String file, int line, List<Access> out) {
        String text = arg == null ? null : Constants.concatenated(index, arg);
        if (text == null) {
            out.add(new Access(m, false, null, via, file, line, false));
        } else {
            jpqlText(m, text, via, file, line, false, out);
        }
    }

    private void jpqlText(MethodInfo m, String jpql, String via, String file, int line, boolean modifying, List<Access> out) {
        boolean write = modifying || jpql.strip().toLowerCase(Locale.ROOT).matches("(?s)^(update|delete)\\b.*");
        Matcher s = JPQL_SOURCE.matcher(jpql);
        boolean first = true;
        boolean any = false;
        while (s.find()) {
            String n = s.group(1);
            Entity e = byFqn.containsKey(n) ? byFqn.get(n) : byName.get(n.substring(n.lastIndexOf('.') + 1));
            if (e != null) {
                out.add(new Access(m, write && first, e.table(), via, file, line, true));
                any = true;
            }
            first = false;
        }
        if (!any) {
            out.add(new Access(m, write, null, via, file, line, false));
        }
    }

    // ---- EntityManager arguments --------------------------------------------------------------------------------------

    private void entityArgument(MethodInfo m, J.MethodInvocation mi, boolean write, String via, String file, int line, List<Access> out) {
        Expression a = first(mi);
        Entity e = a == null ? null : byFqn.get(fqn(a.getType()));
        out.add(new Access(m, write, e == null ? null : e.table(), via, file, line, e != null));
    }

    private void classArgument(MethodInfo m, J.MethodInvocation mi, String via, String file, int line, List<Access> out) {
        Expression a = first(mi);
        Entity e = null;
        if (a instanceof J.FieldAccess fa && fa.getSimpleName().equals("class")) {
            e = byFqn.get(fqn(fa.getTarget().getType()));
        }
        out.add(new Access(m, false, e == null ? null : e.table(), via, file, line, e != null));
    }

    // ---- repositories -------------------------------------------------------------------------------------------------

    /**
     * The entity a repository interface manages: the first type argument of the {@code …Repository<T, ID>} it extends,
     * read from its source (the Spring Data JPA types need not be on the classpath), through project interfaces it extends.
     */
    private Entity entityOfRepository(String fqn, Set<String> seen) {
        if (!seen.add(fqn)) {
            return null;
        }
        ClassInfo c = index.clazz(fqn);
        if (c == null || !c.isInterface()) {
            return null;
        }
        List<TypeTree> supers = new ArrayList<>();
        if (c.decl().getExtends() != null) {
            supers.add(c.decl().getExtends());
        }
        if (c.decl().getImplements() != null) {
            supers.addAll(c.decl().getImplements());
        }
        for (TypeTree t : supers) {
            if (t instanceof J.ParameterizedType pt && pt.getTypeParameters() != null && !pt.getTypeParameters().isEmpty()
                    && pt.getClazz() != null && simpleName(pt.getClazz()).matches(".*(Repository|Executor)")) {
                Expression arg = pt.getTypeParameters().getFirst();
                String argFqn = fqn(arg.getType());
                Entity e = argFqn != null ? byFqn.get(argFqn) : null;
                if (e == null && arg instanceof J.Identifier id) {
                    e = byName.get(id.getSimpleName());
                }
                if (e != null) {
                    return e;
                }
            }
            String superFqn = fqn(t.getType());
            if (superFqn != null) {
                Entity e = entityOfRepository(superFqn, seen);
                if (e != null) {
                    return e;
                }
            }
        }
        return null;
    }

    // ---- helpers ------------------------------------------------------------------------------------------------------

    private static J.Annotation annotation(MethodInfo m, String fqn) {
        return m.decl().getLeadingAnnotations().stream().filter(a -> Annotations.is(a, fqn)).findFirst().orElse(null);
    }

    private static Expression first(J.MethodInvocation mi) {
        List<Expression> args = mi.getArguments();
        return args.isEmpty() || args.getFirst() instanceof J.Empty ? null : args.getFirst();
    }

    private static String fqn(JavaType t) {
        JavaType.FullyQualified f = TypeUtils.asFullyQualified(t);
        return f == null || t instanceof JavaType.Unknown ? null : f.getFullyQualifiedName();
    }

    private static String simpleName(Object tree) {
        return tree instanceof J.Identifier id ? id.getSimpleName() : tree instanceof J.FieldAccess fa ? fa.getSimpleName() : "";
    }

    private static String simple(String fqn) {
        return fqn == null ? "?" : fqn.substring(fqn.lastIndexOf('.') + 1);
    }

    /** Spring Boot's default physical naming: {@code OrderLine} → {@code order_line}. */
    static String snake(String name) {
        return name.replaceAll("([a-z0-9])([A-Z])", "$1_$2").replaceAll("([A-Z])([A-Z][a-z])", "$1_$2").toLowerCase(Locale.ROOT);
    }

    /** An identifier as the database folds it: unquoted names lower case (PostgreSQL), quotes removed. */
    static String ident(String s) {
        String t = s.strip();
        if (t.length() >= 2 && (t.startsWith("\"") && t.endsWith("\"") || t.startsWith("`") && t.endsWith("`")
                || t.startsWith("[") && t.endsWith("]"))) {
            return t.substring(1, t.length() - 1);
        }
        return t.toLowerCase(Locale.ROOT);
    }

    static String tableName(String qualified) {
        String[] parts = qualified.split("\\.");
        return parts.length >= 2 ? tableName(parts[parts.length - 2], parts[parts.length - 1]) : tableName(null, parts[0]);
    }

    static String tableName(String schema, String name) {
        return schema == null || schema.isBlank() ? ident(name) : ident(schema) + "." + ident(name);
    }
}
