package dev.logast.rules;

import dev.logast.graph.CallGraph;
import dev.logast.model.Annotations;
import dev.logast.model.ClassInfo;
import dev.logast.model.Entrypoint;
import dev.logast.model.MethodInfo;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.Statement;
import org.openrewrite.java.tree.TypeUtils;

import java.util.*;
import java.util.stream.Stream;

/**
 * {@code resilience} pack, where retries are missing or stacked (#105).
 * <ul>
 *   <li>RETRY_RECOMMENDED (report, low): a method making only idempotent outgoing HTTP calls (GET / HEAD / PUT / DELETE /
 *       OPTIONS through RestClient, RestTemplate, WebClient, an HTTP interface or an OpenFeign client), reached from an
 *       entrypoint along a path on which nothing retries: no {@code @Retryable} (Spring Framework 7 / Spring Retry),
 *       Resilience4j {@code @Retry}, {@code RetryTemplate} / {@code RetryOperations}, Spring Cloud CircuitBreaker,
 *       {@code retryWhen} / {@code retry} on the Mono/Flux, Feign {@code Retryer} bean, and no Kafka / RabbitMQ
 *       redelivery around it. POST / PATCH / an unknown method are never reported (a retry may duplicate the side
 *       effect). The wording follows the caller: an HTTP request gets at most one quick retry within its own timeout, a
 *       job or consumer a retry with backoff.</li>
 *   <li>NESTED_RETRY (report, medium): a retried method that calls (on the same thread, through another bean, up to 5
 *       calls deep) another retried method or a client with its own retry: the attempts multiply.</li>
 * </ul>
 */
final class RetryCoverageRules {
    private enum Http { NONE, IDEMPOTENT, UNSAFE }

    private static final String RESILIENCE4J_RETRY = "io.github.resilience4j.retry.annotation.Retry";
    /** Programmatic retry: calling into one of these retries what it runs. */
    private static final List<String> RETRY_TYPES = List.of("org.springframework.retry.RetryOperations",
            "org.springframework.core.retry.RetryOperations", "io.github.resilience4j.retry.Retry",
            "org.springframework.cloud.client.circuitbreaker.CircuitBreaker",
            "org.springframework.cloud.client.circuitbreaker.ReactiveCircuitBreaker");
    private static final List<String> REACTOR = List.of("reactor.core.publisher.Mono", "reactor.core.publisher.Flux");
    private static final String REST_CLIENT = "org.springframework.web.client.RestClient";
    private static final String WEB_CLIENT = "org.springframework.web.reactive.function.client.WebClient";
    private static final String REST_OPERATIONS = "org.springframework.web.client.RestOperations";
    private static final Set<String> IDEMPOTENT = Set.of("GET", "HEAD", "PUT", "DELETE", "OPTIONS");
    private static final Set<String> IDEMPOTENT_TEMPLATE = Set.of("getForObject", "getForEntity", "headForHeaders", "put",
            "delete", "optionsForAllow");
    private static final Set<String> UNSAFE_TEMPLATE = Set.of("postForObject", "postForEntity", "postForLocation", "patchForObject");
    /** Entrypoints whose framework redelivers a failed message (Kafka's DefaultErrorHandler, RabbitMQ's requeue). */
    private static final Set<Entrypoint.Kind> REDELIVERED = EnumSet.of(Entrypoint.Kind.KAFKA, Entrypoint.Kind.RABBIT);
    private static final Set<Entrypoint.Kind> USER_FACING = EnumSet.of(Entrypoint.Kind.HTTP, Entrypoint.Kind.WEBSOCKET,
            Entrypoint.Kind.GRPC);

    /** What one method body does. */
    private record Body(Http http, boolean clientRetry) {
    }

    private final RuleContext ctx;
    private final Map<String, Body> bodies = new HashMap<>();
    private final boolean feignRetryer;

    RetryCoverageRules(RuleContext ctx) {
        this.ctx = ctx;
        this.feignRetryer = ctx.a.index.classes().stream().anyMatch(c -> c.file().text().contains("feign.Retryer"));
    }

