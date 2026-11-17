package dev.logast.orders;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.type.TypeReference;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;

/**
 * Known findings that do not fail CI. Order ids are stable across scans (rule + method + ordinal), so a finding
 * stays "known" while the code around it changes; only new ones count. Fixed ones simply disappear.
 * <p>
 * An aggregated finding keeps one id however many places it covers (every unlocked {@code @Scheduled} job is one
 * "scheduled-lock" order), so {@code places} records how many of its places sit in each file: a known id with more
 * places in some file than recorded has grown and counts as new (#55). Per file, not per line, so code moving inside
 * a file does not make a known finding new.
 *
 * @param version logast version that recorded it (order ids may change between versions)
 * @param places  per order id: file → number of places (main location plus {@link WorkOrder#locations()}) in it;
 *                null in baselines recorded before it existed, which then compare by id only
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Baseline(String created, String version, Map<String, String> orders, Map<String, Map<String, Integer>> places) {

    public static Baseline of(ScanResult r) {
        Map<String, String> ids = new TreeMap<>();
        Map<String, Map<String, Integer>> places = new TreeMap<>();
        r.orders().forEach(o -> {
            ids.put(o.id(), o.rule() + " " + o.file() + ":" + o.line());
            places.put(o.id(), places(o));
        });
        return new Baseline(Instant.now().toString(), currentVersion(), ids, places);
    }

    /** The running logast's version ("dev" outside a built distribution). */
    public static String currentVersion() {
        Properties p = new Properties();
        try (InputStream in = Baseline.class.getResourceAsStream("/logast-version.properties")) {
            if (in != null) {
                p.load(in);
            }
        } catch (IOException e) {
            // no version: treat as dev
        }
        return p.getProperty("version", "dev");
    }

    private static Map<String, Integer> places(WorkOrder o) {
        Map<String, Integer> perFile = new TreeMap<>();
        perFile.merge(o.file(), 1, Integer::sum);
        o.locations().forEach(l -> perFile.merge(l.file(), 1, Integer::sum));
        return perFile;
    }

    public static Baseline read(Path file) {
        try {
            return OrderJson.MAPPER.readValue(file.toFile(), new TypeReference<Baseline>() {
            });
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read baseline " + file, e);
        }
    }

    public void write(Path file) {
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            OrderJson.MAPPER.writeValue(file.toFile(), this);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Orders not in the baseline, and known ones that cover more places in some file than when it was recorded. */
    public List<WorkOrder> newOnly(List<WorkOrder> current) {
        return current.stream().filter(o -> !orders.containsKey(o.id()) || grew(o)).toList();
    }

    private boolean grew(WorkOrder o) {
        Map<String, Integer> known = places == null ? null : places.get(o.id());
        return known != null && places(o).entrySet().stream().anyMatch(e -> e.getValue() > known.getOrDefault(e.getKey(), 0));
    }

    /** Baseline entries that no longer occur: fixed since the baseline was taken. */
    public Set<String> fixed(List<WorkOrder> current) {
        Set<String> gone = new TreeSet<>(orders.keySet());
        current.forEach(o -> gone.remove(o.id()));
        return gone;
    }
}
