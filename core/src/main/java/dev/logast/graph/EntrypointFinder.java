package dev.logast.graph;

import dev.logast.model.Annotations;
import dev.logast.model.ClassInfo;
import dev.logast.model.Entrypoint;
import dev.logast.model.Entrypoint.Kind;
import dev.logast.model.MethodInfo;
import dev.logast.policy.Policy;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.Flag;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;
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
        MESSAGING.put("org.springframework.integration.annotation.ServiceActivator", Kind.INTEGRATION);
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
     * @param grpcObserved  Spring gRPC is on the classpath and spring.grpc.server.observation.enabled is not false (Boot's
     *                      GrpcServerObservationAutoConfiguration registers ObservationGrpcServerInterceptor globally)
     * @param integrationPatterns Spring Integration's observation patterns ({@code @EnableIntegrationManagement
     *                      (observationPatterns)} or spring.integration.management.observation-patterns); empty: none observed
     */
    public record Platform(boolean sqsObserved, boolean batchObserved, boolean stream, List<String> functions, boolean autodetect,
                           boolean grpcObserved, List<String> integrationPatterns) {
        public static final Platform NONE = new Platform(false, false, false, null, true, false, List.of());
    }

    /** A handler Spring calls that logast cannot name statically (#15), reported where it is registered. */
    public record Unresolved(MethodInfo site, int line, String what, String why) {
    }

    private final List<Unresolved> unresolved = new ArrayList<>();

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
        FunctionalEntrypoints.redisAdapters(index, bound, unresolved);
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

    /** Handlers {@link #find} saw registered but could not resolve to a source method. */
    public List<Unresolved> unresolved() {
        return unresolved;
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
                    if (e.getValue() == Kind.INTEGRATION && m.hasAnnotation("org.springframework.context.annotation.Bean")) {
                        continue;   // @Bean @ServiceActivator defines a MessageHandler bean: the handler is not this method
                    }
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
        Entrypoint listener = redisListener(m, owner);
        if (listener != null) {
            return listener;
        }
        Entrypoint grpc = grpcService(m, owner);
        if (grpc != null) {
            return grpc;
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
     * An interface outside the sources (a library jar, generated sources) is read from its type information.
     */
    private static Entrypoint http(MethodInfo m, CodeIndex index) {
        boolean controller = controller(m.owner(), index);
        List<MethodInfo> declarations = new ArrayList<>();
        declarations.add(m);
        if (controller) {
            // only a controller serves the mappings it inherits: a client stub or a Feign fallback implementing a
            // mapped interface is not an endpoint
            declarations.addAll(index.overridden(m));
        }
        for (MethodInfo d : declarations) {
            for (J.Annotation a : d.decl().getLeadingAnnotations()) {
                Mapping mapping = mapping(a, index);
                if (mapping != null) {
                    return httpEntrypoint(m, mapping, index);
                }
            }
        }
        if (controller) {
            for (JavaType.Method o : overriddenOutsideSource(m, index)) {
                for (JavaType.FullyQualified a : o.getAnnotations()) {
                    Mapping mapping = mapping(a, 0);
                    if (mapping != null) {
                        return httpEntrypoint(m, mapping, index);
                    }
                }
            }
        }
        return null;
    }

    /** {@code @Controller} / {@code @RestController} on the class or a source supertype, also through a meta-annotation. */
    private static boolean controller(ClassInfo c, CodeIndex index) {
        List<ClassInfo> types = new ArrayList<>();
        types.add(c);
        types.addAll(index.sourceSupertypes(c));
        return types.stream().flatMap(t -> t.decl().getLeadingAnnotations().stream())
                .anyMatch(a -> Annotations.is(a, CONTROLLER) || Annotations.is(a, WEB + "RestController")
                        || a.getType() instanceof JavaType.FullyQualified t && metaAnnotated(t, 0));
    }

    private static final String CONTROLLER = "org.springframework.stereotype.Controller";
    /** How deep meta-annotations are followed: Spring's own composed annotations are one or two levels deep. */
    private static final int META_DEPTH = 3;

    /** An annotation type annotated (directly or through its own annotations) with {@code @Controller}. */
    private static boolean metaAnnotated(JavaType.FullyQualified annotationType, int depth) {
        for (JavaType.FullyQualified meta : annotationType.getAnnotations()) {
            String fqn = meta.getFullyQualifiedName();
            if (fqn.equals(CONTROLLER) || fqn.equals(WEB + "RestController")
                    || !fqn.startsWith("java.lang.annotation.") && depth + 1 < META_DEPTH && metaAnnotated(meta, depth + 1)) {
                return true;
            }
        }
        return false;
    }

    /** A request mapping: its verb ({@code ANY} when a {@code @RequestMapping} names none) and its paths (none: ""). */
    private record Mapping(String verb, List<String> paths) {
    }

    /** {@code @GetMapping(...)} written in source, or an annotation meta-annotated with one (#51). */
    private static Mapping mapping(J.Annotation a, CodeIndex index) {
        for (var e : HTTP_VERBS.entrySet()) {
            if (Annotations.is(a, e.getKey())) {
                String verb = e.getValue();
                if (verb.equals("ANY")) {
                    verb = Annotations.rawAttribute(a, "method")
                            .map(s -> s.substring(s.lastIndexOf('.') + 1).replaceAll("[^A-Z]", ""))
                            .filter(s -> !s.isEmpty()).orElse("ANY");
                }
                return new Mapping(verb, paths(a, index));
            }
        }
        Mapping meta = a.getType() instanceof JavaType.FullyQualified t ? metaMapping(t, 0) : null;
        // a composed annotation's own value/path (Spring: @AliasFor to the mapping's path) replaces the meta paths
        return meta == null || paths(a, index).isEmpty() ? meta : new Mapping(meta.verb(), paths(a, index));
    }

    /** The first mapping among the annotations of an annotation type, followed up to {@link #META_DEPTH} levels. */
    private static Mapping metaMapping(JavaType.FullyQualified annotationType, int depth) {
        for (JavaType.FullyQualified meta : annotationType.getAnnotations()) {
            Mapping m = mapping(meta, depth + 1);
            if (m != null) {
                return m;
            }
        }
        return null;
    }

    /**
     * A mapping from type information (meta-annotations, interfaces in library jars or generated sources): javac has
     * evaluated the attribute values, constants included.
     */
    private static Mapping mapping(JavaType.FullyQualified a, int depth) {
        String fqn = a.getFullyQualifiedName();
        if (fqn.startsWith("java.lang.annotation.") || depth > META_DEPTH) {
            return null;
        }
        String verb = HTTP_VERBS.get(fqn);
        List<String> own = values(a, "value", "path");
        if (verb == null) {
            Mapping meta = metaMapping(a, depth);
            return meta == null || own.isEmpty() ? meta : new Mapping(meta.verb(), own);
        }
        return new Mapping(verb.equals("ANY") ? values(a, "method").stream().findFirst().orElse("ANY") : verb, own);
    }

    /** The values of the first attribute named {@code names} that is set: strings, or enum constant names. */
    private static List<String> values(JavaType.FullyQualified a, String... names) {
        if (!(a instanceof JavaType.Annotation annotation)) {
            return List.of();
        }
        for (String n : names) {
            for (JavaType.Annotation.ElementValue v : annotation.getValues()) {
                if (v.getElement() instanceof JavaType.Method element && element.getName().equals(n)) {
                    List<?> raw = v instanceof JavaType.Annotation.ArrayElementValue array ? array.getValues() : List.of(v.getValue());
                    return raw.stream().map(o -> o instanceof JavaType.Variable constant ? constant.getName() : String.valueOf(o)).toList();
                }
            }
        }
        return List.of();
    }

    /** Supertypes of {@code c} that are not source (library jars, generated sources), in Spring's search order. */
    private static List<JavaType.FullyQualified> outsideSource(ClassInfo c, CodeIndex index) {
        List<JavaType.FullyQualified> out = new ArrayList<>();
        outsideSource(c.type(), index, new HashSet<>(Set.of(c.fqn(), "java.lang.Object")), out);
        return out;
    }

    private static void outsideSource(JavaType.FullyQualified t, CodeIndex index, Set<String> seen, List<JavaType.FullyQualified> out) {
        if (t == null) {
            return;
        }
        List<JavaType.FullyQualified> parents = new ArrayList<>(t.getInterfaces());
        if (t.getSupertype() != null) {
            parents.add(t.getSupertype());
        }
        for (JavaType.FullyQualified parent : parents) {
            if (seen.add(parent.getFullyQualifiedName())) {
                if (index.clazz(parent.getFullyQualifiedName()) == null) {
                    out.add(parent);
                }
                outsideSource(parent, index, seen, out);
            }
        }
    }

    /** The declarations {@code m} overrides in supertypes outside the sources (#51: a mapped interface from a jar). */
    private static List<JavaType.Method> overriddenOutsideSource(MethodInfo m, CodeIndex index) {
        if (m.type().hasFlags(Flag.Static) || m.type().hasFlags(Flag.Private)) {
            return List.of();
        }
        List<JavaType.Method> out = new ArrayList<>();
        for (JavaType.FullyQualified parent : outsideSource(m.owner(), index)) {
            for (JavaType.Method o : parent.getMethods()) {
                if (o.getName().equals(m.name()) && sameParameters(o, m.type())) {
                    out.add(o);
                }
            }
        }
        return out;
    }

    private static boolean sameParameters(JavaType.Method declared, JavaType.Method override) {
        List<JavaType> a = declared.getParameterTypes();
        List<JavaType> b = override.getParameterTypes();
        if (a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            if (!(a.get(i) instanceof JavaType.GenericTypeVariable) && !Types.erasure(a.get(i)).equals(Types.erasure(b.get(i)))) {
                return false;
            }
        }
        return true;
    }

    private Entrypoint messaging(MethodInfo m, J.Annotation a, Kind kind) {
        String label = switch (kind) {
            case KAFKA -> "@KafkaListener " + Annotations.stringAttribute(a, "topics", "value").orElse(m.label());
            case JMS -> "@JmsListener " + Annotations.stringAttribute(a, "destination").orElse(m.label());
            case RABBIT -> "@RabbitListener " + Annotations.stringAttribute(a, "queues", "value").orElse(m.label());
            case SQS -> "@SqsListener " + Annotations.stringAttribute(a, "value", "queueNames").orElse(m.label());
            case INTEGRATION -> "@ServiceActivator " + Annotations.stringAttribute(a, "inputChannel").orElse(m.label());
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
            case INTEGRATION -> integrationObserved(m);
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

    /**
     * Spring Integration observes a message handler only when its component name matches one of the observation patterns
     * (Spring Integration reference, Metrics and Management › Micrometer Observation: "By default, none of the
     * IntegrationManagement components are instrumented"; Boot's IntegrationAutoConfiguration passes
     * spring.integration.management.observation-patterns to {@code @EnableIntegrationManagement}). An annotated service
     * activator's endpoint is named {@code <bean>.<method>.serviceActivator}, its handler {@code <endpoint>.handler}.
     */
    private boolean integrationObserved(MethodInfo m) {
        String simple = m.owner().simpleName();
        String bean = m.owner().decl().getLeadingAnnotations().stream()
                .filter(a -> Annotations.is(a, "org.springframework.stereotype.Component")
                        || Annotations.is(a, "org.springframework.stereotype.Service"))
                .map(a -> Annotations.stringAttribute(a, "value").orElse(null)).filter(Objects::nonNull).findFirst()
                .orElse(Character.toLowerCase(simple.charAt(0)) + simple.substring(1));
        String endpoint = bean + "." + m.name() + ".serviceActivator";
        return smartMatch(endpoint, platform.integrationPatterns()) || smartMatch(endpoint + ".handler", platform.integrationPatterns());
    }

    /**
     * Spring Integration's PatternMatchUtils.smartMatch: case-insensitive, the first pattern that matches decides, a
     * leading {@code !} negates, {@code *} matches anything.
     */
    static boolean smartMatch(String name, List<String> patterns) {
        for (String p : patterns) {
            boolean negate = p.startsWith("!");
            String pattern = negate || p.startsWith("\\") ? p.substring(1) : p;
            StringBuilder re = new StringBuilder();
            String[] parts = pattern.toLowerCase(Locale.ROOT).split("\\*", -1);
            for (int i = 0; i < parts.length; i++) {
                re.append(i == 0 ? "" : ".*").append(java.util.regex.Pattern.quote(parts[i]));
            }
            if (name.toLowerCase(Locale.ROOT).matches(re.toString())) {
                return !negate;
            }
        }
        return false;
    }

    private static final String REDIS_LISTENER = "org.springframework.data.redis.connection.MessageListener";
    static final String REDIS_ADAPTER = "org.springframework.data.redis.listener.adapter.MessageListenerAdapter";

    /**
     * A Spring Data Redis pub/sub listener class ({@code MessageListener.onMessage(Message, byte[])}).
     * RedisMessageListenerContainer calls it on its task executor without an observation (Spring Data Redis reference,
     * Observability: Lettuce observes Redis commands; the container source has no observation), so it is not observed.
     * MessageListenerAdapter is a MessageListener too: its delegates come from {@link FunctionalEntrypoints#redisAdapters}.
     */
    private static Entrypoint redisListener(MethodInfo m, ClassInfo owner) {
        if (owner.type() == null || m.type().hasFlags(Flag.Static) || !m.name().equals("onMessage")
                || m.type().getParameterTypes().size() != 2 || !TypeUtils.isAssignableTo(REDIS_LISTENER, owner.type())
                || TypeUtils.isAssignableTo(REDIS_ADAPTER, owner.type())) {
            return null;
        }
        return new Entrypoint(m, Kind.REDIS, "redis " + m.label(), false);
    }

    /**
     * A gRPC service method: a public method of a {@code BindableService} (the generated {@code *ImplBase}) that answers
     * through its last parameter, a {@code StreamObserver}, and returns nothing (unary and server streaming). Client and
     * bidirectional streaming methods return the request observer: their work runs in its callbacks, not in the method,
     * so they are not seen. Spring gRPC observes every call unless switched off ({@link Platform#grpcObserved}).
     */
    private Entrypoint grpcService(MethodInfo m, ClassInfo owner) {
        if (owner.type() == null || m.type().hasFlags(Flag.Static) || !m.type().hasFlags(Flag.Public)
                || m.type().getParameterTypes().isEmpty()
                || m.type().getReturnType() != org.openrewrite.java.tree.JavaType.Primitive.Void
                || !TypeUtils.isAssignableTo("io.grpc.BindableService", owner.type())
                || !TypeUtils.isAssignableTo("io.grpc.stub.StreamObserver", m.type().getParameterTypes().getLast())) {
            return null;
        }
        return new Entrypoint(m, Kind.GRPC, "grpc " + m.label(), platform.grpcObserved());
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

    /** One entrypoint per handler; each class-level path times each method path is a label (#51: path arrays). */
    private static Entrypoint httpEntrypoint(MethodInfo m, Mapping mapping, CodeIndex index) {
        List<String> labels = new ArrayList<>();
        for (String prefix : orEmpty(prefixes(m.owner(), index))) {
            for (String path : orEmpty(mapping.paths())) {
                String joined = ("/" + prefix + "/" + path).replaceAll("/+", "/");
                if (joined.length() > 1 && joined.endsWith("/")) {
                    joined = joined.substring(0, joined.length() - 1);
                }
                if (!labels.contains(mapping.verb() + " " + joined)) {
                    labels.add(mapping.verb() + " " + joined);
                }
            }
        }
        return new Entrypoint(m, Kind.HTTP, labels.getFirst(), true, List.copyOf(labels.subList(1, labels.size())));
    }

    private static List<String> orEmpty(List<String> paths) {
        return paths.isEmpty() ? List.of("") : paths;
    }

    /**
     * The class-level mapping: on the controller (also through a meta-annotation), else on its nearest source
     * supertype that has one, else on a supertype outside the sources.
     */
    private static List<String> prefixes(ClassInfo owner, CodeIndex index) {
        List<ClassInfo> types = new ArrayList<>();
        types.add(owner);
        types.addAll(index.sourceSupertypes(owner));
        for (ClassInfo t : types) {
            for (J.Annotation a : t.decl().getLeadingAnnotations()) {
                Mapping mapping = mapping(a, index);
                if (mapping != null) {
                    return mapping.paths();
                }
            }
        }
        for (JavaType.FullyQualified t : outsideSource(owner, index)) {
            for (JavaType.FullyQualified a : t.getAnnotations()) {
                Mapping mapping = mapping(a, 0);
                if (mapping != null) {
                    return mapping.paths();
                }
            }
        }
        return List.of();
    }

    /** Every {@code value}/{@code path} of a mapping: literals, source constants or concatenations of them (#51). */
    private static List<String> paths(J.Annotation a, CodeIndex index) {
        return Annotations.attribute(a, "value", "path")
                .map(e -> e instanceof J.NewArray na ? na.getInitializer() == null ? List.<Expression>of() : na.getInitializer() : List.of(e))
                .orElse(List.of()).stream()
                .filter(e -> !(e instanceof J.Empty))
                .map(e -> Constants.concatenated(index, e)).filter(Objects::nonNull).toList();
    }
}