    void run() {
        if (ctx.enabled(Rules.RETRY_RECOMMENDED)) {
            recommended();
        }
        if (ctx.enabled(Rules.NESTED_RETRY)) {
            nested();
        }
    }

    // ---- RETRY_RECOMMENDED --------------------------------------------------------------------------------------

    private void recommended() {
        Map<String, List<Entrypoint>> entrypoints = new HashMap<>();
        for (Entrypoint ep : ctx.a.entrypoints) {
            entrypoints.computeIfAbsent(ep.method().key(), k -> new ArrayList<>()).add(ep);
        }
        for (MethodInfo m : ctx.methods()) {
            if (m.decl().getBody() == null || body(m).http() != Http.IDEMPOTENT || retried(m)
                    || ctx.ignored(m.file(), m.line(), Rules.RETRY_RECOMMENDED)) {
                continue;
            }
            List<Entrypoint> callers = unretriedCallers(m, entrypoints);
            if (callers.isEmpty()) {
                continue;
            }
            boolean request = callers.stream().anyMatch(ep -> USER_FACING.contains(ep.kind()));
            String from = String.join(", ", callers.stream().map(ep -> ep.label() + " (" + kind(ep) + ")").distinct().limit(3).toList())
                    + (callers.size() > 3 ? " and " + (callers.size() - 3) + " more" : "");
            String annotation = "@Retryable(maxRetriesString = \"${resilience.retry.defaults.max-retries}\", "
                    + "delayString = \"${resilience.retry.defaults.delay}\")";
            ctx.order(Rules.RETRY_RECOMMENDED, m, m.line(), "retry-recommended")
                    .title(m.label() + " makes an idempotent HTTP call that nothing retries, reached from " + from + ": "
                            + (request ? "one dropped connection or 503 fails the request"
                            : "one dropped connection or 503 fails the run"))
                    .suggestion(request
                            ? "Retry it at most once, with a short backoff, inside the caller's timeout: @Retryable(maxRetries = 1, "
                            + "delay = 100) on " + m.label() + " (Spring Framework 7, needs @EnableResilientMethods; the next apply "
                            + "moves the settings into application.yml, under resilience.retry.usages when they differ from "
                            + "resilience.retry.defaults), or retryWhen(Retry.backoff(1, Duration.ofMillis(100))) on a WebClient. "
                            + "Skip it when a service mesh or gateway already retries."
                            : "Retry it with backoff: " + annotation + " on " + m.label() + " (Spring Framework 7, needs "
                            + "@EnableResilientMethods), with the settings shared in application.yml under resilience.retry.defaults "
                            + "(for example 3 retries, starting at one second, multiplier 2), or retryWhen(Retry.backoff(..)) on a WebClient.")
                    .basis("only idempotent calls (GET / HEAD / PUT / DELETE / OPTIONS); no @Retryable, Resilience4j @Retry, "
                            + "RetryTemplate, retryWhen or Spring Cloud CircuitBreaker on the path, no Kafka / RabbitMQ redelivery")
                    .doneWhen("the method, or a method on every path to it from an entrypoint, retries (@Retryable, Resilience4j "
                            + "@Retry, RetryTemplate, retryWhen, Spring Cloud CircuitBreaker), or it makes a non-idempotent call");
        }
    }

    private static String kind(Entrypoint ep) {
        return switch (ep.kind()) {
            case HTTP, WEBSOCKET, GRPC -> "request";
            case SCHEDULED -> "scheduled job";
            case BATCH, STEP, ITEM, MAIN -> "batch";
            default -> "consumer";
        };
    }

    /** Entrypoints that reach {@code m} along a path without a retrying method or redelivery. */
    private List<Entrypoint> unretriedCallers(MethodInfo m, Map<String, List<Entrypoint>> entrypoints) {
        List<Entrypoint> out = new ArrayList<>();
        Deque<MethodInfo> todo = new ArrayDeque<>(List.of(m));
        Set<String> seen = new HashSet<>();
        while (!todo.isEmpty()) {
            MethodInfo cur = todo.poll();
            if (!seen.add(cur.key())) {
                continue;
            }
            for (Entrypoint ep : entrypoints.getOrDefault(cur.key(), List.of())) {
                if (!REDELIVERED.contains(ep.kind())) {
                    out.add(ep);
                }
            }
            for (CallGraph.Edge e : ctx.a.graph.in(cur)) {
                if (!retried(e.from())) {
                    todo.add(e.from());
                }
            }
        }
        out.sort(Comparator.comparing(Entrypoint::label));
        return out;
    }

