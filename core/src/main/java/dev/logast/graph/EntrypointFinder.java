package dev.logast.graph;

import dev.logast.model.Annotations;
import dev.logast.model.ClassInfo;
import dev.logast.model.Entrypoint;
import dev.logast.model.Entrypoint.Kind;
import dev.logast.model.MethodInfo;
import dev.logast.policy.Policy;
import org.openrewrite.java.tree.Flag;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.TypeUtils;

import java.util.*;

public final class EntrypointFinder {
    private static final String WEB = "org.springframework.web.bind.annotation.";
    private static final Map<String, String> HTTP_VERBS = Map.of(
            WEB + "GetMapping", "GET", WEB + "PostMapping", "POST", WEB + "PutMapping", "PUT",
            WEB + "DeleteMapping", "DELETE", WEB + "PatchMapping", "PATCH", WEB + "RequestMapping", "ANY");
    private static final Map<String, Kind> MESSAGING = new LinkedHashMap<>();

    static {
        MESSAGING.put("org.springframework.scheduling.annotation.Scheduled", Kind.SCHEDULED);
        MESSAGING.put("org.springframework.kafka.annotation.KafkaListener", Kind.KAFKA);
        MESSAGING.put("org.springframework.jms.annotation.JmsListener", Kind.JMS);
        MESSAGING.put("org.springframework.amqp.rabbit.annotation.RabbitListener", Kind.RABBIT);
        MESSAGING.put("org.springframework.context.event.EventListener", Kind.EVENT);
        MESSAGING.put("org.springframework.transaction.event.TransactionalEventListener", Kind.EVENT);
        // STOMP over WebSocket: only the HTTP handshake is observed, each inbound message runs without a trace
        MESSAGING.put("org.springframework.messaging.handler.annotation.MessageMapping", Kind.WEBSOCKET);
        MESSAGING.put("org.springframework.messaging.simp.annotation.SubscribeMapping", Kind.WEBSOCKET);
        MESSAGING.put("io.awspring.cloud.sqs.annotation.SqsListener", Kind.SQS);
    }

    /**
     * What decides whether the entrypoints found by type or bean (not by annotation alone) exist and are observed (#15).
     *
     * @param sqsObserved   spring.cloud.aws.sqs.observation-enabled is true (Spring Cloud AWS, default false)
     * @param batchObserved Spring Batch sets the ObservationRegistry on jobs and steps: Batch 6 (its
     *                      DefaultBatchConfiguration, which Boot 4 extends, imports BatchObservabilityBeanPostProcessor),
     *                      {@code @EnableBatchProcessing}, or that post-processor registered in source
     * @param stream        Spring Cloud Stream is on the classpath: Consumer/Function beans are bound to destinations
     * @param functions     spring.cloud.function.definition split into function names; null when not set
     * @param autodetect    spring.cloud.stream.function.autodetect is not false
     */
    public record Platform(boolean sqsObserved, boolean batchObserved, boolean stream, List<String> functions, boolean autodetect) {
        public static final Platform NONE = new Platform(false, false, false, null, true);
    }

    private final Policy policy;
    private final boolean kafkaObserved;
    private final boolean rabbitObserved;
    private final Platform platform;

    public EntrypointFinder(Policy policy) {
        this(policy, policy.entrypoints().kafkaObservationEnabled(), false);
    }

    public EntrypointFinder(Policy policy, boolean kafkaObserved) {
        this(policy, kafkaObserved, false);
    }

    /**
     * @param kafkaObserved  listener observation is on (policy or spring.kafka.listener.observation-enabled)
     * @param rabbitObserved spring.rabbitmq.listener.{simple,direct}.observation-enabled is true
     */
    public EntrypointFinder(Policy policy, boolean kafkaObserved, boolean rabbitObserved) {
        this(policy, kafkaObserved, rabbitObserved, Platform.NONE);
    }

    public EntrypointFinder(Policy policy, boolean kafkaObserved, boolean rabbitObserved, Platform platform) {
        this.rabbitObserved = rabbitObserved;
        this.policy = policy;
        this.kafkaObserved = kafkaObserved;
        this.platform = platform;
    }

