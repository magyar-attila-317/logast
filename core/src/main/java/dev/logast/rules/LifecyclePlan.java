package dev.logast.rules;

import dev.logast.graph.CallGraph;
import dev.logast.model.Entrypoint;
import dev.logast.model.Level;
import dev.logast.model.LogCall;
import dev.logast.model.MethodInfo;
import dev.logast.parse.Pos;
import org.openrewrite.java.tree.J;

import java.util.*;

/**
 * Which lifecycle lines every flow has and who owns them, decided in one place (docs/pipeline-plan.md, "Ownership").
 * The rules that add start/outcome lines read it instead of each deciding on their own; `logast lifecycle` prints it.
 * <ul>
 *   <li>a run (@Scheduled, batch): started and completed in its own method;</li>
 *   <li>a message or event: one outcome; an observation wrapper's runtime line (completed/failed with duration) is it;</li>
 *   <li>an HTTP endpoint: access logs and http.server.requests are its outcome; a change-making endpoint whose own flow
 *       logs nothing gets a started line (ENTRYPOINT_SILENT);</li>
 *   <li>implementations of one abstract/interface method log the same lifecycle, each in its own body.</li>
 * </ul>
 * Lines count by role (first / last INFO statement) or by wording ("X started", "X completed").
 */
public final class LifecyclePlan {
    public enum Kind { RUN, MESSAGE, HTTP }

    /**
     * @param startOwner   who writes the started line: "the method", "ENTRYPOINT_SILENT", or "not needed"
     * @param outcomeOwner who writes the outcome: "the method", "observation wrapper (runtime line)", "access log (HTTP)"
     * @param missing      lines a rule will add: "started", "completed"
     */
    public record Flow(String label, String method, String location, Kind kind, boolean started, boolean outcome,
                       String startOwner, String outcomeOwner, List<String> missing) {
    }

    /** Implementations of one abstract method and the lifecycle lines each has (event → subject) and misses. */
    public record Family(String base, Map<String, Map<String, String>> has, Map<String, List<String>> missing) {
    }

    static final List<String> EVENTS = List.of("started", "completed");

    private final RuleContext ctx;

    LifecyclePlan(RuleContext ctx) {
        this.ctx = ctx;
    }

    public static Kind kindOf(Entrypoint ep) {
        return switch (ep.kind()) {
            case SCHEDULED, BATCH, MAIN -> Kind.RUN;
            case HTTP -> Kind.HTTP;
            default -> Kind.MESSAGE;
        };
    }

    /** The flow of one entrypoint: what it has, who owns what, what is missing. */
    public Flow flow(Entrypoint ep) {
        MethodInfo m = ep.method();
        Kind kind = kindOf(ep);
        Set<String> roles = roles(m);
        boolean hasStart = roles.contains("started") || m.facts().logs.stream().anyMatch(l -> "started".equals(event(l)) && info(l));
        boolean hasOutcome = roles.contains("completed") || m.facts().logs.stream()
                .anyMatch(l -> event(l) != null && Set.of("completed", "failed", "skipped").contains(event(l)) && info(l));
        boolean wrapper = wrapperLogsOutcome(m);
        boolean silent = ctx.silentPlanned.contains(m.key());
        String startOwner;
        String outcomeOwner;
        List<String> missing = new ArrayList<>();
        switch (kind) {
            case RUN -> {
                startOwner = silent ? "ENTRYPOINT_SILENT" : "the method";
                outcomeOwner = wrapper ? "observation wrapper (runtime line)" : "the method";
                if (!hasStart && !silent) {
                    missing.add("started");   // a wrapper covers the outcome, not the start of a run
                }
                if (!hasOutcome && !wrapper) {
                    missing.add("completed");
                }
            }
            case MESSAGE -> {
                startOwner = "not needed (one outcome per message)";
                outcomeOwner = wrapper ? "observation wrapper (runtime line)" : "the method";
                if (!hasOutcome && !wrapper) {
                    missing.add("completed");
                }
            }
            default -> {
                startOwner = silent ? "ENTRYPOINT_SILENT" : "not needed";
                outcomeOwner = "access log / http.server.requests";
            }
        }
        return new Flow(ep.label(), m.label(), m.file().relPath() + ":" + m.line(), kind, hasStart,
                hasOutcome || wrapper || kind == Kind.HTTP,   // HTTP: the access log is the outcome
                startOwner, outcomeOwner, missing);
    }

