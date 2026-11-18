package dev.logast.model;

/**
 * @param observedByFramework the framework already opens an Observation (and trace) for this entrypoint
 */
public record Entrypoint(MethodInfo method, Kind kind, String label, boolean observedByFramework) {
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
