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
            case EVENT -> "@" + a.getSimpleName() + " " + m.label() + firstParamType(m);
            default -> "@" + a.getSimpleName() + " " + m.label();
        };
        boolean observed = switch (kind) {
            case SCHEDULED -> true;
            // synchronous listeners run in the publisher's thread, inside its span; @Async ones get the
            // caller's context through the TaskDecorator (logging-support), so neither starts a new trace
            case EVENT -> true;
            case KAFKA -> kafkaObserved;
            default -> false;
        };
        return new Entrypoint(m, kind, label, observed);
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
