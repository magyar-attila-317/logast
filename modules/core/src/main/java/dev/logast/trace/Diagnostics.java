package dev.logast.trace;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * #218: why the tracer knows what it knows, when asked ({@code LOGAST_TRACE=1}, or {@code -Dlogast.trace=true}): what it
 * could not know, counted by reason, so a run on a real service can say where it stopped.
 */
public final class Diagnostics {
    private static final ThreadLocal<Map<String, Integer>> UNKNOWNS = ThreadLocal.withInitial(LinkedHashMap::new);

    private Diagnostics() {
    }

    public static boolean enabled() {
        return System.getenv("LOGAST_TRACE") != null && !System.getenv("LOGAST_TRACE").isBlank()
                || Boolean.getBoolean("logast.trace");
    }

    static void unknown(String why) {
        if (enabled()) {
            UNKNOWNS.get().merge(why == null ? "?" : why, 1, Integer::sum);
        }
    }

    /** The reasons counted since the last reset, most frequent first. */
    public static Map<String, Integer> unknowns() {
        Map<String, Integer> out = new LinkedHashMap<>();
        UNKNOWNS.get().entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).forEach(e -> out.put(e.getKey(), e.getValue()));
        return out;
    }

    public static void reset() {
        UNKNOWNS.get().clear();
    }
}
