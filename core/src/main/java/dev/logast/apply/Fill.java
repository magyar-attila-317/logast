package dev.logast.apply;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The LLM's answer for one order: {@code {"MSG": "Payment charge failed", "args": ["amount"]}}.
 * Every key except {@code args} is a slot name.
 */
public final class Fill {
    private final Map<String, String> slots = new LinkedHashMap<>();
    private final List<String> args = new ArrayList<>();

    @JsonCreator
    public Fill(@JsonProperty("args") List<String> args) {
        if (args != null) {
            this.args.addAll(args);
        }
    }

    public static Fill of(Map<String, String> slots, List<String> args) {
        Fill f = new Fill(args);
        f.slots.putAll(slots);
        return f;
    }

    @JsonAnySetter
    void slot(String name, Object value) {
        if (!name.startsWith("_") && value != null) { // "_MSG", "_where": hints from scan --format fills-template
            slots.put(name, String.valueOf(value));
        }
    }

    public Map<String, String> slots() {
        return slots;
    }

    public List<String> args() {
        return args;
    }
}
