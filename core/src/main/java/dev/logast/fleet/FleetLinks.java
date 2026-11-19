package dev.logast.fleet;

import dev.logast.rules.MetricsMap;

import java.util.*;
import java.util.stream.Collectors;

/**
 * What only a comparison of services shows (#113):
 * <ul>
 *   <li>trace continuity: a call of one service that reaches an entrypoint of another (same path and method, same topic
 *   or queue), and which side, if any, drops the trace;</li>
 *   <li>one outcome, several metric names: the same logged outcome ("Order shipped") counted under different meters in
 *   different services. Candidates only: nothing is merged.</li>
 * </ul>
 */
public final class FleetLinks {
    /**
     * @param breaks "caller", "callee" or both; empty when the trace goes on
     * @param note   how the call was matched when that is less than certain (path only, the base URL being unknown)
     */
    public record Trace(String caller, ServiceSurface.Call call, String callee, ServiceSurface.Entry entry,
                        List<String> breaks, String note) {
    }

    public record MetricUse(String service, String meter, String how, String message, String file, int line) {
    }

    /** @param outcome the subject and outcome all uses log, as one of them writes it ("Order shipped") */
    public record MetricNames(String outcome, List<MetricUse> uses) {
    }

    public record Result(List<Trace> trace, List<MetricNames> metrics) {
        public Result {
            trace = trace == null ? List.of() : List.copyOf(trace);
            metrics = metrics == null ? List.of() : List.copyOf(metrics);
        }

        public List<Trace> broken() {
            return trace.stream().filter(t -> !t.breaks().isEmpty()).toList();
        }
    }

    private FleetLinks() {
    }

    public static Result of(List<FleetReport.Service> services) {
        return new Result(trace(services), metrics(services));
    }

    // ---- trace continuity -------------------------------------------------------------------------------------------

    static List<Trace> trace(List<FleetReport.Service> services) {
        List<Trace> out = new ArrayList<>();
        for (FleetReport.Service a : services) {
            if (a.surface() == null) {
                continue;
            }
            for (ServiceSurface.Call call : a.surface().calls()) {
                for (FleetReport.Service b : services) {
                    if (b == a || b.surface() == null) {
                        continue;
                    }
                    Set<String> seen = new HashSet<>();   // one handler under several paths: the first match
                    for (ServiceSurface.Entry entry : b.surface().entries()) {
                        if (!matches(call, entry) || !seen.add(entry.file() + ":" + entry.line())) {
                            continue;
                        }
                        List<String> breaks = new ArrayList<>();
                        if (call.breaks() != null) {
                            breaks.add("caller");
                        }
                        if (entry.breaks() != null) {
                            breaks.add("callee");
                        }
                        String note = call.kind().equals("http") && call.base() == null
                                ? "base URL not in the code (property, variable or the client's base URL): matched on the path only"
                                : call.kind().equals("http") ? "base " + call.base() + ": matched on the path" : null;
                        out.add(new Trace(a.name(), call, b.name(), entry, breaks, note));
                    }
                }
            }
        }
        return out;
    }

    static boolean matches(ServiceSurface.Call call, ServiceSurface.Entry entry) {
        if (!call.kind().equals(entry.kind())) {
            return false;
        }
        if (!call.kind().equals("http")) {
            return call.target().equals(entry.target());
        }
        if (call.verb() != null && entry.verb() != null && !call.verb().equals(entry.verb())) {
            return false;
        }
        return samePath(call.target(), entry.target());
    }

    /** {@code /orders/{id}} matches {@code /orders/42}, {@code /orders/{orderId}} and {@code /orders/{?}} (a value the code does not know). */
    static boolean samePath(String call, String entry) {
        String[] c = segments(call);
        String[] e = segments(entry);
        if (c.length != e.length) {
            return false;
        }
        for (int i = 0; i < c.length; i++) {
            if (!c[i].equals(e[i]) && !variable(c[i]) && !variable(e[i])) {
                return false;
            }
        }
        return true;
    }

    private static String[] segments(String path) {
        return Arrays.stream(path.split("/")).filter(s -> !s.isEmpty()).toArray(String[]::new);
    }

    private static boolean variable(String segment) {
        return segment.startsWith("{") && segment.endsWith("}");
    }

    // ---- one outcome, several metric names --------------------------------------------------------------------------

