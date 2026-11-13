package dev.logast.rules;

import dev.logast.graph.CallGraph;
import dev.logast.graph.Constants;
import dev.logast.model.Entrypoint;
import dev.logast.model.LogCall;
import dev.logast.model.MethodInfo;
import dev.logast.orders.Edit;
import dev.logast.orders.Slot;
import dev.logast.parse.Pos;
import dev.logast.parse.SourceFileInfo;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.TypeUtils;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Database outcomes, logged where they happen (the callee owns the facts only it has: rows changed, rows read).
 * <ul>
 *   <li>DB_OUTCOME_UNLOGGED: a method running one JdbcTemplate / NamedParameterJdbcTemplate / JdbcClient statement and
 *       logging nothing gets one line after it. Write or read is told from the SQL (an upsert through
 *       {@code queryForObject} is a write). Writes: INFO, through {@code AfterCommit} inside transactions, so a
 *       rolled-back change is never announced. Reads: INFO only when every path to them is low-frequency (jobs,
 *       startup, or a traffic.yaml rate within the volume budget), else DEBUG. Called inside a loop: DEBUG. The wording is generated from the table and
 *       marked {@code // logast:wording};</li>
 *   <li>MESSAGE_SUGGESTED: a line with that marker asks for domain wording (NEEDS TEXT); the marker goes once the
 *       text is written.</li>
 * </ul>
 */
final class DbRules {
    static final String WORDING_MARKER = "// logast:wording";

    private static final Pattern WRITE = Pattern.compile("(?is)\\b(insert\\s+into|update\\s+[\\w.\"]+\\s+set|delete\\s+from|merge\\s+into)\\b");
    private static final Pattern UPSERT = Pattern.compile("(?is)on\\s+conflict.*do\\s+update|\\bmerge\\s+into\\b|on\\s+duplicate\\s+key\\s+update");
    private static final Pattern TABLE_WRITE = Pattern.compile("(?is)\\b(?:insert\\s+into|update|delete\\s+from|merge\\s+into)\\s+([\\w.\"]+)");
    private static final Pattern TABLE_READ = Pattern.compile("(?is)\\bfrom\\s+([\\w.\"]+)");
    private static final Pattern RETURNING = Pattern.compile("(?is)\\breturning\\s+([\\w\"]+)");
    private static final Set<String> TEMPLATE_CALLS = Set.of("update", "batchUpdate", "query", "queryForObject", "queryForList",
            "queryForMap", "queryForRowSet");
    private static final Set<String> CLIENT_TERMINALS = Set.of("update", "list", "single", "optional", "set");
    private static final Set<Entrypoint.Kind> LOW_FREQUENCY = EnumSet.of(Entrypoint.Kind.SCHEDULED, Entrypoint.Kind.BATCH,
            Entrypoint.Kind.MAIN);

    private final RuleContext ctx;
    private final Map<String, List<int[]>> loopRanges = new HashMap<>();

    DbRules(RuleContext ctx) {
        this.ctx = ctx;
    }

    void run() {
        for (MethodInfo m : ctx.methods()) {
            if (ctx.enabled(Rules.MESSAGE_SUGGESTED)) {
                suggestedWording(m);
            }
            if (ctx.enabled(Rules.DB_OUTCOME_UNLOGGED) && m.decl().getBody() != null && m.facts().logs.isEmpty() && !ctx.logHelper(m)) {
                outcome(m);
            }
        }
    }

    // ---- DB_OUTCOME_UNLOGGED --------------------------------------------------------------------

    private record Call(J.MethodInvocation mi, Expression sqlArg, boolean client) {
    }

    private void outcome(MethodInfo m) {
        List<Call> calls = new ArrayList<>();
        new JavaIsoVisitor<Integer>() {
            @Override
            public J.Lambda visitLambda(J.Lambda l, Integer p) {
                return l;
            }

            @Override
            public J.MethodInvocation visitMethodInvocation(J.MethodInvocation mi, Integer p) {
                Call c = jdbcCall(mi);
                if (c != null) {
                    calls.add(c);
                }
                return super.visitMethodInvocation(mi, p);
            }
        }.visit(m.decl().getBody(), 0);
        if (calls.size() != 1) {
            return; // several statements: a person decides what the method's outcome is
        }
        Call call = calls.getFirst();
        int line = Pos.line(call.mi().getName());
        if (ctx.ignored(m.file(), line, Rules.DB_OUTCOME_UNLOGGED)) {
            return;
        }
        String sql = call.sqlArg() == null ? null : Constants.string(ctx.a.index, call.sqlArg());
        String name = call.mi().getSimpleName();
        boolean write = sql != null ? WRITE.matcher(sql).find()
                : name.equals("update") || name.equals("batchUpdate");
        if (sql != null && !write && (!sql.toLowerCase(Locale.ROOT).contains("select")
                || sql.toLowerCase(Locale.ROOT).matches("(?s).*\\b(nextval|currval|next\\s+value\\s+for)\\b.*"))) {
            return; // DDL, a procedure call, a sequence: not a data outcome
        }
        boolean upsert = sql != null && UPSERT.matcher(sql).find();
        String verb = !write ? "read" : upsert ? "saved" : verbOf(sql, name);
        String subject = subject(m, sql, write);

        J stmt = CoverageRules.enclosingStatement(m, call.mi());
        if (stmt == null) {
            return;
        }
        SourceFileInfo f = m.file();
        String text = f.text();
        String indent = Text.indentAt(text, Pos.start(stmt));
        int[] lines = JavaEdits.statementLines(text, stmt);
        Set<String> taken = ctx.taken(m, Pos.start(stmt));
        String returning = returningColumn(sql);

        String var;
        JavaType type;
        List<Edit> edits = new ArrayList<>();
        String tail = "";
        if (stmt instanceof J.Return ret && ret.getExpression() != null && Pos.start(ret.getExpression()) <= Pos.start(call.mi())
                && Pos.end(call.mi()) <= Pos.end(ret.getExpression())) {
            type = m.decl().getMethodType() == null ? null : m.decl().getMethodType().getReturnType();
            String typeText = m.decl().getReturnTypeExpression() == null ? "var" : m.decl().getReturnTypeExpression().printTrimmed();
            var = EntrypointRules.unique(write ? (returning != null && !name.equals("update") ? camel(returning) : "rows") : "result", taken);
            edits.add(Edit.replace(f.relPath(), lines[0], lines[1], indent + typeText + " " + var + " = "
                    + f.slice(Pos.start(ret.getExpression()), Pos.end(ret.getExpression())) + ";\n"));
            tail = indent + "return " + var + ";\n";
        } else if (stmt instanceof J.VariableDeclarations vd && vd.getVariables().size() == 1
                && vd.getVariables().getFirst().getInitializer() != null) {
            var = vd.getVariables().getFirst().getSimpleName();
            type = vd.getType();
        } else if (stmt == call.mi() && write) {
            type = call.mi().getMethodType() == null ? null : call.mi().getMethodType().getReturnType();
            String typeText = typeText(type);
            if (typeText == null) {
                return;
            }
            var = EntrypointRules.unique("rows", taken);
            edits.add(Edit.insert(f.relPath(), Pos.start(stmt), typeText + " " + var + " = "));
        } else {
            return; // the result is used inside a larger expression: leave it to a person
        }

        String[] value = value(write, type, var, returning, name);
        if (value == null) {
            return; // a stream: counting it would consume it
        }
        JavaEdits.LoggerPlan logger = JavaEdits.logger(m.owner(), ctx.policy.logging().loggerName());
        if (logger.name() == null) {
            return;
        }
        boolean perItem = calledInLoop(m);
        String why;
        String level;
        if (perItem) {
            level = "debug";
            why = "called inside a loop: one line per item";
        } else if (write) {
            level = "info";
            why = "a change: worth a line";
        } else {
            String busy = busyPath(m);
            level = busy == null ? "info" : "debug";
            why = busy == null ? "only low-frequency paths reach it" : busy;
        }
        String message = subject + " " + verb + (value[0].isEmpty() ? "" : " [" + value[0] + "={}]");
        String args = value[0].isEmpty() ? "" : ", " + value[1];
        String logCall = logger.name() + "." + level + "(" + Text.javaString(message) + args + ")";
        boolean afterCommit = write && ctx.a.project.probe().hasClass("org.springframework.transaction.support.TransactionSynchronizationManager")
                && ctx.supportHas("AfterCommit");
        StringBuilder add = new StringBuilder();
        add.append(indent).append(afterCommit ? "AfterCommit.log(() -> " + logCall + ");" : logCall + ";")
                .append(' ').append(WORDING_MARKER).append('\n');
        if (tail.isEmpty()) {
            edits.add(Edit.insert(f.relPath(), lines[1], add.toString()));
        } else {
            // the return statement was replaced by the variable; the line and the return follow it
            Edit first = edits.removeFirst();
            edits.add(new Edit(first.file(), first.kind(), first.start(), first.end(), first.text() + add + tail));
        }

        OrderBuilder b = ctx.order(Rules.DB_OUTCOME_UNLOGGED, m, line, "db")
                .title(m.label() + " " + (write ? "changes" : "reads") + " data (" + (sql == null ? name : firstWords(sql)) + ") and logs nothing: "
                        + level.toUpperCase(Locale.ROOT) + " line added (" + why + ")")
                .suggestion("Generated wording \"" + message + "\" is marked " + WORDING_MARKER + "; write a better one in "
                        + "fills.json once it is applied (MESSAGE_SUGGESTED)."
                        + (afterCommit ? " Logged after commit: a rolled-back change is never announced." : "")
                        + (!write && level.equals("debug") ? " A .logast/traffic.yaml rate within the volume budget raises it to INFO." : ""))
                .context(ctx.context(m, Pos.start(stmt), Text.oneLine(text.substring(Pos.start(stmt), Math.min(Pos.end(stmt), text.length())), 160), null));
        b.edits(edits);
        b.edits(logger.edits());
        if (afterCommit) {
            b.edits(JavaEdits.imports(f, ctx.support("AfterCommit")));
            ctx.dependOnStarter(b);
        }
    }

    private Call jdbcCall(J.MethodInvocation mi) {
        JavaType.Method mt = mi.getMethodType();
        if (mt == null) {
            return null;
        }
        JavaType.FullyQualified owner = mt.getDeclaringType();
        if (TEMPLATE_CALLS.contains(mi.getSimpleName())
                && (TypeUtils.isAssignableTo("org.springframework.jdbc.core.JdbcOperations", owner)
                || TypeUtils.isAssignableTo("org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations", owner))) {
            Expression first = mi.getArguments().isEmpty() || mi.getArguments().getFirst() instanceof J.Empty ? null : mi.getArguments().getFirst();
            return new Call(mi, first != null && TypeUtils.isString(first.getType()) ? first : null, false);
        }
        if (CLIENT_TERMINALS.contains(mi.getSimpleName()) && owner.getFullyQualifiedName().startsWith("org.springframework.jdbc.core.simple.JdbcClient")) {
            for (Expression e = mi.getSelect(); e instanceof J.MethodInvocation sel; e = sel.getSelect()) {
                if (sel.getSimpleName().equals("sql") && sel.getArguments().size() == 1) {
                    return new Call(mi, sel.getArguments().getFirst(), true);
                }
            }
            return new Call(mi, null, true);
        }
        return null;
    }

    /** [key, value expression]; ["", ""] for no value; null when the result must not be touched (a stream). */
    private static String[] value(boolean write, JavaType type, String var, String returning, String callName) {
        if (type == null) {
            return new String[]{"", ""};
        }
        if (TypeUtils.isAssignableTo("java.util.stream.BaseStream", type)) {
            return null;
        }
        if (type instanceof JavaType.Array arr) {
            if (arr.getElemType() instanceof JavaType.Array) {
                return new String[]{"batches", var + ".length"};
            }
            return new String[]{"rows", "java.util.Arrays.stream(" + var + ").sum()"};
        }
        if (TypeUtils.isAssignableTo("java.util.Collection", type)) {
            return new String[]{write ? "rows" : "count", var + ".size()"};
        }
        if (TypeUtils.isAssignableTo("java.util.Optional", type)) {
            return new String[]{"found", var + ".isPresent()"};
        }
        boolean scalar = type instanceof JavaType.Primitive p && p != JavaType.Primitive.Void
                || TypeUtils.isAssignableTo("java.lang.Number", type) || TypeUtils.isString(type)
                || TypeUtils.isAssignableTo("java.lang.Boolean", type) || TypeUtils.isAssignableTo("java.util.UUID", type);
        if (write && scalar) {
            return new String[]{returning != null && !callName.equals("update") ? camel(returning) : "rows", var};
        }
        if (!write && (type == JavaType.Primitive.Boolean || TypeUtils.isAssignableTo("java.lang.Boolean", type))) {
            return new String[]{"found", var};
        }
        if (!write && (type instanceof JavaType.Primitive p && p != JavaType.Primitive.Void || TypeUtils.isAssignableTo("java.lang.Number", type))) {
            return new String[]{"value", var};
        }
        return new String[]{"", ""};
    }

    private static String typeText(JavaType t) {
        if (t == JavaType.Primitive.Int) {
            return "int";
        }
        if (t instanceof JavaType.Array a && a.getElemType() == JavaType.Primitive.Int) {
            return "int[]";
        }
        if (t instanceof JavaType.Array a && a.getElemType() instanceof JavaType.Array b && b.getElemType() == JavaType.Primitive.Int) {
            return "int[][]";
        }
        return null;
    }

    private static String verbOf(String sql, String callName) {
        if (sql == null) {
            return "updated";
        }
        String s = sql.toLowerCase(Locale.ROOT);
        Matcher m = WRITE.matcher(s);
        if (!m.find()) {
            return "updated";
        }
        String kw = m.group(1);
        return kw.startsWith("insert") ? "inserted" : kw.startsWith("delete") ? "deleted" : "updated";
    }

    /** "blind_cash_count_preview_counters" → "Blind cash count preview counters"; else the class: "Order item". */
    private static String subject(MethodInfo m, String sql, boolean write) {
        String table = null;
        if (sql != null) {
            Matcher t = (write ? TABLE_WRITE : TABLE_READ).matcher(sql);
            if (t.find()) {
                table = t.group(1).replace("\"", "");
                table = table.substring(table.lastIndexOf('.') + 1);
            }
        }
        String words;
        if (table != null && !table.isBlank()) {
            words = table.replace('_', ' ').toLowerCase(Locale.ROOT);
        } else {
            String cls = m.owner().simpleName().replaceAll("(Jdbc)?(Repository|Dao|Repo|Store)(Impl)?$", "");
            words = cls.replaceAll("([a-z0-9])([A-Z])", "$1 $2").toLowerCase(Locale.ROOT);
            if (words.isBlank()) {
                words = "rows";
            }
        }
        return Character.toUpperCase(words.charAt(0)) + words.substring(1);
    }

    private static String returningColumn(String sql) {
        if (sql == null) {
            return null;
        }
        Matcher m = RETURNING.matcher(sql);
        return m.find() && !m.group(1).equals("*") ? m.group(1).replace("\"", "") : null;
    }

    private static String camel(String column) {
        String[] parts = column.toLowerCase(Locale.ROOT).split("_");
        StringBuilder sb = new StringBuilder(parts[0]);
        for (int i = 1; i < parts.length; i++) {
            if (!parts[i].isEmpty()) {
                sb.append(Character.toUpperCase(parts[i].charAt(0))).append(parts[i].substring(1));
            }
        }
        return sb.toString();
    }

    private static String firstWords(String sql) {
        String one = sql.strip().replaceAll("\\s+", " ");
        return one.length() > 50 ? one.substring(0, 50) + "…" : one;
    }

    /** Null when every entrypoint that reaches {@code m} is low-frequency; else why not. */
    private String busyPath(MethodInfo m) {
        List<Entrypoint> eps = ctx.a.paths.entrypointsReaching(m);
        if (eps.isEmpty()) {
            return "no entrypoint is known to reach it";
        }
        double budget = ctx.policy.levels().volumeBudget();
        for (Entrypoint ep : eps) {
            if (LOW_FREQUENCY.contains(ep.kind())) {
                continue;
            }
            Double rate = ctx.a.traffic.known() ? ctx.a.traffic.perDay(ep.label()) : null;
            if (rate == null) {
                return "reached from " + ep.label() + ", frequency unknown";
            }
            if (rate > budget) {
                return "reached from " + ep.label() + " at " + VolumeRules.human(rate) + "/day";
            }
        }
        return null;
    }

    /** A direct caller calls it from inside a loop (for/while/forEach/stream lambda). */
    private boolean calledInLoop(MethodInfo m) {
        for (CallGraph.Edge e : ctx.a.graph.in(m)) {
            MethodInfo caller = e.from();
            if (caller.decl().getBody() == null || e.site() == null || e.site().tree() == null) {
                continue;
            }
            int at = Pos.start(e.site().tree());
            for (int[] r : loopRanges.computeIfAbsent(caller.key(), k -> loops(caller))) {
                if (r[0] <= at && at < r[1]) {
                    return true;
                }
            }
        }
        return false;
    }

    private static final Set<String> PER_ELEMENT = Set.of("forEach", "map", "flatMap", "peek", "filter", "forEachOrdered");

    private static List<int[]> loops(MethodInfo caller) {
        List<int[]> out = new ArrayList<>();
        new JavaIsoVisitor<Integer>() {
            @Override
            public J.ForEachLoop visitForEachLoop(J.ForEachLoop l, Integer p) {
                out.add(new int[]{Pos.start(l.getBody()), Pos.end(l.getBody())});
                return super.visitForEachLoop(l, p);
            }

            @Override
            public J.ForLoop visitForLoop(J.ForLoop l, Integer p) {
                out.add(new int[]{Pos.start(l.getBody()), Pos.end(l.getBody())});
                return super.visitForLoop(l, p);
            }

            @Override
            public J.WhileLoop visitWhileLoop(J.WhileLoop l, Integer p) {
                out.add(new int[]{Pos.start(l.getBody()), Pos.end(l.getBody())});
                return super.visitWhileLoop(l, p);
            }

            @Override
            public J.MethodInvocation visitMethodInvocation(J.MethodInvocation mi, Integer p) {
                if (PER_ELEMENT.contains(mi.getSimpleName())) {
                    for (Expression a : mi.getArguments()) {
                        if (a instanceof J.Lambda || a instanceof J.MemberReference) {
                            out.add(new int[]{Pos.start(a), Pos.end(a)});
                        }
                    }
                }
                return super.visitMethodInvocation(mi, p);
            }
        }.visit(caller.decl().getBody(), 0);
        return out;
    }

    // ---- MESSAGE_SUGGESTED ----------------------------------------------------------------------

    private void suggestedWording(MethodInfo m) {
        SourceFileInfo f = m.file();
        String text = f.text();
        int n = 0;
        for (LogCall lc : m.facts().logs) {
            int end = Pos.end(lc.call());
            int eol = text.indexOf('\n', end);
            String rest = text.substring(end, eol < 0 ? text.length() : eol);
            int marker = rest.indexOf(WORDING_MARKER);
            if (marker < 0 || !(lc.template() instanceof J.Literal lit) || !(lit.getValue() instanceof String raw)) {
                continue;
            }
            int line = Pos.line(lc.call());
            if (ctx.ignored(f, line, Rules.MESSAGE_SUGGESTED)) {
                continue;
            }
            List<String> keys = MessageTemplate.compliantKeys(raw);
            String base = MessageTemplate.compliantBase(raw);
            int from = end + marker;
            while (from > end && text.charAt(from - 1) == ' ') {
                from--;
            }
            ctx.order(Rules.MESSAGE_SUGGESTED, m, line, "suggested" + n++)
                    .title("Generated wording \"" + Text.oneLine(raw, 70) + "\": say it in domain terms")
                    .edit(Edit.replace(f.relPath(), Pos.start(lit), Pos.end(lit), "\"{{MSG}}\""))
                    .edit(Edit.replace(f.relPath(), from, end + marker + WORDING_MARKER.length(), ""))
                    .slot("MSG", new Slot("What happened, in domain terms (the generated text was made from the table name), "
                            + "e.g. 'Order items closed' instead of 'Order items updated'", keys == null ? List.of() : keys,
                            ctx.glossary.terms(), base))
                    .context(ctx.context(m, Pos.start(lc.call()), Text.oneLine(lc.call().printTrimmed(), 160), null));
        }
    }
}
