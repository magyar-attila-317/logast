package dev.logast.rules;

import dev.logast.model.Entrypoint;
import dev.logast.model.LogCall;
import dev.logast.model.MethodInfo;
import dev.logast.parse.Pos;
import dev.logast.policy.Policy;

import java.util.*;

/**
 * AREA_MDC_MISSING: an area (policy.areas, by package) requires MDC keys on every log line, but paths into its
 * logging methods do not set them. One order per (area, key) with every affected method as a location: a missing
 * key is a systemic gap (set it once where work starts, e.g. a filter), not hundreds of local ones. Report-only.
 */
final class AreaRules {
    private final RuleContext ctx;

    AreaRules(RuleContext ctx) {
        this.ctx = ctx;
    }

    void run() {
        if (!ctx.enabled(Rules.AREA_MDC_MISSING) || ctx.policy.areas().isEmpty()) {
            return;
        }
        Map<String, List<MethodInfo>> byAreaKey = new TreeMap<>();
        for (MethodInfo m : ctx.methods()) {
            String area = ctx.area(m);
            Policy.Area def = area == null ? null : ctx.policy.areas().get(area);
            if (def == null || def.requiredMdc().isEmpty() || m.facts().logs.isEmpty() || !ctx.a.paths.reachable(m)) {
                continue;
            }
            int line = Pos.line(m.facts().logs.getFirst().call());
            for (String key : def.requiredMdc().stream().map(ctx.policy::styledKey).toList()) {
                if (!ctx.mdcInside(m).contains(key) && !ctx.ignored(m.file(), line, Rules.AREA_MDC_MISSING)) {
                    byAreaKey.computeIfAbsent(area + "|" + key, k -> new ArrayList<>()).add(m);
                }
            }
        }
        for (var e : byAreaKey.entrySet()) {
            String area = e.getKey().substring(0, e.getKey().indexOf('|'));
            String key = e.getKey().substring(e.getKey().indexOf('|') + 1);
            List<MethodInfo> methods = e.getValue();
            MethodInfo first = methods.getFirst();
            LogCall firstLog = first.facts().logs.getFirst();
            Map<String, Long> entrypoints = new TreeMap<>();
            for (MethodInfo m : methods) {
                for (Entrypoint ep : ctx.a.paths.entrypointsReaching(m)) {
                    entrypoints.merge(ep.label(), 1L, Long::sum);
                }
            }
            String top = String.join(", ", entrypoints.entrySet().stream()
                    .sorted(Map.Entry.<String, Long>comparingByValue().reversed()).limit(5).map(Map.Entry::getKey).toList());
            OrderBuilder b = ctx.order(Rules.AREA_MDC_MISSING, "area|" + area + "|" + key)
                    .at(first.file().relPath(), Pos.line(firstLog.call()))
                    .title("'" + key + "' is required on '" + area + "' logs but missing in " + methods.size()
                            + " logging method(s) of the area")
                    .suggestion("Set " + key + " once where work enters '" + area + "' (most affected entrypoints: " + top
                            + "), e.g. MdcScope in a filter/interceptor or at the entrypoint; or adjust areas." + area + ".requiredMdc.");
            for (MethodInfo m : methods.subList(1, methods.size())) {
                b.location(m.file().relPath(), Pos.line(m.facts().logs.getFirst().call()), Rules.AREA_MDC_MISSING);
            }
            b.method = first.label();
        }
    }
}
