package dev.logast.orders;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** #57: a decision says why, when it is done, and which orders on the same code go with it; only what is set. */
class DecisionLinesTest {
    private static WorkOrder order(String id, String rule, List<Edit> edits, String basis, String doneWhen, List<String> related) {
        return new WorkOrder(id, rule, List.of(rule), "medium", "title " + id, "A.java", 3, List.of(), null, List.of(),
                "note " + id, edits, Map.of(), WorkOrder.Context.EMPTY, List.of(), Map.of(), basis, doneWhen, related);
    }

    private static final Edit EDIT = Edit.insert("A.java", 0, "x");

    @Test
    void reportOnlyOrderShowsWhyAndDoneWhen() {
        WorkOrder o = order("CAD-1", "CATCH_ALL_DEFAULT", List.of(), "the text matches 'x'", "the catch returns nothing", List.of());
        assertThat(CompactFormat.basisLine(o)).isEqualTo("why: the text matches 'x'");
        assertThat(CompactFormat.decisionLines(o, Map.of())).containsExactly("done when: the catch returns nothing");
    }

    @Test
    void ordersLogastAppliesShowNeitherWhyNorDoneWhen() {
        WorkOrder o = order("SWA-1", "SWALLOWED_EXCEPTION", List.of(EDIT), "b", "d", List.of());
        assertThat(CompactFormat.basisLine(o)).isNull();
        assertThat(CompactFormat.decisionLines(o, Map.of())).isEmpty();
    }

    @Test
    void relatedOrdersAreNamedWithTheirRuleOnAnyOrder() {
        WorkOrder o = order("SWA-1", "SWALLOWED_EXCEPTION", List.of(EDIT), null, null, List.of("CAD-1", "GONE-1"));
        assertThat(CompactFormat.decisionLines(o, Map.of("CAD-1", "CATCH_ALL_DEFAULT")))
                .containsExactly("related: CAD-1 (CATCH_ALL_DEFAULT), GONE-1  (same code: decide them together)");
    }

    @Test
    void nothingSetPrintsNothing() {
        WorkOrder o = order("CAD-1", "CATCH_ALL_DEFAULT", List.of(), null, null, null);
        assertThat(CompactFormat.basisLine(o)).isNull();
        assertThat(CompactFormat.decisionLines(o, Map.of())).isEmpty();
        assertThat(o.related()).isEmpty();
    }

    @Test
    void sarifCarriesThemAsOptionalProperties() {
        WorkOrder full = order("CAD-1", "CATCH_ALL_DEFAULT", List.of(), "b", "d", List.of("SWA-1"));
        WorkOrder bare = order("SWA-1", "SWALLOWED_EXCEPTION", List.of(EDIT), null, null, List.of());
        JsonNode results = OrderJson.MAPPER.valueToTree(Sarif.of(List.of(full, bare))).at("/runs/0/results");
        assertThat(results.get(0).get("properties").toString()).isEqualTo("{\"basis\":\"b\",\"doneWhen\":\"d\",\"related\":[\"SWA-1\"]}");
        assertThat(results.get(1).has("properties")).as("nothing set: the result looks as before").isFalse();
    }

    @Test
    void jsonLeavesOutWhatIsNotSet() {
        JsonNode bare = OrderJson.MAPPER.valueToTree(order("SWA-1", "SWALLOWED_EXCEPTION", List.of(EDIT), null, null, List.of()));
        assertThat(bare.has("basis")).isFalse();
        assertThat(bare.has("doneWhen")).isFalse();
        assertThat(bare.has("related")).isFalse();
        JsonNode full = OrderJson.MAPPER.valueToTree(order("CAD-1", "CATCH_ALL_DEFAULT", List.of(), "b", "d", List.of("SWA-1")));
        assertThat(full.get("basis").asText()).isEqualTo("b");
        assertThat(full.get("doneWhen").asText()).isEqualTo("d");
        assertThat(full.get("related").toString()).isEqualTo("[\"SWA-1\"]");
    }
}
