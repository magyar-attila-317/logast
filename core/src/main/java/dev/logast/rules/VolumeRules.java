package dev.logast.rules;

import dev.logast.graph.CallGraph;
import dev.logast.model.Entrypoint;
import dev.logast.model.Level;
import dev.logast.model.LogCall;
import dev.logast.model.MethodInfo;
import dev.logast.parse.Pos;

import java.util.*;

/**
 * Log noise. Never estimates traffic:
 * <ul>
 *   <li>LOG_IN_LOOP (always): an INFO line per collection element on the normal path; its volume is calls ×
 *       collection size (WARN/ERROR and catch blocks are per failure, which is what you want);</li>
 *   <li>LOG_VOLUME (only with provided traffic): lines/day = sum of the rates of the entrypoints that reach the
 *       statement; INFO+ statements over {@code levels.volumeBudget} are reported, loops marked "× items".</li>
 * </ul>
 * Logs inside catch blocks are skipped for LOG_VOLUME: they run only on failure, at an unknown share of calls.
 */
final class VolumeRules {
    private final RuleContext ctx;

    VolumeRules(RuleContext ctx) {
        this.ctx = ctx;
    }

    void run() {
        boolean traffic = ctx.a.traffic.known() && ctx.enabled(Rules.LOG_VOLUME);
        boolean loopRuleAlreadyFixes = ctx.policy.levels().noInfoInLoops() && ctx.enabled(Rules.LOG_LEVEL);
        for (MethodInfo m : ctx.methods()) {
            List<LogCall> logs = m.facts().logs;
            if (logs.isEmpty()) {
                continue;
            }
            Map<String, Double> rates = traffic ? rates(m) : Map.of();
            double perDay = rates.values().stream().mapToDouble(Double::doubleValue).sum();
            for (int i = 0; i < logs.size(); i++) {
                LogCall lc = logs.get(i);
                if (lc.level().compareTo(Level.INFO) < 0) {
                    continue;
                }
                int line = Pos.line(lc.call());
                boolean over = traffic && lc.inCatch() == null && perDay >= ctx.policy.levels().volumeBudget();
                if (over && !ctx.ignored(m.file(), line, Rules.LOG_VOLUME)) {
                    String top = String.join(", ", rates.entrySet().stream()
                            .sorted(Map.Entry.<String, Double>comparingByValue().reversed()).limit(3)
                            .map(e -> e.getKey() + " " + human(e.getValue()) + "/day").toList());
                    ctx.order(Rules.LOG_VOLUME, m, line, "volume" + i)
                            .title(lc.level() + " log at ~" + human(perDay) + " lines/day"
                                    + (lc.loopBound() > 0 ? " × " + lc.loopBound() + " (fixed loop)" : lc.inLoop() ? " × items per call" : "")
                                    + " (budget " + human(ctx.policy.levels().volumeBudget()) + "; from " + ctx.a.traffic.source() + ")")
                            .suggestion("Reached from " + top + ". " + (lc.inLoop()
                                    ? "Log one summary line after the loop (count, ids in a [k=v]) and move the per-item line to DEBUG."
                                    : "Move it to DEBUG, log once per batch, or rate-limit it; keep INFO for state changes people act on."))
                            .context(ctx.context(m, Pos.start(lc.call()), Text.oneLine(lc.call().printTrimmed(), 160), null));
                } else if (lc.inLargeLoop(ctx.policy.levels().boundedLoopMax()) && lc.level() == Level.INFO
                        && lc.inCatch() == null // per-item failures are fine
                        && !loopRuleAlreadyFixes && ctx.enabled(Rules.LOG_IN_LOOP)
                        && !ctx.ignored(m.file(), line, Rules.LOG_IN_LOOP)) {
                    ctx.order(Rules.LOG_IN_LOOP, m, line, "loop" + i)
                            .title("INFO log once per collection element: volume grows with the collection size")
                            .suggestion("If the collection can be large, log one summary line after the loop and move this one to DEBUG. "
                                    + "Provide .logast/traffic.yaml to see real lines/day (LOG_VOLUME).")
                            .context(ctx.context(m, Pos.start(lc.call()), Text.oneLine(lc.call().printTrimmed(), 160), null));
                }
            }
        }
    }

    /** Provided rate of every entrypoint that reaches {@code m} (entrypoints without data are left out). */
    private Map<String, Double> rates(MethodInfo m) {
        Map<String, Double> out = new LinkedHashMap<>();
        for (Entrypoint ep : ctx.a.paths.entrypointsReaching(m)) {
            Double r = ctx.a.traffic.perDay(ep.label());
            if (r != null) {
                out.put(ep.label(), r);
            }
        }
        return out;
    }

    static String human(double v) {
        if (v >= 1_000_000) {
            return String.format(Locale.ROOT, "%.1fM", v / 1_000_000);
        }
        if (v >= 1_000) {
            return String.format(Locale.ROOT, "%.0fk", v / 1_000);
        }
        return String.format(Locale.ROOT, "%.0f", v);
    }
}
