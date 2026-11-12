package dev.logast.model;

/**
 * @param observedByFramework the framework already opens an Observation (and trace) for this entrypoint
 */
public record Entrypoint(MethodInfo method, Kind kind, String label, boolean observedByFramework) {
    public enum Kind { HTTP, SCHEDULED, KAFKA, JMS, RABBIT, EVENT, BATCH, MAIN, CUSTOM }
}
