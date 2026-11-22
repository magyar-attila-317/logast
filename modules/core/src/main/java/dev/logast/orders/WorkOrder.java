package dev.logast.orders;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

/**
 * One concrete, pre-decided change. When {@code slots} is empty it can be applied mechanically; when
 * {@code edits} is empty it is report-only and {@code suggestion} says what a human should do.
 */
public record WorkOrder(String id, String rule, List<String> fixes, String severity, String title,
                        String file, int line, List<Location> locations, String method, List<String> paths,
                        String suggestion, List<Edit> edits, Map<String, Slot> slots, Context context,
                        List<String> dependsOn, Map<String, Object> data, String basis, String doneWhen,
                        @JsonInclude(JsonInclude.Include.NON_EMPTY) List<String> related) {
    /**
     * @param data     structured details for decision files and tools (e.g. a metric's suggested name and outcome)
     * @param basis    the evidence the rule decided on that the title does not show (a property value, how an audit call
     *                 was recognised), or null
     * @param doneWhen the observable condition after which the next scan no longer reports it, or null
     * @param related  ids of other orders on the same code (one catch, one call): their advice has to be read together
     */
    public WorkOrder {
        data = data == null ? Map.of() : data;
        related = related == null ? List.of() : related;
    }

    public WorkOrder withRelated(List<String> ids) {
        return new WorkOrder(id, rule, fixes, severity, title, file, line, locations, method, paths, suggestion, edits, slots,
                context, dependsOn, data, basis, doneWhen, List.copyOf(ids));
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
        // #231: a slot with generated wording does not wait for a text: it is written marked // logast:wording
        return !edits.isEmpty() && slots.values().stream().allMatch(s -> s.generated() != null);
    }

    public boolean reportOnly() {
        return edits.isEmpty();
    }
}