    // ---- NESTED_RETRY -------------------------------------------------------------------------------------------

    private void nested() {
        for (MethodInfo m : ctx.methods()) {
            if (m.decl().getBody() == null || !annotatedRetry(m) || ctx.ignored(m.file(), m.line(), Rules.NESTED_RETRY)) {
                continue;
            }
            List<String> path = innerRetry(m);
            if (path == null) {
                continue;
            }
            ctx.order(Rules.NESTED_RETRY, m, m.line(), "nested-retry")
                    .title(m.label() + " retries and calls " + String.join(" → ", path.subList(1, path.size()))
                            + ", which retries again: every outer attempt runs all inner attempts, so the attempts multiply "
                            + "(3 × 3 = 9 calls to the remote side) and the wait grows with them")
                    .suggestion("Retry at one layer only: keep the retry where the whole unit of work is repeated (or at the "
                            + "client), and remove the other one; if both stay, size them together so the product stays "
                            + "within the caller's timeout.")
                    .doneWhen("no retried method or client with its own retry is reachable from it on the same thread "
                            + "(up to 5 calls deep)");
        }
    }

    /** The path (labels, {@code root} first) to the first retry below {@code root}; null when there is none. */
    private List<String> innerRetry(MethodInfo root) {
        Deque<List<MethodInfo>> todo = new ArrayDeque<>(List.of(List.of(root)));
        Set<String> seen = new HashSet<>();
        while (!todo.isEmpty()) {
            List<MethodInfo> path = todo.poll();
            MethodInfo cur = path.getLast();
            if (!seen.add(cur.key()) || path.size() > 6) {
                continue;
            }
            for (CallGraph.Edge e : ctx.a.graph.out(cur)) {
                MethodInfo to = e.to();
                if (e.async() != CallGraph.Async.NONE || to.key().equals(root.key())) {
                    continue;
                }
                List<MethodInfo> next = new ArrayList<>(path);
                next.add(to);
                // an annotation retries only through the proxy: a call from another class
                boolean proxied = !to.owner().fqn().equals(e.from().owner().fqn());
                if (proxied && annotatedRetry(to) || to.decl().getBody() != null && body(to).clientRetry()) {
                    return next.stream().map(MethodInfo::label).toList();
                }
                todo.add(next);
            }
        }
        return null;
    }

    // ---- what a method does -------------------------------------------------------------------------------------

    /** Retries what it runs: a retry annotation, or programmatic / client retry in its body. */
    private boolean retried(MethodInfo m) {
        return annotatedRetry(m) || m.decl().getBody() != null && body(m).clientRetry();
    }

    private static boolean annotatedRetry(MethodInfo m) {
        return Stream.concat(m.decl().getLeadingAnnotations().stream(), m.owner().decl().getLeadingAnnotations().stream())
                .anyMatch(a -> Annotations.is(a, RESILIENCE4J_RETRY) || Stream.of(ResilienceRules.RESILIENCE_PKG + "Retryable",
                                ResilienceRules.SPRING_RETRY_PKG + "Retryable", ResilienceRules.SPRING_RETRY_PKG + "CircuitBreaker")
                        .anyMatch(f -> f.equals(ResilienceRules.annotationFqn(a, m.file()))));
    }

    private Body body(MethodInfo m) {
        return bodies.computeIfAbsent(m.key(), k -> scan(m));
    }

