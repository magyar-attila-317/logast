package dev.logast.apply;

import dev.logast.orders.Slot;
import dev.logast.orders.WorkOrder;
import dev.logast.rules.DomainValues;
import dev.logast.rules.Glossary;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Checks LLM-provided slot text and extra arguments before anything is written. */
public final class FillValidator {
    private static final Pattern MANUAL_SUFFIX = Pattern.compile("\\[[^\\]]*=");
    private final Glossary glossary;
    private final DomainValues domain;
    private final dev.logast.rules.Phrasing phrasing;

    public FillValidator(dev.logast.policy.Policy policy) {
        this.glossary = new Glossary(policy);
        this.domain = new DomainValues(policy);
        this.phrasing = new dev.logast.rules.Phrasing(policy);
    }

    /** @return problems; empty when the fill is acceptable */
    public List<String> validate(WorkOrder order, Fill fill) {
        List<String> problems = new ArrayList<>();
        if (order.reportOnly()) {
            problems.add("report-only order, nothing to apply: " + order.suggestion());
            return problems;
        }
        for (var e : order.slots().entrySet()) {
            String text = fill == null ? null : fill.slots().get(e.getKey());
            if (text == null || text.isBlank()) {
                problems.add("missing text for slot " + e.getKey());
                continue;
            }
            problems.addAll(text(e.getKey(), text.strip(), e.getValue()));
        }
        if (fill != null) {
            for (String name : fill.slots().keySet()) {
                if (!order.slots().containsKey(name)) {
                    problems.add("unknown slot " + name + " (order has " + order.slots().keySet() + ")");
                }
            }
            if (!fill.args().isEmpty()) {
                if (order.context() == null || order.context().argsSlot() == null) {
                    problems.add("this order does not take extra args");
                } else {
                    for (String arg : fill.args()) {
                        if (!order.context().varsInScope().containsKey(arg)) {
                            problems.add("arg '" + arg + "' is not a variable in scope " + order.context().varsInScope().keySet());
                        } else if (domain.isPiiName(arg)) {
                            problems.add("arg '" + arg + "' is PII and must not be logged");
                        }
                    }
                }
            }
        }
        return problems;
    }

    private List<String> text(String slot, String text, Slot def) {
        List<String> problems = new ArrayList<>();
        if (text.contains("\n")) {
            problems.add(slot + ": must be a single line");
        }
        if (text.length() > 160) {
            problems.add(slot + ": longer than 160 characters");
        }
        if (text.contains("{}")) {
            problems.add(slot + ": no {} placeholders; values go in the [k=v] suffix the tool adds (keys " + def.keys() + ")");
        }
        if (MANUAL_SUFFIX.matcher(text).find()) {
            problems.add(slot + ": do not write a [k=v] suffix; the tool appends it");
        }
        for (String v : glossary.violations(text)) {
            problems.add(slot + ": glossary " + v);
        }
        if (def.event() != null && !phrasing.matches(text, def.event())) {
            problems.add(slot + ": must read as '" + def.pattern() + "' (house phrasing for " + def.event()
                    + " events), e.g. '" + def.pattern().replace("{subject}", "Order sync") + "'");
        }
        return problems;
    }
}
