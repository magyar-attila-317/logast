package dev.logast.export;

import dev.logast.Analysis;
import dev.logast.graph.CallGraph;
import dev.logast.model.Annotations;
import dev.logast.model.ClassInfo;
import dev.logast.model.MethodInfo;
import dev.logast.parse.Pos;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.TypeUtils;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * #255: whether an outgoing call is protected (a timeout, a retry, a circuit breaker, a bulkhead) and where that is
 * configured, from what the code and the configuration prove: an annotation on the calling method, its class, or on
 * every path that reaches it; for an HTTP client, how it is built (its own request factory, Spring Boot's builder and
 * the {@code spring.http.client.*} properties, a single {@code @Bean} that builds it). Each is {@code yes} (with
 * {@code <name>From}), {@code no} where the code proves it (a client built without a timeout that nothing configures),
 * else {@code not known}.
 */
final class CallProtection {
    static final String YES = "yes";
    static final String NO = "no";
    static final String NOT_KNOWN = "not known";

    private static final Map<String, List<String>> ANNOTATIONS = Map.of(
            "retry", List.of("org.springframework.retry.annotation.Retryable", "org.springframework.resilience.annotation.Retryable",
                    "io.github.resilience4j.retry.annotation.Retry"),
            "circuitBreaker", List.of("io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker"),
            "bulkhead", List.of("io.github.resilience4j.bulkhead.annotation.Bulkhead",
                    "org.springframework.resilience.annotation.ConcurrencyLimit"),
            "timeout", List.of("io.github.resilience4j.timelimiter.annotation.TimeLimiter"));

    private static final List<String> CUSTOMIZERS = List.of("org.springframework.boot.web.client.RestClientCustomizer",
            "org.springframework.boot.web.client.RestTemplateCustomizer", "org.springframework.boot.web.reactive.function.client.WebClientCustomizer",
            "org.springframework.boot.restclient.RestClientCustomizer", "org.springframework.boot.restclient.RestTemplateCustomizer",
            "org.springframework.boot.webclient.WebClientCustomizer");

    private final Analysis a;
    private final int[] boot;   // {major, minor}; {0, 0} unknown

    CallProtection(Analysis a, String bootVersion) {
        this.a = a;
        this.boot = version(bootVersion);
    }

    /** The protection attrs of a call made in {@code m} (null: the method is not known) with {@code client}. */
    Map<String, Object> attrs(MethodInfo m, String transport, String client) {
        Map<String, Object> out = new TreeMap<>();
        for (String what : List.of("retry", "circuitBreaker", "bulkhead", "timeout")) {
            List<String> from = m == null ? null : annotated(m, ANNOTATIONS.get(what));
            if (from != null && !from.isEmpty()) {
                out.put(what, YES);
                out.put(what + "From", String.join(", ", from));
            } else {
                out.put(what, NOT_KNOWN);
            }
        }
        if (NOT_KNOWN.equals(out.get("timeout")) && m != null && transport.equals("http")) {
            String[] t = httpTimeout(m.owner(), client);
            out.put("timeout", t[0]);
            if (t[1] != null) {
                out.put("timeoutFrom", t[1]);
            }
        }
        return out;
    }

    // ---- annotations ---------------------------------------------------------------------------------------------------

    /**
     * Where one of {@code annotations} covers every run of {@code m}: on {@code m} or its class (when something outside
     * the class calls it, so Spring's proxy is in between), or on every path that reaches it. Null when a path has none.
     */
    private List<String> annotated(MethodInfo m, List<String> annotations) {
        Set<String> from = new LinkedHashSet<>();
        Set<String> seen = new HashSet<>();
        ArrayDeque<MethodInfo> todo = new ArrayDeque<>(List.of(m));
        while (!todo.isEmpty()) {
            MethodInfo x = todo.pop();
            if (!seen.add(x.key()) || seen.size() > 5000) {
                return seen.size() > 5000 ? null : from.isEmpty() ? null : List.copyOf(from);   // too wide to decide
            }
            String here = proxied(x) ? annotation(x, annotations) : null;
            if (here != null) {
                from.add(here);
                continue;
            }
            List<CallGraph.Edge> callers = a.graph.in(x);
            if (callers.isEmpty()) {
                return null;   // a path that starts here (an entrypoint, startup code) has none
            }
            callers.forEach(e -> todo.push(e.from()));
        }
        return from.isEmpty() ? null : List.copyOf(from);
    }

