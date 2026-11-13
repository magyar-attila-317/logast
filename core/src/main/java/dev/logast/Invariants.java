package dev.logast;

import dev.logast.graph.PathContext;
import dev.logast.model.Level;
import dev.logast.model.LogCall;
import dev.logast.model.MdcOp;
import dev.logast.model.MethodInfo;
import dev.logast.parse.Pos;
import dev.logast.rules.MessageTemplate;
import dev.logast.rules.Phrasing;
import org.openrewrite.java.tree.J;

import java.util.*;

/**
 * Properties a migrated file must have, whatever the rules did: checked by {@code apply} on the files it changed,
 * before anything is written back. They do not come from logast's own output, so they catch wrong results that a
 * snapshot would happily keep.
 * <ul>
 *   <li>a value MDC carries is not logged again in the message ([couponId={}] below a couponId scope);</li>
 *   <li>no scope for a key that is already in MDC on entry (nested scope);</li>
 *   <li>at most one INFO "started" and one INFO "completed" line per method.</li>
 * </ul>
 */
public final class Invariants {
    private Invariants() {
    }

    /** One line per violation: "file:line  what". */
    public static List<String> check(Analysis a, Collection<String> files) {
        Set<String> scope = new HashSet<>(files);
        Phrasing phrasing = new Phrasing(a.policy);
        List<String> out = new ArrayList<>();
        PathContext paths = a.paths;
        for (MethodInfo m : a.index.methods()) {
            String file = m.file().relPath();
            if (!scope.contains(file) || m.owner().fqn().startsWith(a.supportPackage + ".")) {
                continue;
            }
            Set<String> inside = paths.mdcInside(m);
            Map<String, Integer> lifecycle = new HashMap<>();
            for (LogCall lc : m.facts().logs) {
                if (lc.offThread() || !(lc.template() instanceof J.Literal l) || !(l.getValue() instanceof String raw)) {
                    continue;
                }
                List<String> keys = MessageTemplate.compliantKeys(raw);
                if (keys != null) {
                    for (String k : keys) {
                        if (inside.contains(k)) {
                            out.add(file + ":" + Pos.line(lc.call()) + "  logs " + k + " in the message although MDC carries it");
                        }
                    }
                }
                String event = phrasing.eventOf(MessageTemplate.compliantBase(raw).strip());
                if (lc.level().compareTo(Level.INFO) >= 0 && lc.inCatch() == null && ("started".equals(event) || "completed".equals(event))) {
                    lifecycle.merge(event, 1, Integer::sum);
                }
            }
            lifecycle.forEach((event, n) -> {
                if (n > 1) {
                    out.add(file + ":" + m.line() + "  " + m.label() + " logs '" + event + "' " + n + " times");
                }
            });
            Set<String> onEntry = paths.mdcAtEntry(m);
            for (MdcOp op : m.facts().mdc) {
                if (op.kind() == MdcOp.Kind.PUT_CLOSEABLE && op.key() != null && onEntry.contains(op.key())) {
                    out.add(file + ":" + Pos.line(op.call()) + "  scopes " + op.key() + " although it is in MDC already (nested scope)");
                }
            }
        }
        Collections.sort(out);
        return out;
    }
}
