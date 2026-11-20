package dev.logast.facts;

/** Where an extractor puts its facts (#153): a {@link FactWriter}, a list in a test, a store. */
public interface FactSink {
    void node(Node n);

    void edge(Edge e);
}
