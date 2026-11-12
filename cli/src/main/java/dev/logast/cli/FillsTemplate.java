package dev.logast.cli;

import dev.logast.orders.WorkOrder;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** {@code scan --format fills-template}: a fills.json skeleton the LLM (or a human) only has to complete. */
final class FillsTemplate {
    private FillsTemplate() {
    }

    static Map<String, Object> of(List<WorkOrder> orders) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (WorkOrder o : orders) {
            if (o.slots().isEmpty()) {
                continue;
            }
            Map<String, Object> fill = new LinkedHashMap<>();
            o.slots().forEach((name, slot) -> fill.put(name, ""));
            o.slots().forEach((name, slot) -> fill.put("_" + name, slot.describe()
                    + (slot.original() == null ? "" : " | was: " + slot.original())
                    + (slot.pattern() == null ? "" : " | must read as: " + slot.pattern())
                    + (slot.keys().isEmpty() ? "" : " | suffix keys added by the tool: " + slot.keys())));
            fill.put("_where", o.file() + ":" + o.line() + (o.method() == null ? "" : " " + o.method()));
            out.put(o.id(), fill);
        }
        return out;
    }
}