    /** Implementation families whose members log their lifecycle unevenly. */
    public List<Family> families() {
        List<Family> out = new ArrayList<>();
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
            Map<String, Map<String, String>> has = new LinkedHashMap<>();   // impl -> event -> subject
            impls.forEach(m -> has.put(m.key(), events(m)));
            Set<String> wanted = new LinkedHashSet<>();
            has.values().forEach(ev -> wanted.addAll(ev.keySet()));
            if (wanted.isEmpty()) {
                continue;   // none of them logs a lifecycle: nothing to even out
            }
            Map<String, List<String>> missing = new LinkedHashMap<>();
            for (MethodInfo m : impls) {
                List<String> miss = EVENTS.stream().filter(wanted::contains).filter(e -> !has.get(m.key()).containsKey(e)).toList();
                if (!miss.isEmpty()) {
                    missing.put(m.key(), miss);
                }
            }
            out.add(new Family(base.key(), has, missing));
        }
        return out;
    }

    /** Lifecycle events the method logs at INFO+ on its normal path, with their subject (by role or by wording). */
    Map<String, String> events(MethodInfo m) {
        Map<String, String> out = new LinkedHashMap<>();
        roles(m).forEach(role -> out.put(role, ""));
        for (LogCall lc : m.facts().logs) {
            if (!info(lc) || lc.inCatch() != null || !(lc.template() instanceof J.Literal l) || !(l.getValue() instanceof String raw)) {
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

    /** Lines by role: the first INFO statement is "started", the last (or the one before a final return) "completed". */
    static Set<String> roles(MethodInfo m) {
        return CoverageRules.roles(m);
    }

    /** An ObservedContext wrapper (existing, or planned in this scan) logs the outcome itself, unless it is quiet. */
    boolean wrapperLogsOutcome(MethodInfo m) {
        String body = m.decl().getBody() == null ? "" : m.file().slice(Pos.start(m.decl().getBody()), Pos.end(m.decl().getBody()));
        return ctx.wrappedMethods.contains(m.key()) || body.contains("ObservedContext.entrypoint(") && !body.contains(".quiet()");
    }

    /**
     * Whether the entrypoint's own flow logs at {@code min}+: a line on the normal path of the method or of a method at
     * most {@link EntrypointRules#OWN_FLOW_DEPTH} calls below it. Deeper lines sit in shared code many flows reach.
     */
    static boolean ownFlowLogs(RuleContext ctx, MethodInfo root, Level min) {
        Deque<Map.Entry<MethodInfo, Integer>> queue = new ArrayDeque<>(List.of(Map.entry(root, 0)));
        Set<String> seen = new HashSet<>();
        while (!queue.isEmpty()) {
            var cur = queue.poll();
            MethodInfo m = cur.getKey();
            if (!seen.add(m.key())) {
                continue;
            }
            if (m.facts().logs.stream().anyMatch(l -> l.level().compareTo(min) >= 0 && l.inCatch() == null)) {
                return true;
            }
            if (cur.getValue() < EntrypointRules.OWN_FLOW_DEPTH) {
                for (CallGraph.Edge e : ctx.a.graph.out(m)) {
                    queue.add(Map.entry(e.to(), cur.getValue() + 1));
                }
            }
        }
        return false;
    }

    private static boolean info(LogCall l) {
        return l.level().compareTo(Level.INFO) >= 0;
    }

    private String event(LogCall lc) {
        String text = MessageTemplate.text(lc.template());
        if (text == null) {
            return null;
        }
        String plain = MessageTemplate.compliantKeys(text) != null ? MessageTemplate.compliantBase(text) : MessageTemplate.stripPlaceholders(text);
        return ctx.phrasing.eventOf(plain);
    }
}
