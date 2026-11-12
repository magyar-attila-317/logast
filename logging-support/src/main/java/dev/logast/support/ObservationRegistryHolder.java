package dev.logast.support;

import io.micrometer.observation.ObservationRegistry;

/** The application's ObservationRegistry for static entry points; a no-op registry until Spring sets it. */
public final class ObservationRegistryHolder {
    private static volatile ObservationRegistry registry = ObservationRegistry.NOOP;

    private ObservationRegistryHolder() {
    }

    public static ObservationRegistry get() {
        return registry;
    }

    public static void set(ObservationRegistry r) {
        registry = r == null ? ObservationRegistry.NOOP : r;
    }
}
