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
     * delegate; INTEGRATION: a Spring Integration {@code @ServiceActivator}; GRPC: a gRPC service method (#15); QUARTZ: a Quartz job's
     * {@code execute} (#232). New kinds
     * go last: traffic.yaml groups by ordinal.
     */
    public enum Kind {
        HTTP(Trait.REQUEST, Trait.TIMED_BY_FRAMEWORK, Trait.DEBUG_ON_SUCCESS, Trait.USER_FACING),
        SCHEDULED(Trait.RUN, Trait.JOB, Trait.ONE_OUTCOME, Trait.NO_USER, Trait.TIMED_BY_FRAMEWORK),
        KAFKA(Trait.JOB, Trait.ONE_OUTCOME, Trait.NO_USER, Trait.REQUEST, Trait.TIMED_BY_FRAMEWORK, Trait.DEBUG_ON_SUCCESS, Trait.REDELIVERED),
        JMS(Trait.JOB, Trait.ONE_OUTCOME, Trait.NO_USER, Trait.REQUEST, Trait.TIMED_BY_FRAMEWORK, Trait.DEBUG_ON_SUCCESS),
        RABBIT(Trait.JOB, Trait.ONE_OUTCOME, Trait.NO_USER, Trait.REQUEST, Trait.TIMED_BY_FRAMEWORK, Trait.DEBUG_ON_SUCCESS, Trait.REDELIVERED),
        EVENT(Trait.JOB, Trait.ONE_OUTCOME, Trait.REQUEST),
        WEBSOCKET(Trait.JOB, Trait.ONE_OUTCOME, Trait.REQUEST, Trait.DEBUG_ON_SUCCESS, Trait.USER_FACING),
        BATCH(Trait.RUN, Trait.JOB, Trait.ONE_OUTCOME, Trait.NO_USER, Trait.TIMED_BY_FRAMEWORK),
        MAIN(Trait.RUN, Trait.JOB, Trait.ONE_OUTCOME, Trait.NO_USER),   // #240: a run JOB_OUTCOME_MISSING covers
        CUSTOM(Trait.JOB, Trait.REQUEST),
        STEP(Trait.RUN, Trait.JOB, Trait.ONE_OUTCOME, Trait.NO_USER, Trait.TIMED_BY_FRAMEWORK),
        ITEM(Trait.JOB, Trait.ONE_OUTCOME, Trait.NO_USER, Trait.TIMED_BY_FRAMEWORK, Trait.DEBUG_ON_SUCCESS),
        SQS(Trait.JOB, Trait.ONE_OUTCOME, Trait.NO_USER, Trait.REQUEST, Trait.TIMED_BY_FRAMEWORK, Trait.DEBUG_ON_SUCCESS),
        STREAM(Trait.JOB, Trait.ONE_OUTCOME, Trait.NO_USER, Trait.REQUEST, Trait.TIMED_BY_FRAMEWORK, Trait.DEBUG_ON_SUCCESS),
        REDIS(Trait.JOB, Trait.ONE_OUTCOME, Trait.NO_USER, Trait.DEBUG_ON_SUCCESS),   // #239: a message, no user behind it
        INTEGRATION(Trait.JOB, Trait.ONE_OUTCOME, Trait.NO_USER, Trait.TIMED_BY_FRAMEWORK, Trait.DEBUG_ON_SUCCESS),   // #239
        GRPC(Trait.TIMED_BY_FRAMEWORK, Trait.DEBUG_ON_SUCCESS, Trait.USER_FACING),
        QUARTZ(Trait.RUN, Trait.JOB, Trait.ONE_OUTCOME, Trait.NO_USER);

        private final java.util.Set<Trait> traits;

        Kind(Trait... traits) {
            this.traits = java.util.Collections.unmodifiableSet(java.util.EnumSet.copyOf(java.util.List.of(traits)));
        }

        public boolean is(Trait trait) {
            return traits.contains(trait);
        }
    }

    /**
     * What a kind of entrypoint is, asked by the rules: one place to read (and to change) what a new kind means. A
     * trait a kind lacks here is a decision, not an oversight to fill in silently: change it with the rule it affects.
     */
    public enum Trait {
        /** A run (a job, a batch, main): started and completed in its own method; runs a handful of times a day. */
        RUN,
        /** A job or listener whose silence and outcome log coverage measures (CoverageRules). */
        JOB,
        /** One outcome per run or message: its own flow is where the outcome is logged (HTTP has http.server.requests). */
        ONE_OUTCOME,
        /** No user behind it (a job, a step, a message, startup): audit records name a system actor. */
        NO_USER,
        /** It serves a request or message someone waits on: blocking calls under it are on a request path. */
        REQUEST,
        /** Its framework times it when observed (http.server.requests, spring.kafka.listener, …): no second timer. */
        TIMED_BY_FRAMEWORK,
        /** One per message or request: the wrapper's "completed" line goes to DEBUG; failures stay ERROR. */
        DEBUG_ON_SUCCESS,
        /** Its framework redelivers a failed message (Kafka's DefaultErrorHandler, RabbitMQ's requeue). */
        REDELIVERED,
        /** A user waits on the answer: a quick retry at most. */
        USER_FACING
    }
}