    private Body scan(MethodInfo m) {
        Http[] http = {Http.NONE};
        boolean[] retry = {false};
        new JavaIsoVisitor<Integer>() {
            @Override
            public J.MethodInvocation visitMethodInvocation(J.MethodInvocation mi, Integer p) {
                JavaType.Method t = mi.getMethodType();
                if (t != null) {
                    Http h = http(mi, t);
                    if (h == Http.UNSAFE || h == Http.IDEMPOTENT && http[0] == Http.NONE) {
                        http[0] = h;
                    }
                    JavaType.FullyQualified owner = t.getDeclaringType();
                    if (RETRY_TYPES.stream().anyMatch(r -> TypeUtils.isAssignableTo(r, owner))
                            || REACTOR.contains(owner.getFullyQualifiedName()) && t.getName().startsWith("retry")) {
                        retry[0] = true;
                    }
                }
                return super.visitMethodInvocation(mi, p);
            }
        }.visit(m.decl().getBody(), 0);
        return new Body(http[0], retry[0]);
    }

    /** The HTTP method of an outgoing call, by client API. */
    private Http http(J.MethodInvocation mi, JavaType.Method t) {
        String owner = t.getDeclaringType().getFullyQualifiedName();
        String name = t.getName();
        if (owner.equals(REST_CLIENT) || owner.equals(WEB_CLIENT)) {
            return switch (name) {
                case "get", "head", "put", "delete", "options" -> Http.IDEMPOTENT;
                case "post", "patch" -> Http.UNSAFE;
                case "method" -> verb(mi.getArguments());
                default -> Http.NONE;
            };
        }
        if (TypeUtils.isAssignableTo(REST_OPERATIONS, t.getDeclaringType())) {
            if (IDEMPOTENT_TEMPLATE.contains(name)) {
                return Http.IDEMPOTENT;
            }
            if (UNSAFE_TEMPLATE.contains(name)) {
                return Http.UNSAFE;
            }
            return name.equals("exchange") || name.equals("execute") ? verb(mi.getArguments()) : Http.NONE;
        }
        ClassInfo iface = ctx.a.index.clazz(owner);
        if (iface == null || iface.decl().getKind() != J.ClassDeclaration.Kind.Type.Interface) {
            return Http.NONE;
        }
        for (Statement s : iface.decl().getBody().getStatements()) {
            if (s instanceof J.MethodDeclaration md && md.getSimpleName().equals(name)) {
                return interfaceVerb(md, iface);
            }
        }
        return Http.NONE;
    }

    /** An HttpMethod argument (HttpMethod.GET); unknown counts as unsafe. */
    private static Http verb(List<Expression> args) {
        for (Expression a : args) {
            String text = a.printTrimmed();
            if (text.matches("(HttpMethod\\.)?[A-Z]+") && (text.startsWith("HttpMethod.") || IDEMPOTENT.contains(text)
                    || Set.of("POST", "PATCH").contains(text))) {
                return IDEMPOTENT.contains(text.substring(text.lastIndexOf('.') + 1)) ? Http.IDEMPOTENT : Http.UNSAFE;
            }
        }
        return Http.UNSAFE;
    }

    /** An HTTP interface method (@GetExchange, @HttpExchange(method = ..)) or a Feign client method (@GetMapping, ..). */
    private Http interfaceVerb(J.MethodDeclaration md, ClassInfo iface) {
        boolean feign = iface.decl().getLeadingAnnotations().stream()
                .anyMatch(a -> Annotations.is(a, "org.springframework.cloud.openfeign.FeignClient"));
        if (feign && feignRetryer) {
            return Http.NONE;   // the client retries itself
        }
        for (J.Annotation a : md.getLeadingAnnotations()) {
            String n = a.getSimpleName();
            if (Set.of("GetExchange", "PutExchange", "DeleteExchange").contains(n)
                    || feign && Set.of("GetMapping", "PutMapping", "DeleteMapping").contains(n)) {
                return Http.IDEMPOTENT;
            }
            if (Set.of("PostExchange", "PatchExchange").contains(n) || feign && Set.of("PostMapping", "PatchMapping").contains(n)) {
                return Http.UNSAFE;
            }
            if (n.equals("HttpExchange") || feign && n.equals("RequestMapping")) {
                String m = Annotations.attribute(a, "method").map(Expression::printTrimmed).orElse("");
                String verb = m.replaceAll("[\"{}\\s]", "");
                verb = verb.substring(verb.lastIndexOf('.') + 1);
                return IDEMPOTENT.contains(verb) ? Http.IDEMPOTENT : Http.UNSAFE;
            }
        }
        return Http.NONE;
    }
}
