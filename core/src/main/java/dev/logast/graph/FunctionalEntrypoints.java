package dev.logast.graph;

import dev.logast.model.Annotations;
import dev.logast.model.Entrypoint;
import dev.logast.model.Entrypoint.Kind;
import dev.logast.model.MethodInfo;
import dev.logast.parse.Pos;
import org.openrewrite.Cursor;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.Statement;
import org.openrewrite.java.tree.TypeUtils;

import java.util.*;

/**
 * Entrypoints that are not annotated methods but handlers a bean hands to Spring (#15): the handler functions of Spring
 * MVC's and Spring WebFlux's functional endpoints ({@code RouterFunction}, #14) and the {@code Consumer}/{@code Function} beans Spring Cloud
 * Stream binds to destinations. The entrypoint is the source method the bean refers to: a method reference
 * ({@code handler::show}) or a lambda that only calls one method ({@code m -> intake.accept(m.getPayload())}). A
 * lambda with a body of its own has no method to scope or log in, so it is not seen.
 */
final class FunctionalEntrypoints {
    /** Spring MVC's functional endpoints and WebFlux's (#14): the same builder and predicate API in two packages. */
    private static final List<String> PACKAGES = List.of("org.springframework.web.servlet.function.",
            "org.springframework.web.reactive.function.server.");
    private static final Set<String> VERBS = Set.of("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS");

    private FunctionalEntrypoints() {
    }

    /**
     * Handler functions of MVC and WebFlux router beans: {@code route().GET("/x", h::get)}, nested in
     * {@code path("/p", b -> ..)}, and {@code RouterFunctions.route(RequestPredicates.GET("/x"), h::get)}. Both observe
     * them like annotated controllers (Spring Boot reference › Metrics › Spring MVC / Spring WebFlux: "Auto-configuration
     * enables the instrumentation of all requests handled by Spring WebFlux controllers and functional handlers").
     */
    static void routes(CodeIndex index, Map<String, Entrypoint> out) {
        for (MethodInfo m : index.methods()) {
            if (!m.hasBody() || !(m.type().getReturnType() instanceof JavaType.FullyQualified rt)
                    || PACKAGES.stream().noneMatch(p -> TypeUtils.isAssignableTo(p + "RouterFunction", rt))) {
                continue;
            }
            new JavaIsoVisitor<Integer>() {
                @Override
                public J.MethodInvocation visitMethodInvocation(J.MethodInvocation mi, Integer p) {
                    route(index, mi, getCursor(), out);
                    return super.visitMethodInvocation(mi, p);
                }
            }.visit(m.decl().getBody(), 0, new Cursor(new Cursor(null, Cursor.ROOT_VALUE), m.decl()));
        }
    }

    private static void route(CodeIndex index, J.MethodInvocation mi, Cursor cursor, Map<String, Entrypoint> out) {
        String owner = declaring(mi);
        String pkg = functionPackage(owner);
        if (pkg == null || mi.getArguments().isEmpty()) {
            return;
        }
        String routerFunctions = pkg + "RouterFunctions";
        String verb;
        String path;
        if (owner.startsWith(routerFunctions) && VERBS.contains(mi.getSimpleName())) {
            // Builder.GET(handler), GET(pattern, handler), GET(pattern, predicate, handler), GET(predicate, handler)
            verb = mi.getSimpleName();
            Expression first = mi.getArguments().getFirst();
            path = TypeUtils.isString(first.getType()) ? Constants.concatenated(index, first) : "";
        } else if (owner.equals(routerFunctions) && mi.getSimpleName().equals("route") && mi.getArguments().size() == 2
                && mi.getArguments().getFirst() instanceof J.MethodInvocation predicate && (pkg + "RequestPredicates").equals(declaring(predicate))
                && VERBS.contains(predicate.getSimpleName()) && !predicate.getArguments().isEmpty()) {
            verb = predicate.getSimpleName();
            path = Constants.concatenated(index, predicate.getArguments().getFirst());
        } else {
            return;
        }
        if (path == null) {
            path = "";
        }
        MethodInfo handler = target(index, mi.getArguments().getLast());
        if (handler == null) {
            return;
        }
        String joined = ("/" + prefix(index, cursor, pkg) + "/" + path).replaceAll("/+", "/");
        if (joined.length() > 1 && joined.endsWith("/")) {
            joined = joined.substring(0, joined.length() - 1);
        }
        out.putIfAbsent(handler.key(), new Entrypoint(handler, Kind.HTTP, verb + " " + joined, true));
    }

