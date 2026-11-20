package dev.logast.facts;

/** #182: sinks around sinks. */
public final class FactSinks {
    private FactSinks() {
    }

    /**
     * Facts of one root of a repo (a Gradle build in {@code apps/orders}): evidence without a root gets this one, so a
     * source reads {@code repo/apps/orders/src/…} however the extractor named the file.
     */
    public static FactSink rooted(FactSink sink, String root) {
        if (root == null || root.isBlank() || root.equals(".")) {
            return sink;
        }
        return new FactSink() {
            @Override
            public void node(Node n) {
                sink.node(new Node(n.id(), n.kind(), n.env(), n.attrs(), at(n.evidence())));
            }

            @Override
            public void edge(Edge e) {
                sink.edge(new Edge(e.from(), e.kind(), e.to(), e.env(), e.attrs(), e.provenance(), e.confidence(), e.via(), e.sources(),
                        at(e.evidence())));
            }

            private Evidence at(Evidence ev) {
                return ev == null || ev.root() != null ? ev : new Evidence(ev.repo(), root, ev.file(), ev.line(), ev.commit());
            }
        };
    }
}
