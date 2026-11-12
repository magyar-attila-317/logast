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
    }

    private final Policy policy;
    private final boolean kafkaObserved;

    public EntrypointFinder(Policy policy) {
        this(policy, policy.entrypoints().kafkaObservationEnabled());
    }

    /** @param kafkaObserved listener observation is on (policy or spring.kafka.listener.observation-enabled) */
    public EntrypointFinder(Policy policy, boolean kafkaObserved) {
        this.policy = policy;
        this.kafkaObserved = kafkaObserved;
    }

    public List<Entrypoint> find(CodeIndex index) {
        List<Entrypoint> out = new ArrayList<>();
        for (MethodInfo m : index.methods()) {
            Entrypoint ep = classify(m);
            if (ep != null) {
                out.add(ep);
            }
        }
        out.sort(Comparator.comparing(Entrypoint::label));
        return out;
    }

    private Entrypoint classify(MethodInfo m) {
        if (!m.hasBody()) {
            return null;
        }
        for (J.Annotation a : m.decl().getLeadingAnnotations()) {
            for (var e : HTTP_VERBS.entrySet()) {
                if (Annotations.is(a, e.getKey())) {
                    return new Entrypoint(m, Kind.HTTP, httpLabel(m, a, e.getValue()), true);
                }
            }
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

    private Entrypoint messaging(MethodInfo m, J.Annotation a, Kind kind) {
        String label = switch (kind) {
            case KAFKA -> "@KafkaListener " + Annotations.stringAttribute(a, "topics", "value").orElse(m.label());
            case JMS -> "@JmsListener " + Annotations.stringAttribute(a, "destination").orElse(m.label());
            case RABBIT -> "@RabbitListener " + Annotations.stringAttribute(a, "queues", "value").orElse(m.label());
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
            case KAFKA -> kafkaObserved;
            default -> false;
        };
        return new Entrypoint(m, kind, label, observed);
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

    private static String httpLabel(MethodInfo m, J.Annotation a, String verb) {
        if (verb.equals("ANY")) {
            verb = Annotations.rawAttribute(a, "method")
                    .map(s -> s.substring(s.lastIndexOf('.') + 1).replaceAll("[^A-Z]", ""))
                    .filter(s -> !s.isEmpty()).orElse("ANY");
        }
        String prefix = m.owner().decl().getLeadingAnnotations().stream()
                .filter(c -> Annotations.is(c, WEB + "RequestMapping"))
                .findFirst().flatMap(c -> Annotations.stringAttribute(c, "value", "path")).orElse("");
        String path = Annotations.stringAttribute(a, "value", "path").orElse("");
        String joined = ("/" + prefix + "/" + path).replaceAll("/+", "/");
        if (joined.length() > 1 && joined.endsWith("/")) {
            joined = joined.substring(0, joined.length() - 1);
        }
        return verb + " " + joined;
    }
}