    static List<MetricNames> metrics(List<FleetReport.Service> services) {
        Map<String, List<MetricUse>> byOutcome = new TreeMap<>();
        Map<String, String> wording = new HashMap<>();
        for (FleetReport.Service s : services) {
            if (s.surface() == null) {
                continue;
            }
            for (MetricsMap.Counted c : s.surface().counted()) {
                String key = FleetReport.normalize(c.subject()) + " " + c.event();
                wording.putIfAbsent(key, c.subject() + " " + c.event());
                byOutcome.computeIfAbsent(key, k -> new ArrayList<>())
                        .add(new MetricUse(s.name(), c.meter(), c.how(), c.message(), c.file(), c.line()));
            }
        }
        List<MetricNames> out = new ArrayList<>();
        byOutcome.forEach((key, uses) -> {
            Set<String> names = uses.stream().map(MetricUse::meter).collect(Collectors.toCollection(TreeSet::new));
            Set<String> where = uses.stream().map(MetricUse::service).collect(Collectors.toSet());
            if (names.size() > 1 && where.size() > 1) {
                out.add(new MetricNames(wording.get(key), uses));
            }
        });
        return out;
    }

    // ---- output -----------------------------------------------------------------------------------------------------

    /** Plain text for the terminal: the broken pairs with both places and the fix, then the pairs that continue, then the metric candidates. */
    public static String text(Result r) {
        StringBuilder sb = new StringBuilder();
        List<Trace> broken = r.broken();
        sb.append("Trace across services: ").append(r.trace().size()).append(r.trace().size() == 1 ? " call reaches" : " calls reach")
                .append(" another service, ").append(broken.size()).append(broken.size() == 1 ? " drops" : " drop").append(" the trace\n");
        for (Trace t : broken) {
            sb.append("  ").append(t.caller()).append(" -> ").append(t.callee()).append("  ").append(target(t.call())).append('\n');
            sb.append("    call   ").append(place(t.call().file(), t.call().line())).append("  ").append(t.call().client())
                    .append(", ").append(t.call().method()).append('\n');
            sb.append("    entry  ").append(place(t.entry().file(), t.entry().line())).append("  ").append(t.entry().label())
                    .append(", ").append(t.entry().method()).append('\n');
            if (t.call().breaks() != null) {
                sb.append("    breaks at the caller (").append(t.caller()).append("): ").append(t.call().breaks().what()).append('\n')
                        .append("      fix: ").append(fix(t.call().breaks(), t.caller())).append('\n');
            }
            if (t.entry().breaks() != null) {
                sb.append("    breaks at the callee (").append(t.callee()).append("): ").append(t.entry().breaks().what()).append('\n')
                        .append("      fix: ").append(fix(t.entry().breaks(), t.callee())).append('\n');
            }
            if (t.note() != null) {
                sb.append("    ").append(t.note()).append('\n');
            }
        }
        List<Trace> fine = r.trace().stream().filter(t -> t.breaks().isEmpty()).toList();
        if (!fine.isEmpty()) {
            sb.append("  the trace goes on:\n");
            for (Trace t : fine) {
                sb.append("    ").append(t.caller()).append(" -> ").append(t.callee()).append("  ").append(target(t.call()))
                        .append("  (").append(place(t.call().file(), t.call().line())).append(" -> ")
                        .append(place(t.entry().file(), t.entry().line())).append(")")
                        .append(t.call().kind().equals("http") && t.call().base() == null ? "  path only: base URL not in the code" : "")
                        .append('\n');
            }
        }
        sb.append("One outcome, several metric names: ").append(r.metrics().size())
                .append(r.metrics().size() == 1 ? " candidate" : " candidates").append(" (pick one name; nothing is merged)\n");
        for (MetricNames mn : r.metrics()) {
            sb.append("  \"").append(mn.outcome()).append("\" counted as ").append(mn.uses().stream()
                    .map(u -> u.meter() + " (" + u.service() + ")").distinct().collect(Collectors.joining(", "))).append('\n');
            for (MetricUse u : mn.uses()) {
                sb.append("    ").append(u.service()).append("  ").append(place(u.file(), u.line())).append("  \"").append(u.message())
                        .append("\" -> ").append(u.meter()).append(" (").append(u.how()).append(")\n");
            }
        }
        return sb.toString();
    }

    static String target(ServiceSurface.Call c) {
        return switch (c.kind()) {
            case "http" -> (c.verb() == null ? "" : c.verb() + " ") + c.target();
            case "kafka" -> "topic " + c.target();
            case "rabbit" -> "queue " + c.target();
            default -> "destination " + c.target();
        };
    }

    static String fix(ServiceSurface.Break b, String service) {
        return b.order() != null ? "order " + b.order() + " (" + b.rule() + ") in " + service + ": " + b.fix() : b.fix();
    }

    private static String place(String file, int line) {
        return file + ":" + line;
    }
}