    public List<Entrypoint> find(CodeIndex index) {
        List<Entrypoint> out = new ArrayList<>();
        Map<String, Entrypoint> bound = new HashMap<>();
        FunctionalEntrypoints.routes(index, bound);
        if (platform.stream()) {
            FunctionalEntrypoints.streamFunctions(index, platform.functions(), platform.autodetect(), bound);
        }
        for (MethodInfo m : index.methods()) {
            Entrypoint ep = classify(m, index);
            if (ep == null && m.hasBody()) {
                ep = bound.get(m.key());
            }
            if (ep != null) {
                out.add(ep);
            }
        }
        out.sort(Comparator.comparing(Entrypoint::label));
        return out;
    }

    private Entrypoint classify(MethodInfo m, CodeIndex index) {
        if (!m.hasBody()) {
            return null;
        }
        Entrypoint http = http(m, index);
        if (http != null) {
            return http;
        }
        for (J.Annotation a : m.decl().getLeadingAnnotations()) {
            for (var e : MESSAGING.entrySet()) {
                if (Annotations.is(a, e.getKey())) {
                    return messaging(m, a, e.getValue());
                }
            }
            for (String extra : policy.entrypoints().extraAnnotations()) {
                if (extra.contains(".") ? Annotations.is(a, extra) : a.getSimpleName().equals(extra)) {
                    return new Entrypoint(m, Kind.CUSTOM, "@" + a.getSimpleName() + " " + m.label(), false);
                }
            }
        }
        ClassInfo owner = m.owner();
        Entrypoint batch = springBatch(m, owner);
        if (batch != null) {
            return batch;
        }
        if (WEBSOCKET_CALLBACKS.contains(m.name()) && m.decl().getBody() != null
                && TypeUtils.isAssignableTo("org.springframework.web.socket.WebSocketHandler", owner.type())) {
            return new Entrypoint(m, Kind.WEBSOCKET, "websocket " + m.label(), false);
        }
        if (m.name().equals("run") && (TypeUtils.isAssignableTo("org.springframework.boot.CommandLineRunner", owner.type())
                || TypeUtils.isAssignableTo("org.springframework.boot.ApplicationRunner", owner.type()))) {
            return new Entrypoint(m, Kind.BATCH, "batch " + m.label(), false);
        }
        if (policy.entrypoints().includeMain() && m.name().equals("main") && m.type().hasFlags(Flag.Static)) {
            return new Entrypoint(m, Kind.MAIN, "main " + owner.simpleName(), false);
        }
        return null;
    }

    /**
     * A request mapping on the method, else on the method it overrides in a source interface or superclass: Spring
     * finds mapping annotations through the type hierarchy (API-first controllers implement a mapped interface, #51).
     */
    private static Entrypoint http(MethodInfo m, CodeIndex index) {
        List<MethodInfo> declarations = new ArrayList<>();
        declarations.add(m);
        if (controller(m.owner(), index)) {
            // only a controller serves the mappings it inherits: a client stub or a Feign fallback implementing a
            // mapped interface is not an endpoint
            declarations.addAll(index.overridden(m));
        }
        for (MethodInfo d : declarations) {
            for (J.Annotation a : d.decl().getLeadingAnnotations()) {
                for (var e : HTTP_VERBS.entrySet()) {
                    if (Annotations.is(a, e.getKey())) {
                        return new Entrypoint(m, Kind.HTTP, httpLabel(m, a, e.getValue(), index), true);
                    }
                }
            }
        }
        return null;
    }

    /** {@code @Controller} / {@code @RestController} on the class or a source supertype: what Spring MVC maps. */
    private static boolean controller(ClassInfo c, CodeIndex index) {
        List<ClassInfo> types = new ArrayList<>();
        types.add(c);
        types.addAll(index.sourceSupertypes(c));
        return types.stream().flatMap(t -> t.decl().getLeadingAnnotations().stream())
                .anyMatch(a -> Annotations.is(a, "org.springframework.stereotype.Controller")
                        || Annotations.is(a, WEB + "RestController"));
    }

