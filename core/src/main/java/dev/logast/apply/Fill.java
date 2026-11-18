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
    /** Hints from messages.yaml ("_MSG", "_where"): what the entry was written for, to find its line again. */
    private final Map<String, String> hints = new LinkedHashMap<>();

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
        } else if (value != null) {
            hints.put(name, String.valueOf(value));
        }
    }

    public Map<String, String> slots() {
        return slots;
    }

    public List<String> args() {
        return args;
    }

    /** "src/…/A.java:12 A.run" → "src/…/A.java", or null. */
    public String file() {
        String w = hints.get("_where");
        if (w == null) {
            return null;
        }
        String loc = w.contains(" ") ? w.substring(0, w.indexOf(' ')) : w;
        return loc.contains(":") ? loc.substring(0, loc.lastIndexOf(':')) : loc;
    }

    /** "src/…/A.java:12 A.run" → "A.run", or null. */
    public String method() {
        String w = hints.get("_where");
        return w == null || !w.contains(" ") ? null : w.substring(w.indexOf(' ') + 1).strip();
    }

    /** The wording the slot replaces ("… | was: Order placed | …"), or null when the hint does not say. */
    public String original(String slot) {
        String h = hints.get("_" + slot);
        if (h == null) {
            return null;
        }
        int at = h.indexOf(" | was: ");
        if (at < 0) {
            return null;
        }
        String rest = h.substring(at + " | was: ".length());
        int end = rest.indexOf(" | ");
        return end < 0 ? rest : rest.substring(0, end);
    }
}
