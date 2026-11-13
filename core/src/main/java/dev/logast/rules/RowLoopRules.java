package dev.logast.rules;

import dev.logast.graph.FactsExtractor;
import dev.logast.model.MethodInfo;
import dev.logast.parse.Pos;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.TypeUtils;

import java.util.*;
import java.util.regex.Pattern;

/**
 * ROW_BY_ROW_WRITE (resilience, report only): a loop over a query result that writes one row per item. The whole
 * result sits in memory and N items mean N statements; a bulk statement or paging scales. logast does not change the
 * data access, it says where and why.
 */
final class RowLoopRules {
    private static final Pattern WRITE_NAME = Pattern.compile(
            "^(save|update|delete|insert|close|mark|remove|archive|expire|upsert|increment|persist|merge|store|write).*");

    private final RuleContext ctx;

    RowLoopRules(RuleContext ctx) {
        this.ctx = ctx;
    }

    void run() {
        if (!ctx.enabled(Rules.ROW_BY_ROW_WRITE)) {
            return;
        }
        for (MethodInfo m : ctx.methods()) {
            if (m.decl().getBody() == null) {
                continue;
            }
            Map<String, Expression> locals = locals(m);
            new JavaIsoVisitor<Integer>() {
                @Override
                public J.ForEachLoop visitForEachLoop(J.ForEachLoop loop, Integer p) {
                    Expression it = loop.getControl().getIterable();
                    Expression source = it instanceof J.Identifier id && locals.containsKey(id.getSimpleName()) ? locals.get(id.getSimpleName()) : it;
                    if (FactsExtractor.dataSource(source)) {
                        String write = firstWrite(loop.getBody());
                        int line = Pos.line(loop);
                        if (write != null && !ctx.ignored(m.file(), line, Rules.ROW_BY_ROW_WRITE)) {
                            ctx.order(Rules.ROW_BY_ROW_WRITE, m, line, "rows" + Pos.start(loop))
                                    .title("Loop over '" + Text.oneLine(source.printTrimmed(), 50) + "' writes one row per item (" + write
                                            + "): the whole result is held in memory and N items mean N statements")
                                    .suggestion("One bulk statement (UPDATE … WHERE …) or paging with batch updates scales with the data. "
                                            + "Report only: logast does not change data access.")
                                    .context(ctx.context(m, Pos.start(loop), Text.oneLine(loop.printTrimmed(), 160), null));
                        }
                    }
                    return super.visitForEachLoop(loop, p);
                }
            }.visit(m.decl().getBody(), 0);
        }
    }

    private static Map<String, Expression> locals(MethodInfo m) {
        Map<String, Expression> out = new HashMap<>();
        new JavaIsoVisitor<Integer>() {
            @Override
            public J.VariableDeclarations.NamedVariable visitVariable(J.VariableDeclarations.NamedVariable v, Integer p) {
                if (v.getInitializer() != null) {
                    out.put(v.getSimpleName(), v.getInitializer());
                }
                return v;
            }
        }.visit(m.decl().getBody(), 0);
        return out;
    }

    /** The first call in the loop body that writes a row: JdbcTemplate update, Spring Data save/delete, own repository writes. */
    private static String firstWrite(J body) {
        String[] found = {null};
        new JavaIsoVisitor<Integer>() {
            @Override
            public J.MethodInvocation visitMethodInvocation(J.MethodInvocation mi, Integer p) {
                JavaType.Method mt = mi.getMethodType();
                if (found[0] == null && mt != null) {
                    JavaType.FullyQualified owner = mt.getDeclaringType();
                    String name = mi.getSimpleName();
                    boolean jdbc = (name.equals("update") || name.equals("batchUpdate"))
                            && (TypeUtils.isAssignableTo("org.springframework.jdbc.core.JdbcOperations", owner)
                            || TypeUtils.isAssignableTo("org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations", owner));
                    boolean springData = TypeUtils.isAssignableTo("org.springframework.data.repository.Repository", owner)
                            && name.matches("^(save|delete).*");
                    boolean ownRepo = (owner.getAnnotations().stream().anyMatch(an -> an.getFullyQualifiedName().equals("org.springframework.stereotype.Repository"))
                            || owner.getFullyQualifiedName().matches(".*(Repository|Dao|RepositoryImpl|DaoImpl)$"))
                            && WRITE_NAME.matcher(name).matches();
                    if (jdbc || springData || ownRepo) {
                        found[0] = owner.getClassName().replaceAll(".*\\.", "") + "." + name;
                    }
                }
                return super.visitMethodInvocation(mi, p);
            }
        }.visit(body, 0);
        return found[0];
    }
}
