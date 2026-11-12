package dev.logast.rules;

import dev.logast.model.Annotations;
import dev.logast.model.ClassInfo;
import dev.logast.model.Entrypoint;
import dev.logast.model.LogCall;
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
 * {@code errors} pack: what an HTTP client sees when something goes wrong.
 * <ul>
 *   <li>ERROR_DETAILS_EXPOSED: exception messages / stack traces in error responses (server.error.include-*, or a
 *       handler for unexpected exceptions copying {@code e.getMessage()} into the body);</li>
 *   <li>UNMAPPED_EXCEPTION: an application exception thrown on an HTTP path that no handler or
 *       {@code @ResponseStatus} maps: the client gets a 500 for what is usually a business outcome;</li>
 *   <li>CLIENT_ERROR_AS_SERVER_ERROR: a handler answering 500 for a client error (validation, not found, denied);</li>
 *   <li>PROBLEM_DETAILS_DISABLED: Spring MVC errors are not RFC 9457 ProblemDetail responses.</li>
 * </ul>
 */
final class ErrorRules {
    private static final String EXCEPTION_HANDLER = "org.springframework.web.bind.annotation.ExceptionHandler";
    private static final Set<String> GENERIC = Set.of("java.lang.Exception", "java.lang.RuntimeException", "java.lang.Throwable",
            "java.lang.Error");
    private static final Set<String> DETAIL_CALLS = Set.of("getMessage", "getLocalizedMessage", "getStackTrace", "toString",
            "printStackTrace");

    private record Handler(MethodInfo method, List<JavaType.FullyQualified> types) {
    }

    private final RuleContext ctx;
    private final List<Handler> handlers = new ArrayList<>();
    private final Set<String> clientErrors;

    ErrorRules(RuleContext ctx) {
        this.ctx = ctx;
        this.clientErrors = new HashSet<>(ctx.policy.levels().clientErrors());
    }

    void run() {
        boolean any = ctx.enabled(Rules.ERROR_DETAILS_EXPOSED) || ctx.enabled(Rules.UNMAPPED_EXCEPTION)
                || ctx.enabled(Rules.CLIENT_ERROR_AS_SERVER_ERROR) || ctx.enabled(Rules.PROBLEM_DETAILS_DISABLED);
        if (!any || ctx.a.entrypoints.stream().noneMatch(ep -> ep.kind() == Entrypoint.Kind.HTTP)) {
            return;
        }
        for (MethodInfo m : ctx.methods()) {
            if (m.hasAnnotation(EXCEPTION_HANDLER)) {
                handlers.add(new Handler(m, handledTypes(m)));
            }
        }
        if (ctx.enabled(Rules.ERROR_DETAILS_EXPOSED)) {
            exposedByConfig();
            handlers.forEach(this::exposedByHandler);
        }
        if (ctx.enabled(Rules.CLIENT_ERROR_AS_SERVER_ERROR)) {
            handlers.forEach(this::clientErrorAs500);
        }
        if (ctx.enabled(Rules.UNMAPPED_EXCEPTION)) {
            unmapped();
        }
        if (ctx.enabled(Rules.PROBLEM_DETAILS_DISABLED)) {
            problemDetails();
        }
    }

    /** {@code @ExceptionHandler({A.class, B.class})}, else the Throwable parameters. */
    private static List<JavaType.FullyQualified> handledTypes(MethodInfo m) {
        List<JavaType.FullyQualified> out = new ArrayList<>();
        for (J.Annotation a : m.decl().getLeadingAnnotations()) {
            if (!Annotations.is(a, EXCEPTION_HANDLER) || a.getArguments() == null) {
                continue;
            }
            for (Expression arg : a.getArguments()) {
                Expression v = arg instanceof J.Assignment as ? as.getAssignment() : arg;
                List<Expression> items = v instanceof J.NewArray na && na.getInitializer() != null ? na.getInitializer() : List.of(v);
                for (Expression item : items) {
                    if (item instanceof J.FieldAccess fa && fa.getSimpleName().equals("class")
                            && TypeUtils.asFullyQualified(fa.getTarget().getType()) instanceof JavaType.FullyQualified fq) {
                        out.add(fq);
                    }
                }
            }
        }
        if (out.isEmpty()) {
            for (Statement p : m.decl().getParameters()) {
                if (p instanceof J.VariableDeclarations vd && TypeUtils.isAssignableTo("java.lang.Throwable", vd.getType())
                        && TypeUtils.asFullyQualified(vd.getType()) instanceof JavaType.FullyQualified fq) {
                    out.add(fq);
                }
            }
        }
        return out;
    }

