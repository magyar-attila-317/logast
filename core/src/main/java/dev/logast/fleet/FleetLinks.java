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
    /** How an HTTP call was matched to an entrypoint (#128). */
    public static final String CONFIRMED = "confirmed";
    public static final String PATH_ONLY = "pathOnly";
    public static final String AMBIGUOUS = "ambiguous";
    public static final String DIFFERENT_HOST = "differentHost";

    /** A service that has the path of an ambiguous call (or of one to another host), with its entrypoint. */
    public record Candidate(String service, ServiceSurface.Entry entry, boolean breaks) {
    }

    /**
     * @param callee     null for an ambiguous call (see {@code candidates})
     * @param entry      null for an ambiguous call
     * @param breaks     "caller", "callee" or both; empty when the trace goes on. An ambiguous call breaks at the callee
     *                   only when every candidate does
     * @param note       how the call was matched, in words ("confirmed: host billing", "ambiguous: billing or invoicing
     *                   have this path")
     * @param match      {@link #CONFIRMED} (the base URL's host names the callee), {@link #PATH_ONLY} (the host is not
     *                   known, one service has the path), {@link #AMBIGUOUS} (the host does not decide between the
     *                   services that have the path: one row, the candidates listed), {@link #DIFFERENT_HOST} (the host
     *                   names another service or none: not a link); null for messages, where every listener of the
     *                   topic or queue receives them
     * @param candidates for ambiguous and different-host calls: the services that have the path
     */
    public record Trace(String caller, ServiceSurface.Call call, String callee, ServiceSurface.Entry entry,
                        List<String> breaks, String note, String match, List<Candidate> candidates) {
        public Trace {
            breaks = breaks == null ? List.of() : List.copyOf(breaks);
            candidates = candidates == null ? List.of() : List.copyOf(candidates);
        }

        /** "billing", or "billing or invoicing" for an ambiguous call. */
        public String callees() {
            return callee != null ? callee
                    : candidates.stream().map(Candidate::service).distinct().collect(Collectors.joining(" or "));
        }
    }

    public record MetricUse(String service, String meter, String how, String message, String file, int line) {
    }

    /** @param outcome the subject and outcome all uses log, as one of them writes it ("Order shipped") */
    public record MetricNames(String outcome, List<MetricUse> uses) {
    }

    /**
     * @param otherHost calls whose path another service has while their host names a different one (or no service of
     *                  the fleet): not links, possibly stale URLs (#128)
     */
    public record Result(List<Trace> trace, List<MetricNames> metrics, List<Trace> otherHost) {
        public Result {
            trace = trace == null ? List.of() : List.copyOf(trace);
            metrics = metrics == null ? List.of() : List.copyOf(metrics);
            otherHost = otherHost == null ? List.of() : List.copyOf(otherHost);
        }

        public Result(List<Trace> trace, List<MetricNames> metrics) {
            this(trace, metrics, List.of());
        }

        public List<Trace> broken() {
            return trace.stream().filter(t -> !t.breaks().isEmpty()).toList();
        }
    }

    private FleetLinks() {
    }

    public static Result of(List<FleetReport.Service> services) {
        List<Trace> all = trace(services);
        return new Result(all.stream().filter(t -> !DIFFERENT_HOST.equals(t.match())).toList(), metrics(services),
                all.stream().filter(t -> DIFFERENT_HOST.equals(t.match())).toList());
    }

    // ---- trace continuity -------------------------------------------------------------------------------------------

    /** Every link, the calls to another host included (match {@link #DIFFERENT_HOST}). */
    static List<Trace> trace(List<FleetReport.Service> services) {
        List<Trace> out = new ArrayList<>();
        for (FleetReport.Service a : services) {
            if (a.surface() == null) {
                continue;
            }
            for (ServiceSurface.Call call : a.surface().calls()) {
                Map<FleetReport.Service, List<ServiceSurface.Entry>> hits = new LinkedHashMap<>();
                for (FleetReport.Service b : services) {
                    if (b == a || b.surface() == null) {
                        continue;
                    }
                    Set<String> seen = new HashSet<>();   // one handler under several paths: the first match
                    for (ServiceSurface.Entry entry : b.surface().entries()) {
                        if (matches(call, entry) && seen.add(entry.file() + ":" + entry.line())) {
                            hits.computeIfAbsent(b, k -> new ArrayList<>()).add(entry);
                        }
                    }
                }
                if (hits.isEmpty()) {
                    continue;
                }
                if (!call.kind().equals("http")) {   // every listener of the topic or queue receives the message
                    hits.forEach((b, entries) -> entries.forEach(e -> out.add(link(a, call, b, e, null, null))));
                } else {
                    http(a, call, hits, services, out);
                }
            }
        }
        return out;
    }

    /** #128: the host of the call's base URL decides between the services that have its path. */
    private static void http(FleetReport.Service a, ServiceSurface.Call call, Map<FleetReport.Service, List<ServiceSurface.Entry>> hits,
                             List<FleetReport.Service> services, List<Trace> out) {
        String host = host(call.base());
        String label = label(host);
        if (label != null) {
            if (named(label, a).isPresent()) {
                return;   // the caller's own name: a call inside the service
            }
            Map<FleetReport.Service, String> named = new LinkedHashMap<>();
            for (FleetReport.Service b : services) {
                if (b != a) {
                    named(label, b).ifPresent(how -> named.put(b, how));
                }
            }
            List<FleetReport.Service> withPath = hits.keySet().stream().filter(named::containsKey).toList();
            if (withPath.size() == 1) {
                FleetReport.Service b = withPath.getFirst();
                List<String> others = hits.keySet().stream().filter(x -> x != b).map(FleetReport.Service::name).toList();
                String note = "confirmed: host " + host + named.get(b)
                        + (others.isEmpty() ? "" : " (" + or(others, " and ") + (others.size() == 1 ? " has" : " have") + " this path too)");
                hits.get(b).forEach(e -> out.add(link(a, call, b, e, note, CONFIRMED)));
            } else if (withPath.size() > 1) {
                out.add(ambiguous(a, call, hits, "ambiguous: host " + host + " names "
                        + or(withPath.stream().map(FleetReport.Service::name).toList(), " and ") + ", which all have this path"));
            } else {
                List<Candidate> candidates = candidates(hits);
                String having = or(hits.keySet().stream().map(FleetReport.Service::name).toList(), " and ")
                        + (hits.size() == 1 ? " has" : " have");
                String note = named.isEmpty()
                        ? "different host: " + host + " names no service of the fleet; " + having + " this path (a stale URL, or a service outside the fleet?)"
                        : "different host: " + host + " is " + or(named.keySet().stream().map(FleetReport.Service::name).toList(), " and ")
                        + ", which has no such path; " + having + " it (a stale URL?)";
                out.add(new Trace(a.name(), call, null, null, List.of(), note, DIFFERENT_HOST, candidates));
            }
            return;
        }
        String why = call.base() == null ? "base URL not in the code" : "host " + host(call.base(), true) + " names no service";
        if (hits.size() == 1) {
            var only = hits.entrySet().iterator().next();
            only.getValue().forEach(e -> out.add(link(a, call, only.getKey(), e, "path only: " + why, PATH_ONLY)));
        } else {
            out.add(ambiguous(a, call, hits, "ambiguous: " + or(hits.keySet().stream().map(FleetReport.Service::name).toList(), " or ")
                    + " have this path (" + why + ")"));
        }
    }

    private static Trace link(FleetReport.Service a, ServiceSurface.Call call, FleetReport.Service b, ServiceSurface.Entry entry,
                              String note, String match) {
        List<String> breaks = new ArrayList<>();
        if (call.breaks() != null) {
            breaks.add("caller");
        }
        if (entry.breaks() != null) {
            breaks.add("callee");
        }
        return new Trace(a.name(), call, b.name(), entry, breaks, note, match, List.of());
    }

    /** One row for the services that have the path; it breaks at the callee only when every candidate does. */
    private static Trace ambiguous(FleetReport.Service a, ServiceSurface.Call call, Map<FleetReport.Service, List<ServiceSurface.Entry>> hits,
                                   String note) {
        List<Candidate> candidates = candidates(hits);
        List<String> breaks = new ArrayList<>();
        if (call.breaks() != null) {
            breaks.add("caller");
        }
        if (candidates.stream().allMatch(Candidate::breaks)) {
            breaks.add("callee");
        }
        return new Trace(a.name(), call, null, null, breaks, note, AMBIGUOUS, candidates);
    }

    private static List<Candidate> candidates(Map<FleetReport.Service, List<ServiceSurface.Entry>> hits) {
        List<Candidate> out = new ArrayList<>();
        hits.forEach((b, entries) -> entries.forEach(e -> out.add(new Candidate(b.name(), e, e.breaks() != null))));
        return out;
    }

    private static String or(List<String> names, String word) {
        return names.size() <= 1 ? String.join("", names)
                : String.join(", ", names.subList(0, names.size() - 1)) + word + names.getLast();
    }

    /**
     * The host of a base URL ({@code scheme://user@host:port/path}, or a bare {@code host:port}), lowercased; null when
     * there is none or it names no service: {@code localhost}, a loopback or IP address.
     */
    static String host(String base) {
        return host(base, false);
    }

    private static String host(String base, boolean any) {
        if (base == null || base.isBlank()) {
            return null;
        }
        String h = base.strip().replaceFirst("^[A-Za-z][A-Za-z0-9+.-]*://", "").replaceFirst("^[^@/]*@", "");
        h = h.replaceFirst("[/?#].*$", "");
        h = h.startsWith("[") ? h.replaceFirst("].*$", "]") : h.replaceFirst(":.*$", "");
        h = h.toLowerCase(Locale.ROOT);
        if (any) {
            return h;
        }
        if (h.isEmpty() || h.contains("{") || h.equals("localhost") || h.startsWith("[") || h.matches("[0-9.]+")) {
            return null;
        }
        return h;
    }

    /**
     * The part of a host that names a service: the first label ({@code billing} of {@code billing:8080},
     * {@code billing.payments.svc.cluster.local}, {@code billing.internal}); null for none.
     */
    static String label(String host) {
        if (host == null) {
            return null;
        }
        int dot = host.indexOf('.');
        String l = dot < 0 ? host : host.substring(0, dot);
        return l.isEmpty() ? null : l;
    }

    /**
     * Whether {@code label} names {@code s}: its fleet name (its folder or repository name, the member name of a named
     * fleet), its root folder's name or its {@code spring.application.name}, ignoring case; as a fallback, the names
     * without a {@code -service} / {@code -svc} suffix. Present: how, for the note ("" for the fleet name itself).
     */
    static Optional<String> named(String label, FleetReport.Service s) {
        Map<String, String> names = new LinkedHashMap<>();
        names.put(s.name().toLowerCase(Locale.ROOT), "");
        if (s.root() != null) {
            String folder = java.nio.file.Path.of(s.root()).getFileName() == null ? null
                    : java.nio.file.Path.of(s.root()).getFileName().toString().toLowerCase(Locale.ROOT);
            if (folder != null) {
                names.putIfAbsent(folder, " (" + s.name() + "'s folder)");
            }
        }
        if (s.surface() != null && s.surface().application() != null) {
            names.putIfAbsent(s.surface().application().toLowerCase(Locale.ROOT), " (spring.application.name of " + s.name() + ")");
        }
        String l = label.toLowerCase(Locale.ROOT);
        if (names.containsKey(l)) {
            return Optional.of(names.get(l));
        }
        for (String n : names.keySet()) {
            if (base(n).equals(base(l))) {
                return Optional.of(" ~ " + n + " (the names without -service / -svc)");
            }
        }
        return Optional.empty();
    }

    /** {@code billing-service}, {@code billing-svc}, {@code billing_service} → billing. */
    static String base(String name) {
        return name.toLowerCase(Locale.ROOT).replaceFirst("[-_](service|svc)$", "");
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
            sb.append("  ").append(t.caller()).append(" -> ").append(t.callees()).append("  ").append(target(t.call())).append('\n');
            sb.append("    call   ").append(place(t.call().file(), t.call().line())).append("  ").append(t.call().client())
                    .append(", ").append(t.call().method()).append('\n');
            if (t.entry() != null) {
                sb.append("    entry  ").append(place(t.entry().file(), t.entry().line())).append("  ").append(t.entry().label())
                        .append(", ").append(t.entry().method()).append('\n');
            }
            for (Candidate c : t.candidates()) {
                sb.append("    entry  ").append(c.service()).append("  ").append(place(c.entry().file(), c.entry().line())).append("  ")
                        .append(c.entry().label()).append(", ").append(c.entry().method()).append('\n');
            }
            if (t.call().breaks() != null) {
                sb.append("    breaks at the caller (").append(t.caller()).append("): ").append(t.call().breaks().what()).append('\n')
                        .append("      fix: ").append(fix(t.call().breaks(), t.caller())).append('\n');
            }
            if (t.entry() != null && t.entry().breaks() != null) {
                sb.append("    breaks at the callee (").append(t.callee()).append("): ").append(t.entry().breaks().what()).append('\n')
                        .append("      fix: ").append(fix(t.entry().breaks(), t.callee())).append('\n');
            }
            calleeBreaks(sb, t, "    ");
            if (t.note() != null) {
                sb.append("    ").append(t.note()).append('\n');
            }
        }
        List<Trace> fine = r.trace().stream().filter(t -> t.breaks().isEmpty() && t.candidates().stream().noneMatch(Candidate::breaks)).toList();
        if (!fine.isEmpty()) {
            sb.append("  the trace goes on:\n");
            for (Trace t : fine) {
                sb.append("    ").append(t.caller()).append(" -> ").append(t.callees()).append("  ").append(target(t.call()))
                        .append("  (").append(place(t.call().file(), t.call().line())).append(" -> ").append(entries(t)).append(")")
                        .append(t.note() == null ? "" : "  " + t.note()).append('\n');
            }
        }
        List<Trace> depends = r.trace().stream().filter(t -> t.breaks().isEmpty() && t.candidates().stream().anyMatch(Candidate::breaks)).toList();
        if (!depends.isEmpty()) {
            sb.append("  the trace depends on which service the call reaches:\n");
            for (Trace t : depends) {
                sb.append("    ").append(t.caller()).append(" -> ").append(t.callees()).append("  ").append(target(t.call()))
                        .append("  (").append(place(t.call().file(), t.call().line())).append(" -> ").append(entries(t)).append(")  ")
                        .append(t.note()).append('\n');
                calleeBreaks(sb, t, "      ");
            }
        }
        if (!r.otherHost().isEmpty()) {
            sb.append("Same path, other host: ").append(r.otherHost().size()).append(r.otherHost().size() == 1 ? " call" : " calls")
                    .append(" (not counted as links)\n");
            for (Trace t : r.otherHost()) {
                sb.append("  ").append(t.caller()).append(" -> ").append(t.call().base()).append("  ").append(target(t.call()))
                        .append("  (").append(place(t.call().file(), t.call().line())).append(" -> ").append(entries(t)).append(")\n")
                        .append("    ").append(t.note()).append('\n');
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

    /** The candidates of an ambiguous call that drop the trace, each with its fix. */
    private static void calleeBreaks(StringBuilder sb, Trace t, String indent) {
        for (Candidate c : t.candidates()) {
            if (c.breaks()) {
                sb.append(indent).append("breaks at the callee if it is ").append(c.service()).append(": ").append(c.entry().breaks().what())
                        .append('\n').append(indent).append("  fix: ").append(fix(c.entry().breaks(), c.service())).append('\n');
            }
        }
    }

    /** {@code file:line} of the entrypoint, or {@code service file:line} per candidate. */
    private static String entries(Trace t) {
        return t.entry() != null ? place(t.entry().file(), t.entry().line())
                : t.candidates().stream().map(c -> c.service() + " " + place(c.entry().file(), c.entry().line()))
                .collect(Collectors.joining(", "));
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
