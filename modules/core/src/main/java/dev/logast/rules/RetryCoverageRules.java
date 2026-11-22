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
        if (ctx.enabled(Rules.RETRY_BYPASSED)) {
            Set<String> keys = new HashSet<>();
            ctx.a.entrypoints.forEach(ep -> keys.add(ep.method().key()));
            bypassed(keys);
        }
    }

    // ---- RETRY_RECOMMENDED --------------------------------------------------------------------------------------

    private void recommended() {
        Map<String, List<Entrypoint>> entrypoints = new HashMap<>();
        for (Entrypoint ep : ctx.a.entrypoints) {
            entrypoints.computeIfAbsent(ep.method().key(), k -> new ArrayList<>()).add(ep);
        }
        // #109: the answers in retry.yaml: yes adds the retry, no stops the recommendation
        Map<String, String> answers = RetrySettingsRules.candidateMarks(ctx.a.project.root());
        boolean retryable = ctx.a.project.probe().hasClass(ResilienceRules.RESILIENCE_PKG + "Retryable");
        Set<String> placed = new HashSet<>();
        for (MethodInfo m : ctx.methods()) {
            if (m.decl().getBody() == null || body(m).http() != Http.IDEMPOTENT || retried(m)
                    || ctx.ignored(m.file(), m.line(), Rules.RETRY_RECOMMENDED) || "no".equals(answers.get(m.label()))) {
                continue;
            }
            List<Entrypoint> callers = unretriedCallers(m, entrypoints);
            if (callers.isEmpty()) {
                continue;
            }
            boolean request = callers.stream().anyMatch(ep -> ep.kind().is(Entrypoint.Trait.USER_FACING));
            String from = String.join(", ", callers.stream().map(ep -> ep.label() + " (" + kind(ep) + ")").distinct().limit(3).toList())
                    + (callers.size() > 3 ? " and " + (callers.size() - 3) + " more" : "");
            // a retry annotation works only through the Spring proxy: where it can go (#109, self-invocation)
            List<MethodInfo> targets = retryTargets(m, entrypoints.keySet());
            if (targets.isEmpty()) {
                ctx.order(Rules.RETRY_RECOMMENDED, m, m.line(), "retry-recommended")
                        .data("method", m.label()).data("caller", request ? "request" : "job").data("from", from)
                        .data("annotatable", "no")
                        .title(m.label() + " makes an idempotent HTTP call that nothing retries, reached from " + from
                                + ", and a @Retryable could not work on the way: " + whyNot(m))
                        .suggestion("Retry the call in code instead: a RetryTemplate (Spring Framework 7's "
                                + "org.springframework.core.retry.RetryTemplate) around it, or retryWhen(..) on a WebClient; or "
                                + "move it into a bean other beans call, where @Retryable works.")
                        .basis("Spring AOP proxies skip self-invocation and cannot advise private, static or final methods "
                                + "(Spring Framework reference, Understanding AOP Proxies)")
                        .doneWhen("the call is retried in code, or reached through a method other beans call");
                continue;
            }
            for (MethodInfo t : targets) {
                if (!placed.add(t.key()) || t != m && (retried(t) || "no".equals(answers.get(t.label()))
                        || ctx.ignored(t.file(), t.line(), Rules.RETRY_RECOMMENDED))) {
                    continue;
                }
                candidate(t, m, request, from, answers, retryable);
            }
        }
    }

    /**
     * The order for one place a retry can go: {@code t} itself makes the call ({@code t == m}), or reaches {@code m}
     * through calls on itself, which the proxy does not see.
     */
    private void candidate(MethodInfo t, MethodInfo m, boolean request, String from, Map<String, String> answers, boolean retryable) {
        String via = t == m ? "" : " (through " + m.label() + ", which it calls on itself: the Spring proxy skips that call, "
                + "so the retry goes here)";
        String annotation = "@Retryable(maxRetriesString = \"${resilience.retry.defaults.max-retries}\", "
                + "delayString = \"${resilience.retry.defaults.delay}\")";
        OrderBuilder b = ctx.order(Rules.RETRY_RECOMMENDED, t, t.line(), "retry-recommended")
                .data("method", t.label()).data("caller", request ? "request" : "job").data("from", from);
        if (t != m) {
            b.data("via", m.label());
        }
        if ("yes".equals(answers.get(t.label())) && retryable) {
            // retry: yes: Spring Framework 7's @Retryable with the recommended values; the next round enables it
            // (RETRY_INACTIVE) and moves the values into application.yml (RETRY_SETTINGS_HARDCODED)
            String added = request ? "@Retryable(maxRetries = 1, delay = 100)" : "@Retryable(maxRetries = 3, delay = 1000, multiplier = 2)";
            int at = t.decl().getLeadingAnnotations().isEmpty() ? dev.logast.parse.Pos.start(t.decl())
                    : dev.logast.parse.Pos.start(t.decl().getLeadingAnnotations().getFirst());
            b.title("Adds " + added + " to " + t.label() + " (retry: yes in " + RetrySettingsRules.WORK_FILE + "): its "
                            + "idempotent HTTP call is reached from " + from + via)
                    .suggestion("Answered yes in " + RetrySettingsRules.WORK_FILE + ". The next round enables @Retryable where "
                            + "needed and moves the values into application.yml.")
                    .edits(JavaEdits.imports(t.file(), ResilienceRules.RESILIENCE_PKG + "Retryable"))
                    .edit(dev.logast.orders.Edit.insert(t.file().relPath(), at,
                            added + "\n" + Text.indentAt(t.file().text(), at)));
            return;
        }
        String decide = "Decide in " + RetrySettingsRules.WORK_FILE + " (candidates: " + t.label() + ", retry: yes or no)"
                + (retryable ? "; yes adds it on the next apply. " : "; Spring Framework 7's @Retryable is not on the "
                + "classpath, so add the retry yourself. ");
        b.title((t == m ? m.label() : t.label() + " calls " + m.label() + ", which") + " makes an idempotent HTTP call that "
                        + "nothing retries, reached from " + from + via + ": "
                        + (request ? "one dropped connection or 503 fails the request"
                        : "one dropped connection or 503 fails the run"))
                .suggestion(decide + (request
                        ? "Retry it at most once, with a short backoff, inside the caller's timeout: @Retryable(maxRetries = 1, "
                        + "delay = 100) on " + t.label() + " (Spring Framework 7, needs @EnableResilientMethods; the next apply "
                        + "moves the settings into application.yml, under resilience.retry.usages when they differ from "
                        + "resilience.retry.defaults), or retryWhen(Retry.backoff(1, Duration.ofMillis(100))) on a WebClient. "
                        + "Skip it when a service mesh or gateway already retries."
                        : "Retry it with backoff: " + annotation + " on " + t.label() + " (Spring Framework 7, needs "
                        + "@EnableResilientMethods), with the settings shared in application.yml under resilience.retry.defaults "
                        + "(for example 3 retries, starting at one second, multiplier 2), or retryWhen(Retry.backoff(..)) on a WebClient."))
                .basis("only idempotent calls (GET / HEAD / PUT / DELETE / OPTIONS); no @Retryable, Resilience4j @Retry, "
                        + "RetryTemplate, retryWhen or Spring Cloud CircuitBreaker on the path, no Kafka / RabbitMQ redelivery")
                .doneWhen("the method, or a method on every path to it from an entrypoint, retries (@Retryable, Resilience4j "
                        + "@Retry, RetryTemplate, retryWhen, Spring Cloud CircuitBreaker), or it makes a non-idempotent call");
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
                if (!ep.kind().is(Entrypoint.Trait.REDELIVERED)) {
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

    /** A proxy-based retry annotation can take effect on {@code m}: not private, static or final, its class not final. */
    static boolean proxyable(MethodInfo m) {
        return m.decl().getModifiers().stream().noneMatch(x -> x.getType() == J.Modifier.Type.Private
                || x.getType() == J.Modifier.Type.Static || x.getType() == J.Modifier.Type.Final)
                && m.owner().decl().getModifiers().stream().noneMatch(x -> x.getType() == J.Modifier.Type.Final);
    }

    /** {@code m} is called through the proxy: by the framework (an entrypoint) or from another class. */
    private boolean calledThroughProxy(MethodInfo m, Set<String> entrypointKeys) {
        return entrypointKeys.contains(m.key())
                || ctx.a.graph.in(m).stream().anyMatch(e -> !e.from().owner().fqn().equals(m.owner().fqn()));
    }

    /**
     * Where a retry annotation for {@code m} takes effect (Spring AOP: self-invocation bypasses the proxy, private /
     * static / final methods cannot be advised; Spring Framework reference, "Understanding AOP Proxies"): {@code m}
     * itself when the proxy sees its calls, else the methods of its class that reach it through calls on itself and
     * that other beans (or the framework) call. Empty: no such method.
     */
    private List<MethodInfo> retryTargets(MethodInfo m, Set<String> entrypointKeys) {
        List<MethodInfo> out = new ArrayList<>();
        Deque<MethodInfo> todo = new ArrayDeque<>(List.of(m));
        Set<String> seen = new HashSet<>();
        while (!todo.isEmpty()) {
            MethodInfo cur = todo.poll();
            if (!seen.add(cur.key())) {
                continue;
            }
            if (proxyable(cur) && calledThroughProxy(cur, entrypointKeys)) {
                out.add(cur);
                continue;
            }
            for (CallGraph.Edge e : ctx.a.graph.in(cur)) {
                if (e.from().owner().fqn().equals(cur.owner().fqn()) && e.async() == CallGraph.Async.NONE) {
                    todo.add(e.from());
                }
            }
        }
        out.sort(Comparator.comparing(MethodInfo::label));
        return out;
    }

    /** Why a retry annotation on {@code m} would do nothing. */
    private String whyNot(MethodInfo m) {
        List<String> mods = m.decl().getModifiers().stream().map(x -> x.getType()).filter(t -> t == J.Modifier.Type.Private
                || t == J.Modifier.Type.Static || t == J.Modifier.Type.Final).map(t -> t.name().toLowerCase(Locale.ROOT)).toList();
        return !mods.isEmpty() ? "it is " + String.join(" ", mods) + " and only called from its own class"
                : "it is only called from its own class (self-invocation skips the Spring proxy)";
    }

    // ---- RETRY_BYPASSED -------------------------------------------------------------------------------------------

    /**
     * RETRY_BYPASSED (report, high): a {@code @Retryable} (Spring Framework 7 or Spring Retry) or Resilience4j
     * {@code @Retry} that never takes effect: on a private / static / final method, or on a method only its own class
     * calls (self-invocation bypasses the proxy). The method runs once; its first failure is final. Methods nobody calls
     * in the analysed code (called from elsewhere, by reflection) are left alone.
     */
    private void bypassed(Set<String> entrypointKeys) {
        for (MethodInfo m : ctx.methods()) {
            boolean annotated = m.decl().getLeadingAnnotations().stream().anyMatch(a -> Annotations.is(a, RESILIENCE4J_RETRY)
                    || Stream.of(ResilienceRules.RESILIENCE_PKG + "Retryable", ResilienceRules.SPRING_RETRY_PKG + "Retryable")
                    .anyMatch(f -> f.equals(ResilienceRules.annotationFqn(a, m.file()))));
            if (!annotated || ctx.ignored(m.file(), m.line(), Rules.RETRY_BYPASSED)) {
                continue;
            }
            boolean called = entrypointKeys.contains(m.key()) || !ctx.a.graph.in(m).isEmpty();
            if (proxyable(m) && (!called || calledThroughProxy(m, entrypointKeys))) {
                continue;
            }
            List<String> outside = new ArrayList<>();
            for (MethodInfo t : retryTargets(m, entrypointKeys)) {
                outside.add(t.label());
            }
            ctx.order(Rules.RETRY_BYPASSED, m, m.line(), "retry-bypassed")
                    .data("method", m.label())
                    .title("The retry on " + m.label() + " never runs: " + whyNot(m) + ", so it runs once and its first "
                            + "failure is final")
                    .suggestion(outside.isEmpty()
                            ? "Move the method into a bean other beans call (and make it public), or retry in code (RetryTemplate)."
                            : "Put the annotation on " + String.join(" / ", outside) + ", which other beans call (the retry then "
                            + "repeats it, including the call to " + m.label() + "), or move " + m.label() + " into its own bean.")
                    .basis("Spring AOP: \"self invocation via an explicit or implicit this reference will bypass the advice\"; "
                            + "private and final methods cannot be advised (Spring Framework reference, Understanding AOP Proxies)")
                    .doneWhen("the annotation sits on a method other beans call through the proxy, or the retry is in code");
        }
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
                    .data("method", m.label()).data("inner", path.getLast())
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
