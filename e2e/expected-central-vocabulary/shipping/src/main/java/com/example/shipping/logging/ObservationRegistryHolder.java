// Copied by logast (support.mode: vendor) from logging-support. Keep in sync or switch to the starter.
// Source: logast, https://github.com/magyar-attila-317/logast (Apache License 2.0)
package com.example.shipping.logging;

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
