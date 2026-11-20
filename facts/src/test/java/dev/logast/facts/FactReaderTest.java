package dev.logast.facts;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** #152: the facts format, its writer and its validator. */
class FactReaderTest {
    private static final String HEADER = "{\"header\":{\"schema\":\"kb-facts/0\",\"extractor\":\"java\",\"repo\":\"shipping\",\"commit\":\"abc1234\"}}\n";
    private static final String EV = "\"evidence\":{\"file\":\"src/main/java/com/example/ShipmentController.java\",\"line\":23}";

    private static FactReader.Result read(String text) throws IOException {
        return FactReader.read(new StringReader(text));
    }

    private static List<String> problems(String lines) throws IOException {
        return read(HEADER + lines).problems().stream().map(FactReader.Problem::toString).toList();
    }

    @Test
    void writtenFactsReadBackTheSame() throws IOException {
        Node service = Node.of("service", "shipping", Map.of("application", "shipping"), Evidence.at("src/main/resources/application.yml", 1));
        Node endpoint = Node.of("endpoint", "shipping:GET /shipments/{id}", Map.of("method", "GET", "path", "/shipments/{id}"),
                Evidence.at("app", "src/main/java/com/example/ShipmentController.java", 23));
        Edge has = Edge.extracted(service.id(), "has", endpoint.id(), null, endpoint.evidence());
        Edge writes = Edge.extracted(endpoint.id(), "writes", "table:shipping.shipment", Map.of("via", "ShipmentRepository.save"),
                Evidence.at("src/main/java/com/example/ShipmentService.java", 41)).inEnv("prod");
        Edge inferred = new Edge("call:orders:GET /shipments/{id}", "calls", endpoint.id(), null, null, Provenance.INFERRED, 0.8,
                "alias:shipment-api", null, null);
        Edge llm = new Edge("job:shipping:nightly-export", "part-of", "process:month-end", null, null, Provenance.LLM, null, null,
                List.of("job:shipping:nightly-export", "https://wiki.example.com/month-end"), null);

        StringWriter out = new StringWriter();
        try (FactWriter w = new FactWriter(out, Header.of("java", "0.1.0", "shipping", "abc1234"))) {
            w.node(service);
            w.node(endpoint);
            w.edge(has);
            w.edge(writes);
            w.edge(inferred);
            w.edge(llm);
        }
        FactReader.Result r = read(out.toString());

        assertThat(r.problems()).isEmpty();
        assertThat(r.header()).isEqualTo(Header.of("java", "0.1.0", "shipping", "abc1234"));
        assertThat(r.nodes()).containsExactly(service, endpoint);
        assertThat(r.edges()).containsExactly(has, writes, inferred, llm);
        assertThat(out.toString().lines().toList().get(2)).as("one compact line per fact, fields in a fixed order")
                .isEqualTo("{\"node\":{\"id\":\"endpoint:shipping:GET /shipments/{id}\",\"kind\":\"endpoint\","
                        + "\"attrs\":{\"method\":\"GET\",\"path\":\"/shipments/{id}\"},"
                        + "\"evidence\":{\"root\":\"app\",\"file\":\"src/main/java/com/example/ShipmentController.java\",\"line\":23}}}");
    }

