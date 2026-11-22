package dev.logast.rules;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * What one phase of a scan plans and every later rule reads (Rules.scan runs the phases in order). Rules talk to each
 * other only through these: a rule that reads a plan runs after the phase that writes it, and a write after the phase
 * ended fails ({@code UnsupportedOperationException}) instead of being missed by the rules that already read it.
 */
final class Plans {
    private Plans() {
    }

    /**
     * The entry phase (setup, tracing, reactive and entrypoint rules): which entrypoints get a wrapper, a started line
     * and which MDC keys, and which framework observations are switched on.
     */
    static final class Entry {
        private Set<String> silent = new HashSet<>();
        private Map<String, String> generatedStart = new HashMap<>();
        private Set<String> wrapped = new HashSet<>();
        private Set<String> observedWrapped = new HashSet<>();
        private Map<String, String> entryOrders = new HashMap<>();
        private Map<String, Set<String>> entryKeys = new HashMap<>();
        private boolean kafkaObservation;
        private boolean rabbitObservation;
        private boolean observedAnnotationsActive = true;
        private boolean frozen;

        /** Entrypoints that ENTRYPOINT_SILENT gives a "started" line. */
        Set<String> silent() {
            return silent;
        }

        /** #231: method key → the subject of the started line written this pass with generated wording. */
        Map<String, String> generatedStart() {
            return generatedStart;
        }

        /** Methods whose body an entrypoint order rewraps. */
        Set<String> wrapped() {
            return wrapped;
        }

        /** Methods this scan wraps in ObservedContext (a subset of wrapped: the others get an MdcScope only). */
        Set<String> observedWrapped() {
            return observedWrapped;
        }

        /** Entrypoint method key → id of the order that scopes its domain MDC keys. */
        Map<String, String> entryOrders() {
            return entryOrders;
        }

        /** Entrypoint method key → the MDC keys its order puts in scope. */
        Map<String, Set<String>> entryKeys() {
            return entryKeys;
        }

        /** TracingRules switches on Kafka listener observation: listeners count as observed. */
        boolean kafkaObservation() {
            return kafkaObservation;
        }

        boolean rabbitObservation() {
            return rabbitObservation;
        }

        /**
         * Spring Boot processes @Observed / @NewSpan / @Timed / @Counted (or an order of this scan makes it do so): only
         * then does such an annotation count as observing or measuring its method (OBSERVED_ANNOTATION_INACTIVE).
         */
        boolean observedAnnotationsActive() {
            return observedAnnotationsActive;
        }

        void kafkaObservation(boolean planned) {
            writable();
            kafkaObservation = planned;
        }

        void rabbitObservation(boolean planned) {
            writable();
            rabbitObservation = planned;
        }

        void observedAnnotationsActive(boolean active) {
            writable();
            observedAnnotationsActive = active;
        }

        private void writable() {
            if (frozen) {
                throw new UnsupportedOperationException("the entry plan is written after the entry phase");
            }
        }

        void freeze() {
            frozen = true;
            silent = Collections.unmodifiableSet(silent);
            generatedStart = Collections.unmodifiableMap(generatedStart);
            wrapped = Collections.unmodifiableSet(wrapped);
            observedWrapped = Collections.unmodifiableSet(observedWrapped);
            entryOrders = Collections.unmodifiableMap(entryOrders);
            entryKeys = deep(entryKeys);
        }
    }

    /** The MDC phase (REPEATED_LOG_VALUE): the keys planned scopes put in MDC, and which order puts each one there. */
    static final class Mdc {
        private Map<String, Set<String>> ownKeys = new HashMap<>();
        private Map<String, Set<String>> localKeys = new HashMap<>();
        private Map<String, String> by = new HashMap<>();

        /** Keys a planned wrap sets for a whole method body (passed on to callees). */
        Map<String, Set<String>> ownKeys() {
            return ownKeys;
        }

        /** Keys a planned wrap sets after a local declaration: valid inside the method only. */
        Map<String, Set<String>> localKeys() {
            return localKeys;
        }

        /** "methodKey|mdcKey" → id of the order that will put it in MDC (message rewrites that drop it depend on it). */
        Map<String, String> by() {
            return by;
        }

        void freeze() {
            ownKeys = deep(ownKeys);
            localKeys = deep(localKeys);
            by = Collections.unmodifiableMap(by);
        }
    }

    /** The same map and sets, unmodifiable (no copies: their iteration order stays what the rules saw). */
    private static Map<String, Set<String>> deep(Map<String, Set<String>> m) {
        m.replaceAll((k, v) -> Collections.unmodifiableSet(v));
        return Collections.unmodifiableMap(m);
    }
}
