package dev.logast.orders;

import dev.logast.Analysis;

import java.util.List;
import java.util.Map;

/** What {@code logast scan} writes to {@code .logast/orders.json}. */
public record ScanResult(int version, String root, Analysis.Stats stats, Map<String, String> fingerprints,
                         List<WorkOrder> orders) {
}
