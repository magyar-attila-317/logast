package dev.logast.cli;

import dev.logast.orders.WorkOrder;

import java.util.List;
import java.util.Map;

/** {@code scan --format fills-template}: a messages.yaml skeleton the LLM (or a human) only has to complete. */
final class FillsTemplate {
    private FillsTemplate() {
    }

    static Map<String, Object> of(List<WorkOrder> orders) {
        return dev.logast.apply.FillsFile.template(orders);
    }
}
