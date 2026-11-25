package dev.logast.rules;

import dev.logast.model.CatchInfo;
import org.openrewrite.java.tree.J;
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
        b.about(f.relPath(), c.clause);
        ctx.swallowedOrders.put(c, b.id());

        // G4: in a loop, a catch that records the case somewhere (a report, a list of rejects) and moves on is the flow's
        // expected outcome (a rejected import row): a WARN with a stack trace per item would be noise
        if (recordedInLoop(m, c)) {
            b.severity("low").suggestion("Left as it is: the catch records the case (" + Text.oneLine(f.slice(Pos.start(c.clause.getBody()),
                    Pos.end(c.clause.getBody())), 80) + ") and the loop moves on. Log it at DEBUG if the records are not enough.");
            return;
        }
        JavaEdits.LoggerPlan logger = ctx.logger(m);
        int open = Pos.start(c.clause.getBody());
        int close = Pos.end(c.clause.getBody()) - 1;
        String tryBody = Text.oneLine(f.slice(Pos.start(c.tryStatement.getBody()), Pos.end(c.tryStatement.getBody())), 120);
        String comment = comments(f.slice(open, close + 1));
        b.context(ctx.context(m, open + 1, "try " + tryBody + " catch (" + c.typeNames() + " " + c.param + ") "
                + Text.oneLine(f.slice(open, close + 1), 80), "MSG"));
        if (logger.name() == null) {
            b.suggestion("Log or rethrow " + c.param + "; " + m.owner().simpleName() + (logger.otherApi() != null
                    ? " logs through " + logger.otherApi() + ", not SLF4J: logast adds no lines there."
                    : " cannot get a logger field automatically."));
            return;
        }

        Set<String> inMdc = ctx.mdcInside(m);
        List<DomainValues.Value> values = new ArrayList<>(ctx.domain.fromVars(ctx.vars(m, open + 1)).stream()
                .filter(v -> !inMdc.contains(v.key())).toList());
        // #278: a team that logs exceptions by class only gets [errorType=…] instead of the exception (stack and message)
        boolean attach = ctx.policy.logging().attachExceptions();
        if (!attach) {
            values.add(new DomainValues.Value("errorType", c.param + ".getClass().getSimpleName()"));
        }
        String args = String.join("", values.stream().map(v -> ", " + v.expr()).toList());
        String call = logger.name() + "." + ctx.policy.logging().swallowedLevel() + "(\"{{MSG}}\"" + args + "{{ARGS}}"
                + (attach ? ", " + c.param : "") + ");";

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
        Slot slot = new Slot("What failed and what happens next, for " + c.typeNames() + " thrown by `" + tryBody + "`"
                + (comment.isEmpty() ? "" : " (code comment: \"" + comment + "\")") + ". The [k=v] suffix and the exception are added by the tool.",
                values.stream().map(v -> ctx.policy.logKey(v.key())).toList(), ctx.glossary.terms(), null,
                // #319: a catch with code of its own (a fallback value, a skip, an entry in a report) handles the outcome:
                // "Content type treated as plain text", "Image copy skipped"; only a catch that drops it is a failure
                fallback || ctx.phrasing.canonicalOf("failed") == null ? null : "failed",
                fallback ? null : ctx.phrasing.canonicalOf("failed"));
        // #292: a catch that falls back to a value says which, in words from the code; written marked // logast:wording
        String fellBack = fallback ? fallbackText(m.decl().getSimpleName(), c.clause.getBody().getStatements()) : null;
        b.slot("MSG", fellBack == null ? slot : slot.withGenerated(fellBack));
    }

    /**
     * #292: "Fell back to default currency" for a catch whose last statement returns or assigns a value named in the
     * code (a constant, a field, a variable, a literal, an empty collection); null when the code does not say.
     */
    static String fallbackText(String method, List<org.openrewrite.java.tree.Statement> body) {
        if (body.isEmpty()) {
            return null;
        }
        org.openrewrite.java.tree.Expression value = body.getLast() instanceof J.Return r ? r.getExpression()
                : body.getLast() instanceof J.Assignment a ? a.getAssignment() : null;
        String what = valueWords(value);
        if (what == null) {
            return null;
        }
        // a named value says what it is ("default currency"); a literal or an empty result needs its subject:
        // "Balance fell back to 0", from the method's name
        boolean named = value instanceof J.Identifier || value instanceof J.FieldAccess;
        String subject = Wording.phrase(method.replaceFirst("^(get|find|load|fetch|read|lookup|resolve)(?=[A-Z])", ""));
        return named || subject.isBlank() ? "Fell back to " + what : Wording.capital(subject) + " fell back to " + what;
    }

    private static String valueWords(org.openrewrite.java.tree.Expression v) {
        if (v instanceof J.Identifier id) {
            return named(id.getSimpleName());
        }
        if (v instanceof J.FieldAccess fa) {
            return named(fa.getSimpleName());
        }
        if (v instanceof J.Literal l) {
            if (l.getValue() == null) {
                return null;   // null says nothing a reader can use
            }
            return l.getValue() instanceof String str ? (str.isEmpty() ? "an empty text" : "\"" + Text.oneLine(str, 40) + "\"")
                    : String.valueOf(l.getValue());
        }
        if (v instanceof J.MethodInvocation mi && mi.getArguments().stream().allMatch(a -> a instanceof J.Empty)) {
            String n = mi.getSimpleName();
            if (n.equals("empty") || n.equals("of") || n.startsWith("empty")) {
                return "an empty " + (n.startsWith("empty") && n.length() > 5 ? Wording.words(n.substring(5)) : "result");
            }
        }
        return null;
    }

    /** {@code DEFAULT_CURRENCY} / {@code defaultCurrency} → "default currency"; one letter or {@code result} says nothing. */
    private static String named(String name) {
        String words = name.equals(name.toUpperCase(java.util.Locale.ROOT)) ? Wording.words(name.toLowerCase(java.util.Locale.ROOT))
                : Wording.words(name);
        return words.length() < 3 || Set.of("result", "res", "ret", "value", "out", "response").contains(words) ? null : words;
    }

    /** Calls that run a lambda once per item. */
    private static final Set<String> LAMBDA_LOOPS = Set.of("forEach", "forEachOrdered", "forEachRemaining");

    private static final java.util.regex.Pattern RECORDS = java.util.regex.Pattern.compile(
            "(?i)(report|skip|reject|record|collect|fail|error|warning|invalid)\\w*\\s*\\(|\\.(add|put|increment\\w*)\\(");

    /** G4: the catch sits in a loop, records the case (a call on a report, an add to a list) and continues. */
    private static boolean recordedInLoop(MethodInfo m, CatchInfo c) {
        java.util.List<org.openrewrite.java.tree.Statement> body = c.clause.getBody().getStatements();
        if (body.isEmpty()) {
            return false;
        }
        String text = c.clause.getBody().printTrimmed();
        if (!RECORDS.matcher(text).find()) {
            return false;
        }
        int at = Pos.start(c.clause);
        boolean[] loop = {false};
        boolean[] lambdaLoop = {false};
        new org.openrewrite.java.JavaIsoVisitor<Integer>() {
            @Override
            public org.openrewrite.java.tree.Statement visitStatement(org.openrewrite.java.tree.Statement st, Integer p) {
                if ((st instanceof J.ForEachLoop || st instanceof J.ForLoop || st instanceof J.WhileLoop || st instanceof J.DoWhileLoop)
                        && at >= Pos.start(st) && at < Pos.end(st)) {
                    loop[0] = true;
                }
                return super.visitStatement(st, p);
            }

            @Override
            public J.MethodInvocation visitMethodInvocation(J.MethodInvocation mi, Integer p) {
                // #323 H2: rows.forEach(row -> { try … catch … { report.skipped(..); } }): one item per call of the lambda
                if (LAMBDA_LOOPS.contains(mi.getSimpleName()) && mi.getArguments().stream().anyMatch(a -> a instanceof J.Lambda l
                        && at >= Pos.start(l) && at < Pos.end(l))) {
                    lambdaLoop[0] = true;
                }
                return super.visitMethodInvocation(mi, p);
            }
        }.visit(m.decl().getBody(), 0);
        // moving on: continue in a loop, the end of the loop body, or (in a forEach lambda) a return or the lambda's end
        boolean moves = body.getLast() instanceof J.Continue || insideLoopTail(m, c)
                || lambdaLoop[0] && body.getLast() instanceof J.Return ret && ret.getExpression() == null;
        return moves && (loop[0] || lambdaLoop[0]);
    }

    /** The try is the last statement of the loop's body: after the catch the loop goes on anyway. */
    private static boolean insideLoopTail(MethodInfo m, CatchInfo c) {
        boolean[] tail = {false};
        new org.openrewrite.java.JavaIsoVisitor<Integer>() {
            @Override
            public J.Block visitBlock(J.Block block, Integer p) {
                if (!block.getStatements().isEmpty() && block.getStatements().getLast() == c.tryStatement) {
                    tail[0] = true;
                }
                return super.visitBlock(block, p);
            }
        }.visit(m.decl().getBody(), 0);
        return tail[0];
    }

    /** A callee (called inside this try) that logs and rethrows a type this catch handles. */    /** A callee (called inside this try) that logs and rethrows a type this catch handles. */
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
