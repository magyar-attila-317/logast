package dev.logast.rules;

import dev.logast.orders.WorkOrder;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** #57: orders about one code construct (one catch, one call) point at each other; others stay as they were. */
class RelatedOrdersTest {
    private static WorkOrder order(String id) {
        return new WorkOrder(id, "R", List.of("R"), "low", null, "A.java", 1, List.of(), null, List.of(), null, List.of(),
                Map.of(), WorkOrder.Context.EMPTY, List.of(), Map.of(), null, null, List.of());
    }

    @Test
    void ordersOnOneSubjectAreRelatedBothWays() {
        List<WorkOrder> out = Rules.relate(List.of(order("SWA-1"), order("CAD-1"), order("CDR-1"), order("OTHER-1")),
                Map.of("SWA-1", "A.java@10", "CAD-1", "A.java@10", "CDR-1", "A.java@99"));
        assertThat(out).extracting(WorkOrder::id).containsExactly("SWA-1", "CAD-1", "CDR-1", "OTHER-1");
        assertThat(out.get(0).related()).containsExactly("CAD-1");
        assertThat(out.get(1).related()).containsExactly("SWA-1");
        assertThat(out.get(2).related()).as("alone on its subject").isEmpty();
        assertThat(out.get(3).related()).as("no subject").isEmpty();
    }

    @Test
    void anOrderDroppedBeforeRelatingIsNotNamed() {
        List<WorkOrder> out = Rules.relate(List.of(order("SWA-1")), Map.of("SWA-1", "A.java@10", "CAD-1", "A.java@10"));
        assertThat(out.getFirst().related()).isEmpty();
    }
}