    private Entrypoint messaging(MethodInfo m, J.Annotation a, Kind kind) {
        String label = switch (kind) {
            case KAFKA -> "@KafkaListener " + Annotations.stringAttribute(a, "topics", "value").orElse(m.label());
            case JMS -> "@JmsListener " + Annotations.stringAttribute(a, "destination").orElse(m.label());
            case RABBIT -> "@RabbitListener " + Annotations.stringAttribute(a, "queues", "value").orElse(m.label());
            case SQS -> "@SqsListener " + Annotations.stringAttribute(a, "value", "queueNames").orElse(m.label());
            case WEBSOCKET -> "@" + a.getSimpleName() + " " + Annotations.stringAttribute(a, "value", "destination").orElse(m.label());
            case EVENT -> "@" + a.getSimpleName() + " " + m.label() + eventType(m, a);
            default -> "@" + a.getSimpleName() + " " + m.label();
        };
        boolean observed = switch (kind) {
            case SCHEDULED -> true;
            // synchronous listeners run in the publisher's thread, inside its span; @Async ones get the
            // caller's context through the TaskDecorator (logging-support), so neither starts a new trace.
            // Spring's own lifecycle events (ApplicationReadyEvent, ContextRefreshedEvent, ...) are published
            // outside any request: those listeners have no trace unless they open one.
            case EVENT -> !lifecycleEvent(m, a);
            // per-record observation does not cover batch listeners (Spring Kafka and Spring AMQP alike)
            case KAFKA -> kafkaObserved && !batch(m, a);
            // Spring Boot (3.2.1+) puts the ObservationRegistry on the @JmsListener container factory itself
            case JMS -> true;
            case RABBIT -> rabbitObserved && !batch(m, a);
            // Spring Cloud AWS observes each message (spring.cloud.aws.sqs.observation-enabled); its batch sink none
            case SQS -> platform.sqsObserved() && !batch(m, a);
            default -> false;
        };
        return new Entrypoint(m, kind, label, observed);
    }

    /** A listener that gets a batch of records: {@code batch = "true"}, or a List / ConsumerRecords parameter. */
    public static boolean batch(MethodInfo m, J.Annotation a) {
        if (a != null && Annotations.rawAttribute(a, "batch").map(v -> v.contains("true")).orElse(false)) {
            return true;
        }
        return !m.type().getParameterTypes().isEmpty() && (org.openrewrite.java.tree.TypeUtils.isAssignableTo("java.util.List",
                m.type().getParameterTypes().getFirst()) || org.openrewrite.java.tree.TypeUtils.isAssignableTo(
                "org.apache.kafka.clients.consumer.ConsumerRecords", m.type().getParameterTypes().getFirst()));
    }

    /** Spring Batch 5 and 6 (6 moved the item types to {@code org.springframework.batch.infrastructure}). */
    private static final String TASKLET = "org.springframework.batch.core.step.tasklet.Tasklet";
    private static final Map<String, String> ITEM_TYPES = Map.of(
            "org.springframework.batch.item.ItemProcessor", "process", "org.springframework.batch.item.ItemWriter", "write",
            "org.springframework.batch.infrastructure.item.ItemProcessor", "process",
            "org.springframework.batch.infrastructure.item.ItemWriter", "write");

    /**
     * A Spring Batch step's own code: {@code Tasklet.execute} (once per step run) and the {@code process}/{@code write}
     * of an item processor or writer (per item/chunk), as classes. Both run inside the step's observation when Batch
     * has the registry (Spring Batch reference, Monitoring and metrics / Tracing).
     */
    private Entrypoint springBatch(MethodInfo m, ClassInfo owner) {
        if (owner.type() == null || m.type().hasFlags(Flag.Static)) {
            return null;
        }
        if (m.name().equals("execute") && m.type().getParameterTypes().size() == 2 && TypeUtils.isAssignableTo(TASKLET, owner.type())) {
            return new Entrypoint(m, Kind.STEP, "tasklet " + m.label(), platform.batchObserved());
        }
        for (var e : ITEM_TYPES.entrySet()) {
            if (m.name().equals(e.getValue()) && m.type().getParameterTypes().size() == 1 && TypeUtils.isAssignableTo(e.getKey(), owner.type())) {
                return new Entrypoint(m, Kind.ITEM, (e.getValue().equals("process") ? "item processor " : "item writer ") + m.label(),
                        platform.batchObserved());
            }
        }
        return null;
    }