    @Test
    void sortedAttributesGiveTheSameBytes() throws IOException {
        StringWriter a = new StringWriter();
        StringWriter b = new StringWriter();
        new FactWriter(a, Header.of("java", null, "r", null)).node(Node.of("table", "s.t", Map.of("b", 1, "a", 2), Evidence.at("f", 1)));
        new FactWriter(b, Header.of("java", null, "r", null)).node(Node.of("table", "s.t", Map.of("a", 2, "b", 1), Evidence.at("f", 1)));
        assertThat(a.toString()).isEqualTo(b.toString());
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', quoteCharacter = '`', textBlock = """
            a node whose id is not <kind>:<name>       | {"node":{"id":"shipping","kind":"service",EV}}                          | id 'shipping' is not <kind>:<name>
            a node whose id has another kind           | {"node":{"id":"table:shipping","kind":"service",EV}}                    | does not start with its kind 'service:'
            a kind with capitals                       | {"node":{"id":"Service:x","kind":"Service",EV}}                         | node kind 'Service' is not lower case
            a node without evidence                    | {"node":{"id":"service:x","kind":"service"}}                            | node needs 'evidence'
            evidence without a file                    | {"node":{"id":"service:x","kind":"service","evidence":{"line":3}}}      | evidence needs 'file'
            evidence line 0                            | {"node":{"id":"service:x","kind":"service","evidence":{"file":"f","line":0}}} | evidence line must be a whole number from 1
            an unknown field (a typo)                  | {"node":{"id":"service:x","kind":"service","atrs":{},EV}}               | node has an unknown field 'atrs'
            a bad environment                          | {"node":{"id":"service:x","kind":"service","env":"Prod",EV}}            | env 'Prod' is not lower case
            an edge with an unknown provenance         | {"edge":{"from":"a:x","kind":"calls","to":"b:y","provenance":"guessed",EV}} | provenance must be extracted, inferred or llm
            an extracted edge without evidence         | {"edge":{"from":"a:x","kind":"calls","to":"b:y","provenance":"extracted"}} | an extracted edge needs evidence
            an extracted edge with a confidence        | {"edge":{"from":"a:x","kind":"calls","to":"b:y","provenance":"extracted","confidence":0.5,EV}} | an extracted edge has no confidence
            an inferred edge without via               | {"edge":{"from":"a:x","kind":"calls","to":"b:y","provenance":"inferred","confidence":0.5}} | an inferred edge needs a confidence and via
            a confidence above 1                       | {"edge":{"from":"a:x","kind":"calls","to":"b:y","provenance":"inferred","confidence":1.5,"via":"rule"}} | confidence must be a number above 0, at most 1
            an llm edge without sources                | {"edge":{"from":"a:x","kind":"calls","to":"b:y","provenance":"llm"}}    | an llm edge needs sources
            an edge end that is not an id              | {"edge":{"from":"x","kind":"calls","to":"b:y","provenance":"extracted",EV}} | 'x' is not an id
            a second header                            | {"header":{"schema":"kb-facts/0","extractor":"java","repo":"r"}}         | the header must be the first line, and only once
            a line that is not JSON                    | {"node":                                                                | not JSON
            a line with two keys                       | {"node":{},"edge":{}}                                                   | exactly one key
            an unknown line type                       | {"fact":{}}                                                             | unknown line type 'fact'
            """)
    void eachRuleIsReportedWithItsLine(String rule, String line, String message) throws IOException {
        List<String> p = problems(line.replace("EV", EV) + "\n");
        assertThat(p).as(rule).anySatisfy(s -> assertThat(s).startsWith("line 2: ").contains(message));
    }

    @Test
    void theHeaderComesFirstAndNamesTheSchema() throws IOException {
        assertThat(read("{\"node\":{\"id\":\"service:x\",\"kind\":\"service\"," + EV + "}}\n").problems())
                .extracting(FactReader.Problem::message).contains("the first line must be the header");
        assertThat(read("{\"header\":{\"schema\":\"kb-facts/1\",\"extractor\":\"java\",\"repo\":\"r\"}}\n").problems())
                .extracting(FactReader.Problem::message).contains("schema 'kb-facts/1' is not kb-facts/0");
        assertThat(read("").problems()).extracting(FactReader.Problem::message).containsExactly("no header line");
    }

    @Test
    void evidenceTakesTheRepoFromTheHeaderOrNeedsItsOwn() throws IOException {
        assertThat(problems("{\"node\":{\"id\":\"service:x\",\"kind\":\"service\"," + EV + "}}\n")).isEmpty();
        String noRepo = "{\"header\":{\"schema\":\"kb-facts/0\",\"extractor\":\"java\",\"repo\":\"\"}}\n";
        assertThat(read(noRepo).problems()).extracting(FactReader.Problem::message).contains("header needs 'repo'");
    }

    @Test
    void aBrokenLineIsLeftOutAndTheRestIsRead() throws IOException {
        FactReader.Result r = read(HEADER
                + "{\"node\":{\"id\":\"service:x\",\"kind\":\"service\"," + EV + "}}\n"
                + "{\"node\":{\"id\":\"bad\",\"kind\":\"service\"," + EV + "}}\n"
                + "\n"
                + "{\"edge\":{\"from\":\"service:x\",\"kind\":\"has\",\"to\":\"endpoint:x:GET /\",\"provenance\":\"extracted\"," + EV + "}}\n");
        assertThat(r.nodes()).extracting(Node::id).containsExactly("service:x");
        assertThat(r.edges()).hasSize(1);
        assertThat(r.problems()).extracting(FactReader.Problem::line).containsExactly(3);
    }

    @Test
    void idsAreBuiltFromAKindAndAName() {
        assertThat(FactFormat.id("endpoint", "shipping:GET /shipments/{id}")).isEqualTo("endpoint:shipping:GET /shipments/{id}");
        assertThat(FactFormat.kindOf("table:billing.invoice")).isEqualTo("table");
        assertThat(FactFormat.kindOf("no-kind")).isNull();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> FactFormat.id("Table", "x")).isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> FactFormat.id("table", "a\nb")).isInstanceOf(IllegalArgumentException.class);
    }

    /** The JSON Schema for other producers says what the validator checks: the same fields, the same required ones. */
    @Test
    void theJsonSchemaMatchesTheValidator() throws IOException {
        JsonNode schema;
        try (InputStream in = FactFormat.class.getResourceAsStream("kb-facts-0.schema.json")) {
            schema = FactWriter.JSON.readTree(in);
        }
        JsonNode defs = schema.get("$defs");
        assertThat(defs.at("/header/properties/schema/const").asText()).isEqualTo(FactFormat.SCHEMA);
        Map<String, List<List<String>>> expected = Map.of(
                "header", List.of(FactFormat.HEADER_FIELDS, FactFormat.HEADER_REQUIRED),
                "node", List.of(FactFormat.NODE_FIELDS, FactFormat.NODE_REQUIRED),
                "edge", List.of(FactFormat.EDGE_FIELDS, FactFormat.EDGE_REQUIRED),
                "evidence", List.of(FactFormat.EVIDENCE_FIELDS, FactFormat.EVIDENCE_REQUIRED));
        expected.forEach((def, lists) -> {
            List<String> props = new ArrayList<>();
            defs.get(def).get("properties").fieldNames().forEachRemaining(props::add);
            List<String> required = new ArrayList<>();
            defs.get(def).get("required").forEach(n -> required.add(n.asText()));
            assertThat(props).as(def + " properties").containsExactlyElementsOf(lists.get(0));
            assertThat(required).as(def + " required").containsExactlyElementsOf(lists.get(1));
            assertThat(defs.get(def).get("additionalProperties").asBoolean(true)).as(def + ": unknown fields are errors").isFalse();
        });
        assertThat(defs.at("/kind/pattern").asText()).isEqualTo("^" + FactFormat.KIND.pattern() + "$");
        assertThat(defs.at("/env/pattern").asText()).isEqualTo("^" + FactFormat.ENV.pattern() + "$");
    }
}