    /** The patterns of the {@code path("/p", ..)} / {@code nest(path("/p"), ..)} calls the route sits in, outermost first. */
    private static String prefix(CodeIndex index, Cursor cursor, String pkg) {
        Deque<String> parts = new ArrayDeque<>();
        for (Cursor c = cursor.getParent(); c != null; c = c.getParent()) {
            if (c.getValue() instanceof J.MethodInvocation outer && outer.getArguments().size() == 2
                    && declaring(outer) != null && declaring(outer).startsWith(pkg + "RouterFunctions")) {
                Expression first = outer.getArguments().getFirst();
                String part = null;
                if (outer.getSimpleName().equals("path") && TypeUtils.isString(first.getType())) {
                    part = Constants.concatenated(index, first);
                } else if (outer.getSimpleName().equals("nest") && first instanceof J.MethodInvocation p
                        && p.getSimpleName().equals("path") && (pkg + "RequestPredicates").equals(declaring(p)) && !p.getArguments().isEmpty()) {
                    part = Constants.concatenated(index, p.getArguments().getFirst());
                }
                if (part != null) {
                    parts.addFirst(part);
                }
            }
        }
        return String.join("/", parts);
    }

    /**
     * {@code @Bean Consumer<..>} / {@code Function<..>} methods Spring Cloud Stream binds: the names in
     * spring.cloud.function.definition, else the one functional bean auto-discovered (several without the property are
     * a conflict nothing is bound for; Spring Cloud Stream reference › Producing and Consuming Messages). A Supplier is
     * polled and sends: no inbound entrypoint. Each invocation is observed by Spring Cloud Stream's
     * ObservationFunctionAroundWrapper (reference › Observability), so the entrypoint is observed by the framework.
     */
    static void streamFunctions(CodeIndex index, List<String> definition, boolean autodetect, Map<String, Entrypoint> out) {
        Map<String, MethodInfo> beans = new TreeMap<>();
        int functional = 0;
        for (MethodInfo m : index.methods()) {
            J.Annotation bean = m.decl().getLeadingAnnotations().stream()
                    .filter(a -> Annotations.is(a, "org.springframework.context.annotation.Bean")).findFirst().orElse(null);
            if (bean == null || !m.hasBody() || !(m.type().getReturnType() instanceof JavaType.FullyQualified rt)) {
                continue;
            }
            String fqn = Types.fqn(rt);
            if (fqn.equals("java.util.function.Supplier")) {
                functional++;
            } else if (fqn.equals("java.util.function.Consumer") || fqn.equals("java.util.function.Function")) {
                functional++;
                String name = Annotations.stringAttribute(bean, "value", "name").orElse(m.name());
                beans.put(name, m);
            }
        }
        for (var e : beans.entrySet()) {
            boolean bound = definition != null ? definition.contains(e.getKey()) : autodetect && functional == 1;
            if (!bound) {
                continue;
            }
            MethodInfo handler = returned(index, e.getValue());
            if (handler != null) {
                out.putIfAbsent(handler.key(), new Entrypoint(handler, Kind.STREAM, "stream " + e.getKey() + "-in-0", true));
            }
        }
    }

