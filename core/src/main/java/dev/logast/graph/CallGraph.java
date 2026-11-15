package dev.logast.graph;

import dev.logast.model.Annotations;
import dev.logast.model.CallSite;
import dev.logast.model.MethodInfo;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.TypeUtils;

import java.util.*;

public final class CallGraph {
    public enum Async { NONE, MANAGED, RAW }

    /** @param ambiguous the call site dispatches to more than one source implementation */
    public record Edge(MethodInfo from, MethodInfo to, CallSite site, boolean ambiguous, Async async) {
    }

    private final Map<String, List<Edge>> out = new HashMap<>();
    private final Map<String, List<Edge>> in = new HashMap<>();
    private int ambiguousSites;
    private int edgeCount;
    private final List<MethodInfo> roots = new ArrayList<>();

    public static CallGraph build(CodeIndex index) {
        CallGraph g = new CallGraph();
        for (MethodInfo m : index.methods()) {
            for (CallSite site : m.facts().calls) {
                List<MethodInfo> targets = index.resolve(site.type());
                if (targets.size() > 1) {
                    g.ambiguousSites++;
                }
                for (MethodInfo t : targets) {
                    Async async = site.async() ? (site.asyncManaged() ? Async.MANAGED : Async.RAW)
                            : t.hasAnnotation("org.springframework.scheduling.annotation.Async") ? Async.MANAGED : Async.NONE;
                    Edge e = new Edge(m, t, site, targets.size() > 1, async);
                    g.out.computeIfAbsent(m.key(), k -> new ArrayList<>()).add(e);
                    g.in.computeIfAbsent(t.key(), k -> new ArrayList<>()).add(e);
                    g.edgeCount++;
                }
            }
        }
        g.events(index);
        for (MethodInfo m : index.methods()) {
            if (g.out.containsKey(m.key()) && !g.in.containsKey(m.key())) {
                g.roots.add(m);
            }
        }
        return g;
    }

    private static final String PUBLISHER = "org.springframework.context.ApplicationEventPublisher";
    private static final List<String> LISTENERS = List.of("org.springframework.context.event.EventListener",
            "org.springframework.transaction.event.TransactionalEventListener");

    /**
     * Spring's event dispatch, which is not a call in the code: {@code publisher.publishEvent(new OrderPlaced(…))}
     * reaches every {@code @EventListener} whose parameter type the event fits. Synchronous listeners run in the
     * publisher's thread (its MDC and span); {@code @Async} ones on another thread, through the TaskDecorator.
     */
    private void events(CodeIndex index) {
        List<MethodInfo> listeners = index.methods().stream()
                .filter(l -> l.hasBody() && !l.type().getParameterTypes().isEmpty()
                        && LISTENERS.stream().anyMatch(a -> l.decl().getLeadingAnnotations().stream().anyMatch(x -> Annotations.is(x, a))))
                .toList();
        if (listeners.isEmpty()) {
            return;
        }
        for (MethodInfo m : index.methods()) {
            for (CallSite site : m.facts().calls) {
                if (!site.type().getName().equals("publishEvent") || !(site.tree() instanceof J.MethodInvocation mi)
                        || !TypeUtils.isAssignableTo(PUBLISHER, site.type().getDeclaringType())) {
                    continue;
                }
                List<Expression> args = mi.getArguments().stream().filter(a -> !(a instanceof J.Empty)).toList();
                JavaType event = args.isEmpty() ? null : args.getFirst().getType();
                if (event == null || event instanceof JavaType.Unknown) {
                    continue;
                }
                List<MethodInfo> targets = listeners.stream().filter(l -> TypeUtils.isAssignableTo(
                        Types.erasure(l.type().getParameterTypes().getFirst()), event)).toList();
                for (MethodInfo t : targets) {
                    Async async = t.hasAnnotation("org.springframework.scheduling.annotation.Async") ? Async.MANAGED : Async.NONE;
                    Edge e = new Edge(m, t, site, targets.size() > 1, async);
                    out.computeIfAbsent(m.key(), k -> new ArrayList<>()).add(e);
                    in.computeIfAbsent(t.key(), k -> new ArrayList<>()).add(e);
                    edgeCount++;
                }
            }
        }
    }

    public List<Edge> out(MethodInfo m) {
        return out.getOrDefault(m.key(), List.of());
    }

    /** Methods that call others and that nothing in the analysed code calls: where unknown flows start. */
    public List<MethodInfo> roots() {
        return roots;
    }

    public List<Edge> in(MethodInfo m) {
        return in.getOrDefault(m.key(), List.of());
    }

    public int edgeCount() {
        return edgeCount;
    }

    public int ambiguousSites() {
        return ambiguousSites;
    }
}
