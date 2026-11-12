package dev.logast.orders;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * How much of what should be logged is logged: per part {covered, total}.
 * <ul>
 *   <li>jobs: jobs/listeners with a start and an outcome line;</li>
 *   <li>http: HTTP endpoints that log anything at INFO or above on their path;</li>
 *   <li>stateChanges: methods writing through a repository that log at INFO or above;</li>
 *   <li>skips: early returns on job paths that say why;</li>
 *   <li>loops: loops doing work on job paths with a summary line after them.</li>
 * </ul>
 */
public record LogCoverage(Map<String, int[]> parts) {
    public LogCoverage {
        parts = parts == null ? Map.of() : new LinkedHashMap<>(parts);
    }

    public int covered() {
        return parts.values().stream().mapToInt(c -> c[0]).sum();
    }

    public int total() {
        return parts.values().stream().mapToInt(c -> c[1]).sum();
    }

    /** Percent of all checked places that log, or 100 when there is nothing to check. */
    public double percent() {
        return total() == 0 ? 100.0 : Math.round(1000.0 * covered() / total()) / 10.0;
    }

    /** "71% (jobs 3/4 · http 12/14 · stateChanges 5/9 · skips 2/6 · loops 1/3)" */
    public String line() {
        StringBuilder sb = new StringBuilder();
        parts.forEach((k, c) -> sb.append(sb.isEmpty() ? "" : " · ").append(k).append(' ').append(c[0]).append('/').append(c[1]));
        return percent() + "%" + (sb.isEmpty() ? "" : " (" + sb + ")");
    }
}
