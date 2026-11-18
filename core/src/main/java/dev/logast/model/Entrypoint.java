package dev.logast.model;

import java.util.ArrayList;
import java.util.List;

/**
 * @param observedByFramework the framework already opens an Observation (and trace) for this entrypoint
 * @param aliases             further labels of the same handler: the other paths of {@code @GetMapping({"/a", "/b"})}
 *                            (#51); the label is the first one
 */
public record Entrypoint(MethodInfo method, Kind kind, String label, boolean observedByFramework, List<String> aliases) {
    public Entrypoint(MethodInfo method, Kind kind, String label, boolean observedByFramework) {
        this(method, kind, label, observedByFramework, List.of());
    }

    /** The label and its aliases: every name traffic.yaml and audit globs know this entrypoint by. */
    public List<String> labels() {
        List<String> out = new ArrayList<>(List.of(label));
        out.addAll(aliases);
        return out;
    }

    /**
     * BATCH: a CommandLineRunner/ApplicationRunner; STEP: a Spring Batch {@code Tasklet}; ITEM: a Spring Batch item
     * processor/writer (per item/chunk); SQS: {@code @SqsListener}; STREAM: the handler a Spring Cloud Stream
     * {@code Consumer}/{@code Function} bean binds (#15); REDIS: a Redis pub/sub listener or MessageListenerAdapter
     * delegate; INTEGRATION: a Spring Integration {@code @ServiceActivator}; GRPC: a gRPC service method (#15). New kinds
     * go last: traffic.yaml groups by ordinal.
     */
    public enum Kind { HTTP, SCHEDULED, KAFKA, JMS, RABBIT, EVENT, WEBSOCKET, BATCH, MAIN, CUSTOM, STEP, ITEM, SQS, STREAM,
        REDIS, INTEGRATION, GRPC }
}
