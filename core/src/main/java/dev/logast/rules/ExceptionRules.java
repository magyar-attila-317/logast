package dev.logast.rules;

import dev.logast.model.CatchInfo;
import dev.logast.model.MethodInfo;
import dev.logast.orders.Edit;
import dev.logast.orders.Slot;
import dev.logast.parse.Pos;
import dev.logast.parse.SourceFileInfo;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** SWALLOWED_EXCEPTION: insert a log line (with domain values not already in MDC) into the catch. */
final class ExceptionRules {
    private static final Set<String> INTENTIONAL = Set.of("ignored", "ignore", "expected", "_");
    private final RuleContext ctx;

    ExceptionRules(RuleContext ctx) {
        this.ctx = ctx;
    }

    void run() {
        if (!ctx.enabled(Rules.SWALLOWED_EXCEPTION)) {
            return;
        }
        for (MethodInfo m : ctx.methods()) {
            List<CatchInfo> catches = m.facts().catches;
            for (int i = 0; i < catches.size(); i++) {
                CatchInfo c = catches.get(i);
                int line = Pos.line(c.clause);
                if (c.swallows() && !INTENTIONAL.contains(c.param) && !ctx.ignored(m.file(), line, Rules.SWALLOWED_EXCEPTION)) {
                    swallowed(m, c, i, line);
                }
            }
        }
    }

    private void swallowed(MethodInfo m, CatchInfo c, int ordinal, int line) {
        SourceFileInfo f = m.file();
        String text = f.text();
        int bodyStart = Pos.start(c.clause.getBody());
        String bodyText = f.slice(bodyStart, Pos.end(c.clause.getBody()));
        boolean commented = bodyText.contains("//") || bodyText.contains("/*");
        boolean fallback = !c.clause.getBody().getStatements().isEmpty();
        // empty catch: nobody will ever know; documented or fallback catches are usually deliberate
        String severity = commented ? "low" : fallback ? "medium" : Rules.severityOf(ctx.policy, Rules.SWALLOWED_EXCEPTION);
        OrderBuilder b = ctx.order(Rules.SWALLOWED_EXCEPTION, m, line, "catch" + ordinal)
                .severity(severity)
                .title(c.typeNames() + " is caught and dropped without a log line in " + m.label()
                        + (commented ? " (documented as intentional)" : fallback ? " (falls back silently)" : ""));
        ctx.swallowedOrders.put(c, b.id());

        JavaEdits.LoggerPlan logger = ctx.logger(m.owner());
        int open = Pos.start(c.clause.getBody());
        int close = Pos.end(c.clause.getBody()) - 1;
        String tryBody = Text.oneLine(f.slice(Pos.start(c.tryStatement.getBody()), Pos.end(c.tryStatement.getBody())), 120);
        String comment = comments(f.slice(open, close + 1));
        b.context(ctx.context(m, open + 1, "try " + tryBody + " catch (" + c.typeNames() + " " + c.param + ") "
                + Text.oneLine(f.slice(open, close + 1), 80), "MSG"));
        if (logger.name() == null) {
            b.suggestion("Log or rethrow " + c.param + "; " + m.owner().simpleName() + " cannot get a logger field automatically.");
            return;
        }

        Set<String> inMdc = ctx.mdcInside(m);
        List<DomainValues.Value> values = ctx.domain.fromVars(ctx.vars(m, open + 1)).stream()
                .filter(v -> !inMdc.contains(v.key())).toList();
        String args = String.join("", values.stream().map(v -> ", " + v.expr()).toList());
        String call = logger.name() + "." + ctx.policy.logging().swallowedLevel() + "(\"{{MSG}}\"" + args + "{{ARGS}}, " + c.param + ");";

        String unit = Text.indentUnit(text);
        String indent = Text.indentAt(text, Pos.start(c.clause)) + unit;
        if (Text.blankOrComment(text, open + 1, Text.lineEnd(text, open))) {
            b.edit(Edit.insert(f.relPath(), Text.nextLine(text, open), indent + call + "\n"));
        } else {
            String inner = f.slice(open + 1, close).strip();
            b.edit(Edit.replace(f.relPath(), open, close + 1, "{\n" + indent + call + "\n"
                    + (inner.isEmpty() ? "" : indent + inner + "\n") + Text.indentAt(text, Pos.start(c.clause)) + "}"));
        }
        b.edits(logger.edits());
        String upstream = calleeAlreadyLogs(m, c);
        if (upstream != null) {
            b.suggestion("Note: " + upstream + " already logs this exception before rethrowing it. After this fix that "
                    + "log becomes an EXCEPTION_LOGGED_TWICE order to delete (log once, where it is handled).");
        }
        b.slot("MSG", new Slot("What failed and what happens next, for " + c.typeNames() + " thrown by `" + tryBody + "`"
                + (comment.isEmpty() ? "" : " (code comment: \"" + comment + "\")") + ". The [k=v] suffix and the exception are added by the tool.",
                values.stream().map(v -> ctx.policy.logKey(v.key())).toList(), ctx.glossary.terms(), null,
                ctx.phrasing.canonicalOf("failed") == null ? null : "failed", ctx.phrasing.canonicalOf("failed")));
    }

    /** A callee (called inside this try) that logs and rethrows a type this catch handles. */
    private String calleeAlreadyLogs(MethodInfo m, CatchInfo c) {
        for (dev.logast.graph.CallGraph.Edge e : ctx.a.graph.out(m)) {
            if (!e.site().enclosingCatches().contains(c)) {
                continue;
            }
            for (CatchInfo inner : e.to().facts().catches) {
                if (inner.rethrowsCause && !inner.logs.isEmpty() && inner.throwsList.stream().anyMatch(t ->
                        c.types.stream().anyMatch(ct -> org.openrewrite.java.tree.TypeUtils.isAssignableTo(ct, t.getException().getType())))) {
                    return e.to().label();
                }
            }
        }
        return null;
    }

    private static String comments(String block) {
        Matcher m = Pattern.compile("//\\s*([^\\n]*)|/\\*+\\s*(.*?)\\s*\\*/", Pattern.DOTALL).matcher(block);
        List<String> out = new ArrayList<>();
        while (m.find()) {
            out.add(Text.oneLine(m.group(1) != null ? m.group(1) : m.group(2), 100));
        }
        return String.join(" ", out);
    }
}