    /** Spring's proxy runs around {@code m}: nothing calls it, or something outside its class does (#: a self-call bypasses it). */
    private boolean proxied(MethodInfo m) {
        List<CallGraph.Edge> callers = a.graph.in(m);
        return callers.isEmpty() || callers.stream().anyMatch(e -> !e.from().owner().fqn().equals(m.owner().fqn()));
    }

    private static String annotation(MethodInfo m, List<String> annotations) {
        for (J.Annotation an : m.decl().getLeadingAnnotations()) {
            if (annotations.stream().anyMatch(f -> Annotations.is(an, f))) {
                return m.file().relPath() + ":" + Pos.line(an);
            }
        }
        for (J.Annotation an : m.owner().decl().getLeadingAnnotations()) {
            if (annotations.stream().anyMatch(f -> Annotations.is(an, f))) {
                return m.owner().file().relPath() + ":" + Pos.line(an);
            }
        }
        return null;
    }

    // ---- HTTP client timeouts ------------------------------------------------------------------------------------------

    private static final Map<String, String> CLIENT_TYPES = Map.of(
            "RestClient", "org.springframework.web.client.RestClient",
            "RestTemplate", "org.springframework.web.client.RestTemplate",
            "WebClient", "org.springframework.web.reactive.function.client.WebClient");

    /** {state, from}: how the clients of type {@code client} that {@code owner} uses are built. */
    private String[] httpTimeout(ClassInfo owner, String client) {
        String type = CLIENT_TYPES.get(client);
        if (type == null) {
            return new String[]{NOT_KNOWN, null};
        }
        List<String[]> found = builds(owner.decl(), owner, type);
        if (found.isEmpty()) {
            // the class is handed its client: the one @Bean method that makes such clients says how
            List<MethodInfo> beans = a.index.methods().stream().filter(x -> x.hasAnnotation("org.springframework.context.annotation.Bean")
                    && x.type().getReturnType() != null && TypeUtils.isOfClassType(x.type().getReturnType(), type)).toList();
            if (beans.size() != 1) {
                return new String[]{NOT_KNOWN, null};
            }
            found = builds(beans.getFirst().decl(), beans.getFirst().owner(), type);
        }
        Set<String> states = new HashSet<>();
        found.forEach(f -> states.add(f[0]));
        return states.size() == 1 ? new String[]{found.getFirst()[0], String.join(", ", found.stream().map(f -> f[1]).filter(java.util.Objects::nonNull).toList())}
                : new String[]{NOT_KNOWN, null};
    }