    // ---- details in responses -------------------------------------------------------------------

    private void exposedByConfig() {
        Map<String, String> props = ctx.a.springProperties;
        Map<String, String> fix = new LinkedHashMap<>();
        for (String key : List.of("include-message", "include-stacktrace", "include-exception")) {
            String v = props.get("server.error." + key);
            if (v == null) {
                continue;
            }
            String norm = v.strip().toLowerCase(Locale.ROOT).replace('_', '-');
            if (key.equals("include-exception") ? norm.equals("true") : norm.equals("always") || norm.startsWith("on-")) {
                fix.put(key, key.equals("include-exception") ? "false" : "never");
            }
        }
        if (fix.isEmpty()) {
            return;
        }
        OrderBuilder b = ctx.order(Rules.ERROR_DETAILS_EXPOSED, "server-error-config").at(ServiceYaml.path(ctx), 1)
                .title("server.error." + String.join(", ", fix.keySet()) + " sends exception messages / stack traces to "
                        + "clients: internals (SQL, class names, paths) leak in error responses")
                .suggestion("Sets them to never/false; the details stay in the logs, correlated by trace id.");
        StringBuilder snippet = new StringBuilder("server:\n  error:\n");
        fix.forEach((k, v) -> snippet.append("    ").append(k).append(": ").append(v).append('\n'));
        ServiceYaml.merge(ctx, b, snippet.toString(), true);
    }

    /** A handler for unexpected exceptions that copies the exception's message or trace into the response. */
    private void exposedByHandler(Handler h) {
        if (h.types().isEmpty() || h.types().stream().noneMatch(this::serverSide)) {
            return;
        }
        MethodInfo m = h.method();
        Set<String> exVars = new HashSet<>();
        for (Statement p : m.decl().getParameters()) {
            if (p instanceof J.VariableDeclarations vd && TypeUtils.isAssignableTo("java.lang.Throwable", vd.getType())) {
                vd.getVariables().forEach(v -> exVars.add(v.getSimpleName()));
            }
        }
        List<int[]> logRanges = m.facts().logs.stream().map(LogCall::call).map(c -> new int[]{Pos.start(c), Pos.end(c)}).toList();
        J.MethodInvocation[] hit = {null};
        new JavaIsoVisitor<Integer>() {
            @Override
            public J.MethodInvocation visitMethodInvocation(J.MethodInvocation mi, Integer p) {
                int at = Pos.start(mi);
                boolean inLog = logRanges.stream().anyMatch(r -> r[0] <= at && at < r[1]);
                if (hit[0] == null && !inLog && DETAIL_CALLS.contains(mi.getSimpleName())
                        && mi.getSelect() instanceof J.Identifier id && exVars.contains(id.getSimpleName())) {
                    hit[0] = mi;
                }
                return super.visitMethodInvocation(mi, p);
            }
        }.visit(m.decl().getBody(), 0);
        if (hit[0] == null) {
            return;
        }
        int line = Pos.line(hit[0]);
        if (ctx.ignored(m.file(), line, Rules.ERROR_DETAILS_EXPOSED)) {
            return;
        }
        ctx.order(Rules.ERROR_DETAILS_EXPOSED, m, line, "handler")
                .title("Handler for unexpected " + names(h.types()) + " puts " + hit[0].printTrimmed()
                        + " into the response: internal details reach the client")
                .suggestion("Answer with a fixed text (ProblemDetail.forStatusAndDetail(INTERNAL_SERVER_ERROR, \"Unexpected error\")) "
                        + "and log the exception; the client can quote the trace id.")
                .context(ctx.context(m, Pos.start(hit[0]), Text.oneLine(hit[0].printTrimmed(), 160), null));
    }

