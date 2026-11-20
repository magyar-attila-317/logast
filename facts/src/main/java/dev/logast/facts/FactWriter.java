package dev.logast.facts;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.util.Map;

/**
 * Writes a facts file (#152): the header, then one node or edge per line. Attributes are sorted and empty fields left
 * out, so the same facts always give the same bytes (diffable, golden-testable).
 */
public final class FactWriter implements AutoCloseable {
    static final ObjectMapper JSON = JsonMapper.builder().build();

    private final Writer out;

    public FactWriter(Writer out, Header header) {
        this.out = out;
        line("header", header);
    }

    public FactWriter node(Node n) {
        line("node", n);
        return this;
    }

    public FactWriter edge(Edge e) {
        line("edge", e);
        return this;
    }

    private void line(String key, Object value) {
        try {
            out.write(JSON.writeValueAsString(Map.of(key, value)));
            out.write('\n');
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void close() throws IOException {
        out.flush();
    }
}
