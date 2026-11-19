package dev.logast.fleet;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** #140: the links of a fleet as a diagram. */
class FleetGraphTest {
    private static final ServiceSurface.Break BREAK = new ServiceSurface.Break("no trace headers", null, null, null);

    private static ServiceSurface.Call call(String path) {
        return new ServiceSurface.Call("http", "GET", path, null, "RestClient", "m", "A.java", 1, null);
    }

    private static ServiceSurface.Entry entry(String path, ServiceSurface.Break breaks) {
        return new ServiceSurface.Entry("http", "GET", path, "GET " + path, "m", "B.java", 1, breaks);
    }

    private static FleetLinks.Trace link(String from, String to, String match, List<String> breaks) {
        return new FleetLinks.Trace(from, call("/" + to), to, entry("/" + to, null), breaks, match + ": " + to, match, null);
    }

    @Test
    void oneArrowPerPairStyledByItsSurestMatchRedWhenTheTraceBreaks() {
        var ambiguous = new FleetLinks.Trace("gateway", call("/receipts"), null, null, List.of(), "ambiguous", FleetLinks.AMBIGUOUS,
                List.of(new FleetLinks.Candidate("billing", entry("/receipts", BREAK), true),
                        new FleetLinks.Candidate("invoicing", entry("/receipts", null), false)));
        var links = new FleetLinks.Result(List.of(
                link("gateway", "orders", FleetLinks.CONFIRMED, List.of()),
                link("gateway", "orders", FleetLinks.PATH_ONLY, List.of()),
                link("orders", "shipping", FleetLinks.PATH_ONLY, List.of("callee")),
                link("shipping", "gateway", FleetLinks.CONFIRMED, List.of()),
                ambiguous), List.of());

        assertThat(FleetGraph.edges(links)).extracting(e -> e.from() + ">" + e.to() + " " + e.match() + (e.broken() ? " broken" : ""))
                .as("two calls on one pair: one arrow, the surer match; an ambiguous call: one dotted arrow per candidate")
                .containsExactly("gateway>billing ambiguous broken", "gateway>invoicing ambiguous", "gateway>orders confirmed",
                        "orders>shipping pathOnly broken", "shipping>gateway confirmed");

        assertThat(FleetGraph.columns(List.of("audit", "billing", "gateway", "invoicing", "orders", "shipping"), FleetGraph.edges(links)))
                .as("callers left of their callees; the cycle gateway → orders → shipping → gateway is cut where it closes")
                .containsEntry("gateway", 0).containsEntry("orders", 1).containsEntry("shipping", 2)
                .containsEntry("billing", 1).containsEntry("audit", 0);

        String svg = FleetGraph.svg(List.of("audit", "billing", "gateway", "invoicing", "orders", "shipping"), links);
        assertThat(svg).contains("<path class='edge confirmed' data-from='gateway' data-to='orders'",
                        "<path class='edge pathOnly broken' data-from='orders' data-to='shipping'",
                        "<path class='edge ambiguous broken' data-from='gateway' data-to='billing'",
                        "marker-end='url(#arrow-bad)'", "gateway → orders: the trace goes on (2 calls)\ngoes on: GET /orders (confirmed: orders)\ngoes on: GET /orders (pathOnly: orders)",
                        "orders → shipping: the trace breaks on 1 of 1 call\nbreaks: GET /shipping")
                .as("a service without links is listed under the diagram, not drawn")
                .contains("No link to or from another service: audit").doesNotContain("data-name='audit'");
    }

    @Test
    void columnsAreOrderedSoArrowsCrossLess() {
        // by name: a, b, c on the left and x, y, z on the right; every arrow would cross
        var links = new FleetLinks.Result(List.of(link("a", "z", FleetLinks.CONFIRMED, List.of()),
                link("b", "y", FleetLinks.CONFIRMED, List.of()), link("c", "x", FleetLinks.CONFIRMED, List.of())), List.of());
        var edges = FleetGraph.edges(links);
        var cols = FleetGraph.order(FleetGraph.columns(List.of(), edges), edges);
        assertThat(cols).hasSize(2);
        List<String> left = cols.get(0);
        List<String> right = cols.get(1);
        assertThat(edges).as("each caller at the height of its callee").allSatisfy(e ->
                assertThat(left.indexOf(e.from())).isEqualTo(right.indexOf(e.to())));
    }

    @Test
    void nothingToDrawWithoutLinks() {
        assertThat(FleetGraph.svg(List.of("a"), new FleetLinks.Result(List.of(), List.of()))).contains("nothing to draw");
        assertThat(FleetGraph.svg(List.of("a"), null)).contains("nothing to draw");
    }
}