    /** Unexpected exceptions: generic, or library ones that are not client errors. App exceptions carry messages for clients. */
    private boolean serverSide(JavaType.FullyQualified t) {
        String fqn = t.getFullyQualifiedName();
        return GENERIC.contains(fqn) || ctx.a.index.clazz(fqn) == null && !clientError(t) && !frameworkClientError(t);
    }

    /** Spring's request/validation/security exceptions describe the client's mistake; their messages are for the client. */
    private static boolean frameworkClientError(JavaType.FullyQualified t) {
        String fqn = t.getFullyQualifiedName();
        return fqn.startsWith("org.springframework.web.") || fqn.startsWith("org.springframework.validation.")
                || fqn.startsWith("org.springframework.security.") || fqn.startsWith("jakarta.validation.")
                || TypeUtils.isAssignableTo("org.springframework.web.ErrorResponse", t);
    }

    private boolean clientError(JavaType.FullyQualified t) {
        return clientErrors.stream().anyMatch(c -> TypeUtils.isAssignableTo(c, t));
    }

    // ---- client error answered with 500 ---------------------------------------------------------

    private void clientErrorAs500(Handler h) {
        List<JavaType.FullyQualified> client = h.types().stream().filter(this::clientError).toList();
        if (client.isEmpty()) {
            return;
        }
        MethodInfo m = h.method();
        String text = m.file().slice(Pos.start(m.decl()), Pos.end(m.decl()))
                .replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\\n]*", "");   // what the code does, not what comments say
        if (!text.contains("INTERNAL_SERVER_ERROR") && !text.matches("(?s).*status\\(\\s*500\\s*\\).*")
                && !text.contains("valueOf(500)")) {
            return;
        }
        if (ctx.ignored(m.file(), m.line(), Rules.CLIENT_ERROR_AS_SERVER_ERROR)) {
            return;
        }
        ctx.order(Rules.CLIENT_ERROR_AS_SERVER_ERROR, m, m.line(), "status")
                .title("Handler for " + names(client) + " answers 500: a client mistake is reported as a server failure "
                        + "(alerts fire, clients retry)")
                .suggestion("Answer 4xx (400 validation, 404 not found, 403 denied, 409 conflict) and log at WARN or below.")
                .context(ctx.context(m, Pos.start(m.decl()), Text.oneLine(text, 160), null));
    }

    // ---- application exceptions nobody maps -----------------------------------------------------

    private void unmapped() {
        Set<String> onHttp = new HashSet<>();
        for (Entrypoint ep : ctx.a.entrypoints) {
            if (ep.kind() == Entrypoint.Kind.HTTP) {
                onHttp.addAll(ctx.a.paths.reachableFrom(ep));
            }
        }
        Map<String, List<String[]>> throwsByType = new TreeMap<>();
        Map<String, JavaType.FullyQualified> types = new HashMap<>();
        for (MethodInfo m : ctx.methods()) {
            if (!onHttp.contains(m.key()) || m.decl().getBody() == null) {
                continue;
            }
            new JavaIsoVisitor<Integer>() {
                @Override
                public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration c, Integer p) {
                    return c;
                }

                @Override
                public J.Throw visitThrow(J.Throw t, Integer p) {
                    if (t.getException() instanceof J.NewClass nc && TypeUtils.asFullyQualified(nc.getType()) instanceof JavaType.FullyQualified x
                            && ctx.a.index.clazz(x.getFullyQualifiedName()) != null && !caughtLocally(getCursor(), x)) {
                        types.put(x.getFullyQualifiedName(), x);
                        throwsByType.computeIfAbsent(x.getFullyQualifiedName(), k -> new ArrayList<>())
                                .add(new String[]{m.file().relPath(), String.valueOf(Pos.line(t))});
                    }
                    return super.visitThrow(t, p);
                }
            }.visit(m.decl().getBody(), 0, new Cursor(new Cursor(null, Cursor.ROOT_VALUE), m.decl()));
        }
        throwsByType.forEach((fqn, sites) -> {
            JavaType.FullyQualified x = types.get(fqn);
            if (mapped(x)) {
                return;
            }
            boolean catchAll = handlers.stream().anyMatch(h -> h.types().stream().anyMatch(t -> GENERIC.contains(t.getFullyQualifiedName())
                    && TypeUtils.isAssignableTo(t.getFullyQualifiedName(), x)));
            ClassInfo c = ctx.a.index.clazz(fqn);
            int line = Pos.line(c.decl());
            if (ctx.ignored(c.file(), line, Rules.UNMAPPED_EXCEPTION)) {
                return;
            }
            OrderBuilder b = ctx.order(Rules.UNMAPPED_EXCEPTION, "unmapped|" + fqn).at(c.file().relPath(), line)
                    .title(c.simpleName() + " is thrown on HTTP paths (" + sites.size() + "×) but no handler or @ResponseStatus maps it"
                            + (catchAll ? ": it ends in the catch-all handler" : ": the client gets a 500"))
                    .suggestion("Decide its status: @ResponseStatus(HttpStatus.CONFLICT) on the class, or an @ExceptionHandler returning "
                            + "a ProblemDetail (with a stable error code) in the controller advice.");
            sites.stream().limit(10).forEach(s -> b.location(s[0], Integer.parseInt(s[1]), Rules.UNMAPPED_EXCEPTION));
        });
    }

    /** @ResponseStatus (on it or a supertype), a status-carrying exception type, or a specific handler. */
    private boolean mapped(JavaType.FullyQualified x) {
        for (JavaType.FullyQualified t = x; t != null; t = t.getSupertype()) {
            if (t.getAnnotations().stream().anyMatch(a -> a.getFullyQualifiedName().equals("org.springframework.web.bind.annotation.ResponseStatus"))) {
                return true;
            }
            ClassInfo c = ctx.a.index.clazz(t.getFullyQualifiedName());
            if (c != null && c.decl().getLeadingAnnotations().stream().anyMatch(a -> Annotations.is(a, "org.springframework.web.bind.annotation.ResponseStatus"))) {
                return true;
            }
        }
        if (TypeUtils.isAssignableTo("org.springframework.web.ErrorResponse", x)
                || TypeUtils.isAssignableTo("org.springframework.web.server.ResponseStatusException", x) || clientError(x)) {
            return true;
        }
        return handlers.stream().anyMatch(h -> h.types().stream().anyMatch(t -> !GENERIC.contains(t.getFullyQualifiedName())
                && TypeUtils.isAssignableTo(t.getFullyQualifiedName(), x)));
    }

    /** Thrown inside a try whose catch handles it right there. */
    private static boolean caughtLocally(Cursor c, JavaType.FullyQualified x) {
        for (Cursor p = c.getParent(); p != null; p = p.getParent()) {
            if (p.getValue() instanceof J.Try t && p.getParent() != null) {
                for (J.Try.Catch k : t.getCatches()) {
                    JavaType caught = k.getParameter().getTree().getType();
                    if (caught instanceof JavaType.MultiCatch mc ? mc.getThrowableTypes().stream()
                            .anyMatch(ct -> TypeUtils.isAssignableTo(ct, x)) : TypeUtils.isAssignableTo(caught, x)) {
                        return true;
                    }
                }
            }
            if (p.getValue() instanceof J.MethodDeclaration) {
                break;
            }
        }
        return false;
    }

    // ---- ProblemDetail --------------------------------------------------------------------------

    private void problemDetails() {
        if ("true".equalsIgnoreCase(ctx.a.springProperties.getOrDefault("spring.mvc.problemdetails.enabled", ""))) {
            return;
        }
        for (ClassInfo c : ctx.a.index.classes()) {
            if (TypeUtils.isAssignableTo("org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler", c.type())) {
                return; // the advice already answers framework errors with ProblemDetail
            }
        }
        ctx.order(Rules.PROBLEM_DETAILS_DISABLED, "problemdetails").at(ServiceYaml.path(ctx), 1)
                .title("Spring MVC errors (400 bad input, 405, 415, ...) are not RFC 9457 ProblemDetail responses: "
                        + "every service answers them in its own shape")
                .suggestion("Set spring.mvc.problemdetails.enabled: true (or extend ResponseEntityExceptionHandler in the controller "
                        + "advice). Not applied automatically: it changes the error body API clients see.");
    }

    private static String names(List<JavaType.FullyQualified> types) {
        return String.join(", ", types.stream().map(JavaType.FullyQualified::getClassName).toList());
    }
}
