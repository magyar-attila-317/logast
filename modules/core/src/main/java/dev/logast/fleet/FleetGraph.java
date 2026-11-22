package dev.logast.fleet;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The links between the services of a fleet as an inline SVG diagram for fleet.html (#140): one box per service in
 * columns by call depth (callers left), one arrow per caller → callee pair, its line by the weakest doubt of its calls
 * (solid: confirmed or a message, dashed: path only, dotted: ambiguous), red when the trace breaks on the pair.
 */
public final class FleetGraph {
    static final int ROW = 58;
    static final int MAX_BOX_W = 300;
    static final int CHAR_W = 7;
    static final int GAP = 90;
    static final int BOX_H = 34;
    static final int PAD = 20;

    /** One caller → callee pair. {@code match} is the surest of its calls: confirmed (or a message), pathOnly, ambiguous. */
    record Edge(String from, String to, String match, boolean broken, List<String> calls) {
    }

    private FleetGraph() {
    }

    /** The pairs, sorted by caller and callee. Calls to another host are not links and are left out. */
    static List<Edge> edges(FleetLinks.Result links) {
        Map<String, List<String>> calls = new TreeMap<>();
        Map<String, String> match = new HashMap<>();
        Set<String> broken = new HashSet<>();
        if (links != null) {
            for (FleetLinks.Trace t : links.trace()) {
                String line = (t.call().verb() == null ? t.call().kind() : t.call().verb()) + " " + t.call().target()
                        + (t.note() == null ? "" : " (" + t.note() + ")");
                if (t.callee() != null) {
                    boolean breaks = !t.breaks().isEmpty();
                    add(calls, match, broken, t.caller(), t.callee(), t.match() == null ? FleetLinks.CONFIRMED : t.match(),
                            breaks, (breaks ? "breaks: " : "goes on: ") + line);
                }
                for (String callee : new LinkedHashSet<>(t.candidates().stream().map(FleetLinks.Candidate::service).toList())) {
                    boolean breaks = t.breaks().contains("caller") || t.candidates().stream()
                            .anyMatch(c -> c.service().equals(callee) && c.breaks());
                    add(calls, match, broken, t.caller(), callee, FleetLinks.AMBIGUOUS, breaks,
                            (breaks ? "breaks: " : "goes on: ") + line);
                }
            }
        }
        List<Edge> out = new ArrayList<>();
        calls.forEach((key, lines) -> {
            String[] ends = key.split("\u0000");
            out.add(new Edge(ends[0], ends[1], match.get(key), broken.contains(key), lines));
        });
        return out;
    }

    private static void add(Map<String, List<String>> calls, Map<String, String> match, Set<String> broken, String from,
                            String to, String how, boolean breaks, String line) {
        if (from.equals(to)) {
            return;
        }
        String key = from + "\u0000" + to;
        calls.computeIfAbsent(key, k -> new ArrayList<>()).add(line);
        match.merge(key, how, (a, b) -> rank(a) <= rank(b) ? a : b);
        if (breaks) {
            broken.add(key);
        }
    }

    private static int rank(String match) {
        return switch (match) {
            case FleetLinks.CONFIRMED -> 0;
            case FleetLinks.PATH_ONLY -> 1;
            default -> 2;
        };
    }

    /** The column of each service: the longest chain of calls that leads to it, cycles cut where they close. */
    static Map<String, Integer> columns(Collection<String> services, List<Edge> edges) {
        Set<String> nodes = new TreeSet<>(services);
        edges.forEach(e -> {
            nodes.add(e.from());
            nodes.add(e.to());
        });
        Map<String, List<String>> out = new TreeMap<>();
        edges.forEach(e -> out.computeIfAbsent(e.from(), k -> new ArrayList<>()).add(e.to()));
        // depth-first from every node in name order; an edge back into the current path closes a cycle and is ignored
        Map<String, List<String>> in = new HashMap<>();
        Set<String> done = new HashSet<>();
        for (String n : nodes) {
            forward(n, out, new LinkedHashSet<>(), done, in);
        }
        Map<String, Integer> col = new TreeMap<>();
        for (String n : nodes) {
            depth(n, in, col);
        }
        return col;
    }

    private static void forward(String n, Map<String, List<String>> out, Set<String> path, Set<String> done,
                                Map<String, List<String>> in) {
        if (done.contains(n)) {
            return;
        }
        path.add(n);
        for (String to : out.getOrDefault(n, List.of())) {
            if (path.contains(to)) {
                continue;
            }
            in.computeIfAbsent(to, k -> new ArrayList<>()).add(n);
            forward(to, out, path, done, in);
        }
        path.remove(n);
        done.add(n);
    }

    private static int depth(String n, Map<String, List<String>> in, Map<String, Integer> col) {
        Integer known = col.get(n);
        if (known != null) {
            return known;
        }
        int d = 0;
        for (String from : in.getOrDefault(n, List.of())) {
            d = Math.max(d, depth(from, in, col) + 1);
        }
        col.put(n, d);
        return d;
    }

    /**
     * The services of each column, ordered so that arrows cross less: a few sweeps that move each service to the mean
     * position of the services it links to (the barycenter heuristic), names breaking ties.
     */
    static List<List<String>> order(Map<String, Integer> col, List<Edge> edges) {
        int n = col.values().stream().mapToInt(Integer::intValue).max().orElse(0) + 1;
        List<List<String>> cols = new ArrayList<>();
        for (int c = 0; c < n; c++) {
            cols.add(new ArrayList<>());
        }
        new TreeMap<>(col).forEach((name, c) -> cols.get(c).add(name));
        Map<String, List<String>> near = new HashMap<>();
        edges.forEach(e -> {
            near.computeIfAbsent(e.from(), k -> new ArrayList<>()).add(e.to());
            near.computeIfAbsent(e.to(), k -> new ArrayList<>()).add(e.from());
        });
        Map<String, Double> pos = new HashMap<>();
        cols.forEach(c -> place(c, pos));
        // one column at a time, left to right then back, each against its neighbours' current places
        for (int sweep = 0; sweep < 8; sweep++) {
            for (int k = 0; k < n; k++) {
                List<String> c = cols.get(sweep % 2 == 0 ? k : n - 1 - k);
                Map<String, Double> key = new HashMap<>();
                for (String s : c) {
                    key.put(s, near.getOrDefault(s, List.of()).stream().filter(o -> !c.contains(o))
                            .mapToDouble(pos::get).average().orElse(pos.get(s)));
                }
                c.sort(java.util.Comparator.<String>comparingDouble(key::get).thenComparing(java.util.Comparator.naturalOrder()));
                place(c, pos);
            }
        }
        return cols;
    }

    /** Relative places (0 top, 1 bottom), so columns of different heights compare, as they are drawn fanned out. */
    private static void place(List<String> c, Map<String, Double> pos) {
        for (int i = 0; i < c.size(); i++) {
            pos.put(c.get(i), c.size() == 1 ? 0.5 : (double) i / (c.size() - 1));
        }
    }

    private static boolean hasBackEdge(List<Edge> edges, Map<String, int[]> at) {
        return edges.stream().anyMatch(e -> at.get(e.to())[0] <= at.get(e.from())[0]);
    }

    /** The diagram, or a line saying there is nothing to draw. */
    public static String svg(Collection<String> services, FleetLinks.Result links) {
        List<Edge> edges = edges(links);
        if (edges.isEmpty()) {
            return "<p class=muted>No call reaches an entrypoint of another service: nothing to draw.</p>";
        }
        Set<String> linked = new TreeSet<>();
        edges.forEach(e -> {
            linked.add(e.from());
            linked.add(e.to());
        });
        List<List<String>> cols = order(columns(linked, edges), edges);
        int rows = cols.stream().mapToInt(List::size).max().orElse(1);
        int longest = linked.stream().mapToInt(String::length).max().orElse(10);
        int boxW = Math.max(120, Math.min(MAX_BOX_W, longest * CHAR_W + 24));
        int chars = (boxW - 24) / CHAR_W;
        int colW = boxW + GAP;
        Map<String, int[]> at = new HashMap<>();
        for (int c = 0; c < cols.size(); c++) {
            List<String> names = cols.get(c);
            // fanned out: a shorter column spreads over the height of the tallest, one service sits in the middle
            double step = names.size() == 1 ? 0 : (double) (rows - 1) * ROW / (names.size() - 1);
            int top = names.size() == 1 ? PAD + (rows - 1) * ROW / 2 : PAD;
            for (int i = 0; i < names.size(); i++) {
                at.put(names.get(i), new int[]{PAD + c * colW, (int) Math.round(top + i * step)});
            }
        }
        int width = PAD * 2 + (cols.size() - 1) * colW + boxW;
        int height = PAD * 2 + (rows - 1) * ROW + BOX_H + (hasBackEdge(edges, at) ? ROW : 0);
        StringBuilder svg = new StringBuilder();
        svg.append("<div class=wrap><svg class=graph width=").append(width).append(" height=").append(height)
                .append(" viewBox='0 0 ").append(width).append(' ').append(height)
                .append("' role=img aria-label='Links between services'>\n<defs>")
                .append(marker("arrow", "var(--muted)")).append(marker("arrow-bad", "var(--bad)")).append("</defs>\n");
        for (Edge e : edges) {
            int[] a = at.get(e.from());
            int[] b = at.get(e.to());
            double x1 = a[0] + boxW;
            double y1 = a[1] + BOX_H / 2.0;
            double x2 = b[0];
            double y2 = b[1] + BOX_H / 2.0;
            String d;
            if (x2 > x1) {
                double mid = (x1 + x2) / 2;
                d = "M" + x1 + " " + y1 + " C" + mid + " " + y1 + " " + mid + " " + y2 + " " + (x2 - 2) + " " + y2;
            } else {
                // a call back to an earlier column (a cycle): loop below the boxes
                double low = Math.max(a[1], b[1]) + BOX_H + ROW / 2.0;
                d = "M" + x1 + " " + y1 + " C" + (x1 + 60) + " " + low + " " + (x2 - 60) + " " + low + " " + (x2 - 2) + " " + y2;
            }
            svg.append("<path class='edge ").append(e.match()).append(e.broken() ? " broken" : "").append("' data-from='")
                    .append(esc(e.from())).append("' data-to='").append(esc(e.to())).append("' d='").append(d)
                    .append("' marker-end='url(#").append(e.broken() ? "arrow-bad" : "arrow").append(")'><title>")
                    .append(esc(e.from() + " → " + e.to() + ": " + summary(e) + "\n"
                            + String.join("\n", e.calls()))).append("</title></path>\n");
        }
        for (Map.Entry<String, int[]> n : new TreeMap<>(at).entrySet()) {
            String name = n.getKey();
            int[] p = n.getValue();
            String shown = name.length() > chars ? name.substring(0, chars - 1) + "…" : name;
            svg.append("<g class=node data-name='").append(esc(name))
                    .append("'><title>").append(esc(name)).append("</title><rect x=").append(p[0]).append(" y=").append(p[1])
                    .append(" width=").append(boxW).append(" height=").append(BOX_H).append(" rx=6></rect><text x=")
                    .append(p[0] + boxW / 2).append(" y=").append(p[1] + BOX_H / 2 + 4).append(">").append(esc(shown))
                    .append("</text></g>\n");
        }
        List<String> alone = new TreeSet<>(services).stream().filter(n -> !linked.contains(n)).toList();
        svg.append("</svg></div>\n").append(LEGEND);
        if (!alone.isEmpty()) {
            svg.append("<div class=muted>No link to or from another service: ").append(esc(String.join(", ", alone)))
                    .append("</div>\n");
        }
        svg.append("""
                <script>
                document.querySelectorAll('svg.graph .node').forEach(function (n) {
                  n.addEventListener('click', function () {
                    var svg = n.closest('svg'), name = n.dataset.name, on = svg.dataset.focus !== name;
                    svg.dataset.focus = on ? name : '';
                    svg.classList.toggle('focus', on);
                    svg.querySelectorAll('.edge').forEach(function (e) {
                      e.classList.toggle('on', on && (e.dataset.from === name || e.dataset.to === name));
                    });
                    svg.querySelectorAll('.node').forEach(function (m) {
                      var near = m === n || Array.prototype.some.call(svg.querySelectorAll('.edge.on'), function (e) {
                        return e.dataset.from === m.dataset.name || e.dataset.to === m.dataset.name;
                      });
                      m.classList.toggle('on', on && near);
                    });
                    if (on && window.openService) openService(name);
                  });
                });
                </script>
                """);
        return svg.toString();
    }

    /** What the lines mean, each next to a sample of itself (#140: "path only" alone said nothing). */
    static final String LEGEND = """
            <div class=legend>
            <div><svg width=44 height=10><line x1=0 y1=5 x2=44 y2=5 class='edge confirmed'/></svg><span><b>sure</b>: the call's
              URL names this service (its host is the service's name), or it is a message to a topic or queue the service listens to</span></div>
            <div><svg width=44 height=10><line x1=0 y1=5 x2=44 y2=5 class='edge pathOnly'/></svg><span><b>likely</b>: the URL's
              host is not in the code (it comes from config logast could not read), but only this service has an endpoint
              with that URL path and method</span></div>
            <div><svg width=44 height=10><line x1=0 y1=5 x2=44 y2=5 class='edge ambiguous'/></svg><span><b>unsure</b>: several
              services have an endpoint with that URL path and the host doesn't decide: an arrow to each</span></div>
            <div><svg width=44 height=10><line x1=0 y1=5 x2=44 y2=5 class='edge confirmed broken'/></svg><span><b>red</b>: on
              at least one of these calls the trace id is lost, so the two services' logs of one request can't be joined
              (hover the arrow: which calls, and the Trace table below: which side to fix)</span></div>
            <div class=muted>Arrows point from the calling service to the called one. Hover an arrow for its calls; click a
              service for its breakdown and only its links (click again to show all).</div>
            </div>
            """;

    /** The page's styles for the diagram. */
    public static final String CSS = """
            svg.graph{display:block;font:12px system-ui,sans-serif}
            svg.graph .node rect{fill:var(--card);stroke:var(--fg);stroke-width:1.2}svg.graph .node{cursor:pointer}
            svg.graph .node text{fill:var(--fg);text-anchor:middle}
            svg.graph .edge{fill:none;stroke:var(--muted);stroke-width:1.6}svg.graph .edge:hover{stroke-width:3.5}
            svg.graph .edge.pathOnly{stroke-dasharray:6 4}svg.graph .edge.ambiguous{stroke-dasharray:2 4}
            svg.graph .edge.broken{stroke:var(--bad)}
            svg.graph.focus .edge:not(.on),svg.graph.focus .node:not(.on){opacity:.15}
            .legend{font-size:12px;margin-top:8px}.legend>div{display:flex;gap:10px;align-items:baseline;margin:3px 0}
            .legend svg{flex:none}.legend .edge{fill:none;stroke:var(--muted);stroke-width:2}
            .legend .edge.pathOnly{stroke-dasharray:6 4}.legend .edge.ambiguous{stroke-dasharray:2 4}.legend .edge.broken{stroke:var(--bad)}
            """;

    /** "the trace breaks on 2 of 3 calls", "the trace goes on (2 calls)". */
    private static String summary(Edge e) {
        long breaks = e.calls().stream().filter(c -> c.startsWith("breaks: ")).count();
        int n = e.calls().size();
        return breaks > 0 ? "the trace breaks on " + breaks + " of " + n + (n == 1 ? " call" : " calls")
                : "the trace goes on (" + n + (n == 1 ? " call)" : " calls)");
    }

    private static String marker(String id, String color) {
        return "<marker id=" + id + " viewBox='0 0 10 10' refX=9 refY=5 markerWidth=7 markerHeight=7 orient=auto-start-reverse>"
                + "<path d='M0 0L10 5L0 10z' style='fill:" + color + "'></path></marker>";
    }

    private static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("'", "&#39;").replace("\"", "&quot;");
    }
}
