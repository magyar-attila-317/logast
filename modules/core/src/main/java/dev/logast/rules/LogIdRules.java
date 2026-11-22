package dev.logast.rules;

import dev.logast.model.Level;
import dev.logast.model.LogCall;
import dev.logast.model.MethodInfo;
import dev.logast.orders.Edit;
import dev.logast.parse.Pos;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.Statement;
import org.openrewrite.java.tree.TypeUtils;

import java.util.*;

/**
 * LOG_ID_MISSING (#120, logging pack, low): a WARN / ERROR line or an outcome line ("… completed" / "… failed") that
 * leaves out a business id the method has at hand: a parameter that is a vocabulary key, or the id a parameter carries
 * (its getter / record accessor, as {@link DomainValues#fromParams}). People search those lines by the id. Not when
 * MDC carries the key there (or a scope logast plans will), or the line logs the value already; only lines in the
 * house format (LOG_FORMAT rewrites the others first). One candidate: AUTO, the {@code [key={}]} suffix and its value;
 * several: a decision listing them.
 */
final class LogIdRules {
    private static final Set<String> OUTCOMES = Set.of("completed", "failed");

    private final RuleContext ctx;

    LogIdRules(RuleContext ctx) {
        this.ctx = ctx;
    }

    void run() {
        if (!ctx.enabled(Rules.LOG_ID_MISSING)) {
            return;
        }
        for (MethodInfo m : ctx.methods()) {
            if (m.decl().getBody() == null || m.facts().logs.isEmpty()) {
                continue;
            }
            List<DomainValues.Value> values = ctx.domain.fromParams(params(m));
            if (values.isEmpty()) {
                continue;
            }
            Set<String> inMdc = ctx.mdcInside(m);
            int ordinal = 0;
            for (LogCall lc : m.facts().logs) {
                ordinal++;
                if (lc.fluent() || !(lc.template() instanceof J.Literal l) || !(l.getValue() instanceof String raw)) {
                    continue;
                }
                List<String> keys = MessageTemplate.compliantKeys(raw);
                if (keys == null || !important(lc, MessageTemplate.compliantBase(raw))) {
                    continue;
                }
                Set<String> logged = new HashSet<>(keys);
                Set<String> loggedRoots = new HashSet<>();
                for (Expression a : lc.args()) {
                    String src = m.file().slice(Pos.start(a), Pos.end(a));
                    logged.add(src);
                    loggedRoots.add(root(src));
                }
                List<DomainValues.Value> missing = new ArrayList<>();
                for (DomainValues.Value v : values) {
                    String key = ctx.policy.logKey(v.key());
                    boolean accessor = v.expr().contains(".");
                    // the line identifies that object already (order.id() logged: not also order.customerId()); and an
                    // accessor of a parameter the method checks for null could throw right on the line that logs it
                    if (accessor && (loggedRoots.contains(root(v.expr())) || nullChecked(m, root(v.expr())))) {
                        continue;
                    }
                    boolean carried = !lc.offThread() && (inMdc.contains(v.key()) || ctx.plannedBy(m, v.key()) != null);
                    if (!carried && !logged.contains(key) && !logged.contains(v.key()) && !logged.contains(v.expr())) {
                        missing.add(v);
                    }
                }
                int line = Pos.line(lc.call());
                if (missing.isEmpty() || ctx.ignored(m.file(), line, Rules.LOG_ID_MISSING)) {
                    continue;
                }
                OrderBuilder b = ctx.order(Rules.LOG_ID_MISSING, m, line, "logid" + ordinal);
                List<String> names = missing.stream().map(v -> ctx.policy.logKey(v.key()) + " (" + v.expr() + ")").toList();
                if (missing.size() > 1) {
                    b.title(lc.level() + " line \"" + MessageTemplate.compliantBase(raw) + "\" leaves out the business ids "
                                    + m.label() + " has at hand: " + String.join(", ", names))
                            .suggestion("Log the one that identifies what failed or completed: add [key={}] and its value; "
                                    + "or put it into MDC where the flow starts, so every line carries it.")
                            .doneWhen("the line logs one of them, or MDC carries it here");
                    continue;
                }
                DomainValues.Value v = missing.getFirst();
                String key = ctx.policy.logKey(v.key());
                List<String> all = new ArrayList<>(keys);
                all.add(key);
                b.title(lc.level() + " line \"" + MessageTemplate.compliantBase(raw) + "\" leaves out " + key
                                + ", which " + m.label() + " has at hand (" + v.expr() + "): adds [" + key + "={}]")
                        .suggestion("People search these lines by the id; MDC does not carry it here.")
                        .edit(Edit.replace(m.file().relPath(), Pos.start(l), Pos.end(l),
                                Text.javaString(MessageTemplate.compliantBase(raw) + MessageTemplate.suffix(all))));
                // the value goes after the suffix's other values, before a trailing exception
                List<Expression> args = lc.args();
                Expression last = args.isEmpty() ? null : args.getLast();
                if (last != null && TypeUtils.isAssignableTo("java.lang.Throwable", last.getType())) {
                    b.edit(Edit.insert(m.file().relPath(), Pos.start(last), v.expr() + ", "));
                } else {
                    b.edit(Edit.insert(m.file().relPath(), Pos.end(last != null ? last : l), ", " + v.expr()));
                }
            }
        }
    }

    /** {@code order} of {@code order.id()}; the expression itself when it has no dot. */
    private static String root(String expr) {
        int dot = expr.indexOf('.');
        return dot < 0 ? expr : expr.substring(0, dot);
    }

    /** The method compares {@code name} with null (or asks Objects about it): it may be null here. */
    private static boolean nullChecked(MethodInfo m, String name) {
        String body = m.file().slice(Pos.start(m.decl().getBody()), Pos.end(m.decl().getBody()));
        return java.util.regex.Pattern.compile("\\b" + java.util.regex.Pattern.quote(name) + "\\s*[!=]=\\s*null\\b|\\bnull\\s*[!=]=\\s*"
                + java.util.regex.Pattern.quote(name) + "\\b|Objects\\.(isNull|nonNull|requireNonNull\\w*)\\(\\s*" + java.util.regex.Pattern.quote(name) + "\\b")
                .matcher(body).find();
    }

    /** WARN / ERROR, or a line that reports an outcome (completed / failed, in the house phrasing or a variant). */
    private boolean important(LogCall lc, String text) {
        if (lc.level().compareTo(Level.WARN) >= 0) {
            return true;
        }
        String event = ctx.phrasing.eventOf(text);
        return event != null && OUTCOMES.contains(event);
    }

    private static Map<String, JavaType> params(MethodInfo m) {
        Map<String, JavaType> out = new LinkedHashMap<>();
        for (Statement s : m.decl().getParameters()) {
            if (s instanceof J.VariableDeclarations vd) {
                vd.getVariables().forEach(v -> out.put(v.getSimpleName(), v.getType()));
            }
        }
        return out;
    }
}