    /** Each construction of a {@code type} client in {@code tree} (not nested classes): {state, from}. */
    private List<String[]> builds(J tree, ClassInfo owner, String type) {
        List<String[]> out = new ArrayList<>();
        new JavaIsoVisitor<Integer>() {
            @Override
            public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration c, Integer p) {
                return c == owner.decl() ? super.visitClassDeclaration(c, p) : c;
            }

            @Override
            public J.MethodInvocation visitMethodInvocation(J.MethodInvocation mi, Integer p) {
                if ((mi.getSimpleName().equals("build") || mi.getSimpleName().equals("create")) && mi.getType() != null
                        && TypeUtils.isOfClassType(mi.getType(), type)) {
                    out.add(chain(mi, owner, type));
                    return mi;   // its chain is this construction
                }
                return super.visitMethodInvocation(mi, p);
            }

            @Override
            public J.NewClass visitNewClass(J.NewClass nc, Integer p) {
                if (nc.getType() != null && TypeUtils.isOfClassType(nc.getType(), type)) {
                    boolean factory = nc.getArguments() != null && nc.getArguments().stream().anyMatch(x -> !(x instanceof J.Empty));
                    out.add(factory ? factory(nc.getArguments().getFirst(), owner, nc)
                            : new String[]{NO, owner.file().relPath() + ":" + Pos.line(nc)});
                }
                return super.visitNewClass(nc, p);
            }
        }.visit(tree, 0);
        return out;
    }

    /** A builder chain ending in {@code build()} (or a static {@code create}): what sets its timeouts. */
    private String[] chain(J.MethodInvocation end, ClassInfo owner, String type) {
        String at = owner.file().relPath() + ":" + Pos.line(end);
        Expression x = end;
        while (x instanceof J.MethodInvocation mi) {
            String name = mi.getSimpleName();
            if (name.equals("requestFactory") || name.equals("clientConnector")) {
                return factory(mi.getArguments().getFirst(), owner, mi);
            }
            if (name.toLowerCase(java.util.Locale.ROOT).contains("timeout")) {
                return new String[]{YES, owner.file().relPath() + ":" + Pos.line(mi)};
            }
            if (mi.getSelect() == null) {
                return new String[]{NOT_KNOWN, null};
            }
            x = mi.getSelect();
        }
        JavaType rootType = x == null ? null : x.getType();
        boolean staticRoot = x instanceof J.Identifier id && id.getFieldType() == null;
        if (staticRoot || rootType == null) {
            // RestClient.builder() / create(..), WebClient.builder() / create(..): Spring Boot's properties do not
            // apply, and the JDK client underneath has no read timeout
            return staticRoot ? new String[]{NO, at} : new String[]{NOT_KNOWN, null};
        }
        // Spring Boot's builder, handed in: its spring.http.client.* properties (Boot 3.4+; reactiveclient: 3.5+)
        if (CUSTOMIZERS.stream().anyMatch(c -> a.index.classes().stream().anyMatch(k -> k.type() != null && TypeUtils.isAssignableTo(c, k.type())))) {
            return new String[]{NOT_KNOWN, null};   // a customizer may set them: not followed
        }
        boolean reactive = type.contains(".reactive.");
        boolean has = boot[0] > 3 || boot[0] == 3 && boot[1] >= (reactive ? 5 : 4);
        String prefix = "spring.http." + (reactive ? "reactiveclient" : "client") + ".";
        Map<String, String> props = a.springProperties;
        String read = props.keySet().stream().filter(k -> k.startsWith(prefix) && k.matches(".*read-?timeout")).findFirst().orElse(null);
        String connect = props.keySet().stream().filter(k -> k.startsWith(prefix) && k.matches(".*connect-?timeout")).findFirst().orElse(null);
        if (has && read != null && connect != null) {
            return new String[]{YES, read + ", " + connect};
        }
        if (read == null && connect == null) {
            return new String[]{NO, at};
        }
        return new String[]{NOT_KNOWN, null};
    }

    /** A request factory or connector given to the client: it sets timeouts when it, or the method that makes it, names one. */
    private String[] factory(Expression arg, ClassInfo owner, J where) {
        boolean[] timeout = {false};
        J[] scope = {arg};
        if (arg instanceof J.MethodInvocation mi && mi.getMethodType() != null) {
            MethodInfo maker = a.index.resolve(mi.getMethodType()).stream().findFirst().orElse(null);
            if (maker != null && maker.decl().getBody() != null) {
                scope[0] = maker.decl().getBody();
            }
        }
        new JavaIsoVisitor<Integer>() {
            @Override
            public J.MethodInvocation visitMethodInvocation(J.MethodInvocation mi, Integer p) {
                timeout[0] |= mi.getSimpleName().toLowerCase(java.util.Locale.ROOT).contains("timeout");
                return super.visitMethodInvocation(mi, p);
            }
        }.visit(scope[0], 0);
        return timeout[0] ? new String[]{YES, owner.file().relPath() + ":" + Pos.line(where)} : new String[]{NOT_KNOWN, null};
    }

    private static int[] version(String v) {
        if (v == null) {
            return new int[]{0, 0};
        }
        String[] parts = v.split("[.-]");
        try {
            return new int[]{Integer.parseInt(parts[0]), parts.length > 1 ? Integer.parseInt(parts[1]) : 0};
        } catch (NumberFormatException e) {
            return new int[]{0, 0};
        }
    }
}
