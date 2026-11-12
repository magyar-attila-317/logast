package dev.logast.orders;

import com.fasterxml.jackson.core.type.TypeReference;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;

/**
 * Known findings that do not fail CI. Order ids are stable across scans (rule + method + ordinal), so a finding
 * stays "known" while the code around it changes; only new ones count. Fixed ones simply disappear.
 */
public record Baseline(String created, Map<String, String> orders) {

    public static Baseline of(ScanResult r) {
        Map<String, String> ids = new TreeMap<>();
        r.orders().forEach(o -> ids.put(o.id(), o.rule() + " " + o.file() + ":" + o.line()));
        return new Baseline(Instant.now().toString(), ids);
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

    public List<WorkOrder> newOnly(List<WorkOrder> current) {
        return current.stream().filter(o -> !orders.containsKey(o.id())).toList();
    }

    /** Baseline entries that no longer occur: fixed since the baseline was taken. */
    public Set<String> fixed(List<WorkOrder> current) {
        Set<String> gone = new TreeSet<>(orders.keySet());
        current.forEach(o -> gone.remove(o.id()));
        return gone;
    }
}