    /** WebSocketHandler methods Spring calls per connection event or message, outside any HTTP request. */
    private static final java.util.Set<String> WEBSOCKET_CALLBACKS = java.util.Set.of("afterConnectionEstablished",
            "handleMessage", "handleTextMessage", "handleBinaryMessage", "handlePongMessage", "handleTransportError",
            "afterConnectionClosed");

    /** Events Spring publishes itself during startup/shutdown, never inside a trace. */
    private static final java.util.Set<String> LIFECYCLE_EVENTS = java.util.Set.of(
            "ApplicationStartingEvent", "ApplicationEnvironmentPreparedEvent", "ApplicationContextInitializedEvent",
            "ApplicationPreparedEvent", "ApplicationStartedEvent", "ApplicationReadyEvent", "ApplicationFailedEvent",
            "ContextRefreshedEvent", "ContextStartedEvent", "ContextStoppedEvent", "ContextClosedEvent",
            "WebServerInitializedEvent", "ServletWebServerInitializedEvent", "ReactiveWebServerInitializedEvent",
            "AvailabilityChangeEvent");

    static boolean lifecycleEvent(MethodInfo m, J.Annotation a) {
        java.util.List<String> types = new java.util.ArrayList<>();
        m.type().getParameterTypes().stream().findFirst().map(t -> Types.erasure(t)).ifPresent(types::add);
        Annotations.rawAttribute(a, "classes").ifPresent(types::add);
        Annotations.rawAttribute(a, "value").ifPresent(types::add);
        return types.stream().filter(java.util.Objects::nonNull)
                .flatMap(t -> java.util.Arrays.stream(t.split("[^\\w.$]+")))
                .map(t -> t.endsWith(".class") ? t.substring(0, t.length() - 6) : t)
                .map(t -> t.substring(t.lastIndexOf('.') + 1))
                .anyMatch(LIFECYCLE_EVENTS::contains);
    }

    /** {@code (OrderPlaced)} from the parameter, else from {@code @EventListener(X.class)}. */
    private static String eventType(MethodInfo m, J.Annotation a) {
        if (!m.type().getParameterTypes().isEmpty()) {
            return firstParamType(m);
        }
        return Annotations.rawAttribute(a, "classes").or(() -> Annotations.rawAttribute(a, "value"))
                .map(s -> "(" + s.replaceAll("\\.class\\b", "").replaceAll("[{}\\s]", "") + ")").orElse("()");
    }

    private static String firstParamType(MethodInfo m) {
        return m.type().getParameterTypes().isEmpty() ? "()"
                : "(" + Types.display(m.type().getParameterTypes().getFirst()) + ")";
    }

    private static String httpLabel(MethodInfo m, J.Annotation a, String verb, CodeIndex index) {
        if (verb.equals("ANY")) {
            verb = Annotations.rawAttribute(a, "method")
                    .map(s -> s.substring(s.lastIndexOf('.') + 1).replaceAll("[^A-Z]", ""))
                    .filter(s -> !s.isEmpty()).orElse("ANY");
        }
        // the class-level mapping: on the controller, else on its nearest source supertype that has one
        List<ClassInfo> types = new ArrayList<>();
        types.add(m.owner());
        types.addAll(index.sourceSupertypes(m.owner()));
        String prefix = types.stream().flatMap(t -> t.decl().getLeadingAnnotations().stream())
                .filter(c -> Annotations.is(c, WEB + "RequestMapping"))
                .findFirst().flatMap(c -> path(c, index)).orElse("");
        String path = path(a, index).orElse("");
        String joined = ("/" + prefix + "/" + path).replaceAll("/+", "/");
        if (joined.length() > 1 && joined.endsWith("/")) {
            joined = joined.substring(0, joined.length() - 1);
        }
        return verb + " " + joined;
    }

    /** The {@code value}/{@code path} of a mapping: a literal, a source constant or a concatenation of them (#51). */
    private static Optional<String> path(J.Annotation a, CodeIndex index) {
        return Annotations.attribute(a, "value", "path").map(e -> e instanceof J.NewArray na
                        && na.getInitializer() != null && !na.getInitializer().isEmpty() ? na.getInitializer().getFirst() : e)
                .map(e -> Constants.concatenated(index, e));
    }
}