    /**
     * Delegates of Spring Data Redis {@code new MessageListenerAdapter(delegate[, "method"])}: the adapter calls the
     * delegate's method of that name (default {@code handleMessage}, or {@code setDefaultListenerMethod("..")}) with the
     * converted message and optionally the channel (Spring Data Redis reference, Redis Messaging › Message Listener
     * Adapters). A delegate that is a MessageListener itself is called through onMessage (found by type). A name that is
     * not a constant, a delegate that is not a source class, or no method of that name is reported.
     */
    static void redisAdapters(CodeIndex index, Map<String, Entrypoint> out, List<EntrypointFinder.Unresolved> unresolved) {
        for (MethodInfo m : index.methods()) {
            if (!m.hasBody()) {
                continue;
            }
            List<J.NewClass> adapters = new ArrayList<>();
            List<J.MethodInvocation> setters = new ArrayList<>();
            new JavaIsoVisitor<Integer>() {
                @Override
                public J.NewClass visitNewClass(J.NewClass nc, Integer p) {
                    if (nc.getType() instanceof JavaType.FullyQualified t && Types.fqn(t).equals(EntrypointFinder.REDIS_ADAPTER)) {
                        adapters.add(nc);
                    }
                    return super.visitNewClass(nc, p);
                }

                @Override
                public J.MethodInvocation visitMethodInvocation(J.MethodInvocation mi, Integer p) {
                    if (mi.getSimpleName().equals("setDefaultListenerMethod") && mi.getArguments().size() == 1) {
                        setters.add(mi);
                    }
                    return super.visitMethodInvocation(mi, p);
                }
            }.visit(m.decl().getBody(), 0, new Cursor(new Cursor(null, Cursor.ROOT_VALUE), m.decl()));
            for (J.NewClass nc : adapters) {
                List<Expression> args = nc.getArguments().stream().filter(a -> !(a instanceof J.Empty)).toList();
                int line = Pos.line(nc);
                if (args.isEmpty() || !(args.getFirst().getType() instanceof JavaType.FullyQualified delegate)) {
                    unresolved.add(new EntrypointFinder.Unresolved(m, line, "MessageListenerAdapter in " + m.label(),
                            "the delegate is set later (setDelegate)"));
                    continue;
                }
                if (TypeUtils.isAssignableTo("org.springframework.data.redis.connection.MessageListener", delegate)) {
                    continue;   // called through its own onMessage
                }
                String name = args.size() > 1 ? Constants.concatenated(index, args.get(1))
                        : setters.isEmpty() ? "handleMessage"
                        : setters.size() == 1 ? Constants.concatenated(index, setters.getFirst().getArguments().getFirst()) : null;
                if (name == null) {
                    unresolved.add(new EntrypointFinder.Unresolved(m, line, "MessageListenerAdapter for " + delegate.getClassName(),
                            "the listener method name is not a constant"));
                    continue;
                }
                List<MethodInfo> handlers = index.methods().stream()
                        .filter(h -> h.hasBody() && h.name().equals(name) && Types.fqn(delegate).equals(h.owner().fqn())
                                && !h.type().getParameterTypes().isEmpty() && h.type().getParameterTypes().size() <= 2)
                        .toList();
                if (handlers.isEmpty()) {
                    unresolved.add(new EntrypointFinder.Unresolved(m, line, "MessageListenerAdapter for " + delegate.getClassName(),
                            "no source method " + name + "(message[, channel]) in " + delegate.getClassName()));
                }
                for (MethodInfo h : handlers) {
                    out.putIfAbsent(h.key(), new Entrypoint(h, Kind.REDIS, "redis " + h.label(), false));
                }
            }
        }
    }

    /** The method a bean method returns as a method reference or single-call lambda. */
    private static MethodInfo returned(CodeIndex index, MethodInfo bean) {
        List<Statement> statements = bean.decl().getBody().getStatements();
        return statements.size() == 1 && statements.getFirst() instanceof J.Return r && r.getExpression() != null
                ? target(index, r.getExpression()) : null;
    }

    /** {@code x::m} or {@code a -> x.m(..)} (also {@code a -> { return x.m(..); }}): the source method m, if one. */
    private static MethodInfo target(CodeIndex index, Expression e) {
        JavaType.Method mt = null;
        if (e instanceof J.MemberReference ref) {
            mt = ref.getMethodType();
        } else if (e instanceof J.Lambda l) {
            J body = l.getBody();
            if (body instanceof J.Block b && b.getStatements().size() == 1) {
                body = b.getStatements().getFirst() instanceof J.Return r ? r.getExpression() : b.getStatements().getFirst();
            }
            if (body instanceof J.MethodInvocation call) {
                mt = call.getMethodType();
            }
        }
        if (mt == null) {
            return null;
        }
        List<MethodInfo> found = index.resolve(mt);
        return found.size() == 1 ? found.getFirst() : null;
    }

    /** The functional-endpoint package (MVC or WebFlux) a type belongs to, or null. */
    private static String functionPackage(String fqn) {
        return fqn == null ? null : PACKAGES.stream().filter(fqn::startsWith).findFirst().orElse(null);
    }

    private static String declaring(J.MethodInvocation mi) {
        return mi.getMethodType() == null ? null : Types.fqn(mi.getMethodType().getDeclaringType());
    }
}
