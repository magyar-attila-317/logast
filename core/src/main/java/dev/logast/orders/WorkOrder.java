package dev.logast.orders;

import java.util.List;
import java.util.Map;

/**
 * One concrete, pre-decided change. When {@code slots} is empty it can be applied mechanically; when
 * {@code edits} is empty it is report-only and {@code suggestion} says what a human should do.
 */
public record WorkOrder(String id, String rule, List<String> fixes, String severity, String title,
                        String file, int line, List<Location> locations, String method, List<String> paths,
                        String suggestion, List<Edit> edits, Map<String, Slot> slots, Context context,
                        List<String> dependsOn, Map<String, Object> data) {
    /** @param data structured details for decision files and tools (e.g. a metric's suggested name and outcome) */
    public WorkOrder {
        data = data == null ? Map.of() : data;
    }

    /** Another place this order fixes, e.g. an {@code @Async} method covered by adding the starter. */
    public record Location(String file, int line, String rule) {
    }

    /**
     * @param varsInScope variables the LLM may add as extra {@code [k=v]} arguments via {@code "args"} in its fill
     * @param argsSlot    name of the slot that extra arguments are appended to
     */
    public record Context(List<String> mdcInScope, Map<String, String> varsInScope, String code,
                          String argsSlot) {
        public static final Context EMPTY = new Context(List.of(), Map.of(), null, null);
    }

    public boolean mechanical() {
        return !edits.isEmpty() && slots.isEmpty();
    }

    public boolean reportOnly() {
        return edits.isEmpty();
    }
}
