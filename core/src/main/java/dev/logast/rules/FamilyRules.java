package dev.logast.rules;

import dev.logast.model.Level;
import dev.logast.model.LogCall;
import dev.logast.model.MethodInfo;
import dev.logast.orders.Edit;
import dev.logast.parse.Pos;
import dev.logast.parse.SourceFileInfo;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.J;

import java.util.*;

/**
 * LIFECYCLE_UNEVEN: implementations of one abstract or interface method (job variants, strategies) log their
 * lifecycle unevenly: one says started and completed, another only completed, a third nothing. The callee owns its
 * logs, so each implementation gets the lines it misses in its own body, in its own words where it has a subject
 * ("Calculation completed" → "Calculation started"), else from its class name, marked // logast:wording.
 */
final class FamilyRules {
    private static final List<String> EVENTS = List.of("started", "completed");

    private final RuleContext ctx;

    FamilyRules(RuleContext ctx) {
        this.ctx = ctx;
    }

    void run() {
        if (!ctx.enabled(Rules.LIFECYCLE_UNEVEN)) {
            return;
        }
        Set<String> done = new HashSet<>();
        for (MethodInfo base : ctx.a.index.methods()) {
            if (base.hasBody() || base.decl().getMethodType() == null) {
                continue;   // only abstract / interface methods define a family
            }
            List<MethodInfo> impls = ctx.a.index.resolve(base.decl().getMethodType()).stream()
                    .filter(m -> !m.key().equals(base.key()) && m.decl().getBody() != null && done.add(m.key()))
                    .filter(m -> !m.owner().fqn().startsWith(ctx.a.supportPackage + "."))
                    .toList();
            if (impls.size() < 2) {
                continue;
            }
            Map<String, Map<String, String>> lifecycle = new LinkedHashMap<>();   // impl -> event -> subject
            impls.forEach(m -> lifecycle.put(m.key(), events(m)));
            Set<String> wanted = new LinkedHashSet<>();
            lifecycle.values().forEach(ev -> wanted.addAll(ev.keySet()));
            if (wanted.isEmpty()) {
                continue;   // none of them logs a lifecycle: nothing to even out
            }
            for (MethodInfo m : impls) {
                Map<String, String> has = lifecycle.get(m.key());
                List<String> missing = EVENTS.stream().filter(wanted::contains).filter(e -> !has.containsKey(e)).toList();
                if (!missing.isEmpty()) {
                    even(base, m, has, missing, impls.size());
                }
            }
        }
    }

    /** Lifecycle events the method logs at INFO+ on its normal path, with their subject (by role or by wording). */
    private Map<String, String> events(MethodInfo m) {
        Map<String, String> out = new LinkedHashMap<>();
        CoverageRules.roles(m).forEach(role -> out.put(role, ""));
        for (LogCall lc : m.facts().logs) {
            if (lc.level().compareTo(Level.INFO) < 0 || lc.inCatch() != null || !(lc.template() instanceof J.Literal l)
                    || !(l.getValue() instanceof String raw)) {
                continue;
            }
            String text = MessageTemplate.compliantBase(raw).strip();
            String event = ctx.phrasing.eventOf(text);
            if (event != null && EVENTS.contains(event)) {
                String subject = ctx.phrasing.subjectOf(text);
                out.merge(event, subject == null ? "" : subject, (had, now) -> had.isBlank() ? now : had);
            }
        }
        return out;
    }

    private void even(MethodInfo base, MethodInfo m, Map<String, String> has, List<String> missing, int siblings) {
        SourceFileInfo f = m.file();
        String text = f.text();
        int line = m.line();
        if (ctx.ignored(f, line, Rules.LIFECYCLE_UNEVEN)) {
            return;
        }
        int[] inner = JavaEdits.blockInner(text, m.decl().getBody());
        JavaEdits.LoggerPlan logger = ctx.logger(m.owner());
        if (inner == null || logger.name() == null) {
            return;
        }
        String subject = has.values().stream().filter(s -> !s.isBlank()).findFirst()
                .orElse(classWords(m.owner().simpleName()));
        String indent = Text.indentAt(text, Pos.start(m.decl().getBody())) + Text.indentUnit(text);
        boolean returns = hasReturn(m);
        OrderBuilder b = ctx.order(Rules.LIFECYCLE_UNEVEN, m, line, "lifecycle")
                .title(m.label() + " is one of " + siblings + " implementations of " + base.label() + " and the only ones without "
                        + String.join(" / ", missing) + " lines: every implementation logs its own lifecycle the same way");
        List<String> added = new ArrayList<>();
        StringBuilder top = new StringBuilder();
        StringBuilder bottom = new StringBuilder();
        for (String event : missing) {
            String message = ctx.phrasing.canonicalOf(event) == null ? subject + " " + event
                    : ctx.phrasing.canonicalOf(event).replace("{subject}", subject);
            String stmt = indent + logger.name() + ".info(" + Text.javaString(message) + "); " + DbRules.WORDING_MARKER + "\n";
            if (event.equals("started")) {
                top.append(stmt);
                added.add(message);
            } else if (!returns) {
                bottom.append(stmt);
                added.add(message);
            }
        }
        if (inner[0] == inner[1]) {
            // empty body: one insertion keeps started before completed
            b.edit(Edit.insert(f.relPath(), inner[0], top.toString() + bottom));
        } else {
            if (!top.isEmpty()) {
                b.edit(Edit.insert(f.relPath(), inner[0], top.toString()));
            }
            if (!bottom.isEmpty()) {
                b.edit(Edit.insert(f.relPath(), inner[1], bottom.toString()));
            }
        }
        if (added.isEmpty()) {
            ctx.drop(b);
            return;
        }
        b.edits(logger.edits());
        b.suggestion("Adds " + String.join(", ", added.stream().map(s -> "\"" + s + "\"").toList())
                + (returns && missing.contains("completed") ? "; the method returns early, so the completed line is left to you" : "")
                + ". The wording is marked " + DbRules.WORDING_MARKER + " (fills.json asks for better words).");
        b.context(ctx.context(m, inner[0], Text.oneLine(text.substring(Pos.start(m.decl()), Math.min(inner[0] + 120, text.length())), 160), null));
    }

    private static boolean hasReturn(MethodInfo m) {
        boolean[] found = {false};
        new JavaIsoVisitor<Integer>() {
            @Override
            public J.Lambda visitLambda(J.Lambda l, Integer p) {
                return l;
            }

            @Override
            public J.Return visitReturn(J.Return r, Integer p) {
                found[0] = true;
                return r;
            }
        }.visit(m.decl().getBody(), 0);
        return found[0];
    }

    /** "ExportJob" → "Export job". */
    static String classWords(String simpleName) {
        String w = simpleName.replaceAll("([a-z0-9])([A-Z])", "$1 $2").toLowerCase(Locale.ROOT);
        return Character.toUpperCase(w.charAt(0)) + w.substring(1);
    }
}
