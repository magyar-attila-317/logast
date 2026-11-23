package dev.logast.rules;

import dev.logast.model.Annotations;
import dev.logast.model.ClassInfo;
import dev.logast.model.Entrypoint;
import dev.logast.model.LogCall;
import dev.logast.model.MethodInfo;
import dev.logast.orders.Edit;
import dev.logast.parse.LibraryAdvice;
import dev.logast.parse.Pos;
import dev.logast.parse.SourceFileInfo;
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
 *   <li>PROBLEM_DETAILS_DISABLED: Spring MVC (or WebFlux) errors are not RFC 9457 ProblemDetail responses;</li>
 *   <li>UNEXPECTED_ERROR_UNLOGGED: a catch-all handler answers without logging the exception (no stack trace anywhere);</li>
 *   <li>ERROR_HANDLER_STATUS_OK: a handler whose answer carries no error status (the client gets 200);</li>
 *   <li>TX_COMMITS_ON_CHECKED: a {@code @Transactional} method that throws a checked exception commits by default;</li>
 *   <li>REQUEST_NOT_VALIDATED: a request body / model attribute whose type has Bean Validation constraints, without
 *       {@code @Valid}: the constraints are never checked.</li>
 * </ul>
 * Handlers count from the sources and from {@code @ControllerAdvice} classes in libraries on the classpath (#52).
 * <p>A WebFlux-only service (#102: spring-webflux without spring-webmvc, which Spring Boot runs as a reactive web
 * application) gets the WebFlux names: spring.webflux.problemdetails.enabled, the reactive ResponseEntityExceptionHandler,
 * WebExchangeBindException for a failed {@code @Valid}; ERROR_RESPONSE_UNTRACED switches on logging-support's
 * ReactiveProblemTraceId there (#103: an ErrorResponse.Interceptor, WebFlux has no ResponseBodyAdvice). A service with
 * both runs Spring MVC and keeps the MVC behaviour.
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
    /** Exception types handled by global {@code @ControllerAdvice} classes in libraries on the classpath (#52). */
    private final List<JavaType.FullyQualified> libraryHandled = new ArrayList<>();
    /** A library advice extends ResponseEntityExceptionHandler (MVC's or WebFlux's): the framework's exceptions get their 4xx ProblemDetail. */
    private boolean libraryAnswersMvcErrors;
    private final Set<String> clientErrors;
    /** Spring WebFlux without Spring MVC: the reactive names apply (#102). */
    private final boolean webflux;

    ErrorRules(RuleContext ctx) {
        this.ctx = ctx;
        this.clientErrors = new HashSet<>(ctx.policy.levels().clientErrors());
        this.webflux = webfluxOnly(ctx);
    }

    /**
     * Spring WebFlux is there and Spring MVC is not: Spring Boot runs a reactive web application (Boot reference,
     * SpringApplication › Web Environment: "If Spring MVC is not present and Spring WebFlux is present, an
     * AnnotationConfigReactiveWebServerApplicationContext is used"). With both, Spring MVC is used.
     */
    static boolean webfluxOnly(RuleContext ctx) {
        return ctx.a.project.probe().hasClass("org.springframework.web.reactive.DispatcherHandler")
                && !ctx.a.project.probe().hasClass("org.springframework.web.servlet.DispatcherServlet");
    }

    /** What a failed {@code @Valid} body raises: MVC's MethodArgumentNotValidException, WebFlux's WebExchangeBindException. */
    private String validationException() {
        return webflux ? "WebExchangeBindException" : "MethodArgumentNotValidException";
    }

    void run() {
        if (ctx.enabled(Rules.TX_COMMITS_ON_CHECKED)) {
            commitsOnChecked();   // not about HTTP: any transactional method
        }
        boolean any = ctx.enabled(Rules.ERROR_DETAILS_EXPOSED) || ctx.enabled(Rules.UNMAPPED_EXCEPTION)
                || ctx.enabled(Rules.CLIENT_ERROR_AS_SERVER_ERROR) || ctx.enabled(Rules.PROBLEM_DETAILS_DISABLED)
                || ctx.enabled(Rules.ERROR_RESPONSE_UNTRACED) || ctx.enabled(Rules.UNEXPECTED_ERROR_UNLOGGED)
                || ctx.enabled(Rules.ERROR_HANDLER_STATUS_OK) || ctx.enabled(Rules.REQUEST_NOT_VALIDATED);
        if (!any || ctx.a.entrypoints.stream().noneMatch(ep -> ep.kind() == Entrypoint.Kind.HTTP)) {
            return;
        }
        for (MethodInfo m : ctx.methods()) {
            if (m.hasAnnotation(EXCEPTION_HANDLER)) {
                handlers.add(new Handler(m, handledTypes(m)));
            }
        }
        for (LibraryAdvice advice : ctx.a.project.probe().controllerAdvice()) {
            if (advice.global() && ctx.a.index.clazz(advice.className()) == null) {   // a source class is read from source
                advice.handled().forEach(t -> libraryHandled.add(JavaType.ShallowClass.build(t)));
                libraryAnswersMvcErrors |= REEH.equals(advice.superName()) || REACTIVE_REEH.equals(advice.superName());
            }
        }
        if (ctx.enabled(Rules.ERROR_DETAILS_EXPOSED)) {
            exposedByConfig();
            handlers.forEach(this::exposedByHandler);
        }
        List<String[]> validated = new ArrayList<>();
        List<String[]> unvalidated = new ArrayList<>();
        requestBodies(validated, unvalidated);
        Handler validationTo500 = validationAnsweredBy500();
        if (ctx.enabled(Rules.CLIENT_ERROR_AS_SERVER_ERROR)) {
            handlers.forEach(this::clientErrorAs500);
            if (validationTo500 != null && !validated.isEmpty()) {
                validationAs500(validationTo500, validated);
            }
        }
        if (ctx.enabled(Rules.REQUEST_NOT_VALIDATED)) {
            unvalidated.forEach(u -> notValidated(u, validationTo500));
        }
        if (ctx.enabled(Rules.UNEXPECTED_ERROR_UNLOGGED)) {
            handlers.forEach(this::unlogged);
        }
        if (ctx.enabled(Rules.ERROR_HANDLER_STATUS_OK)) {
            handlers.forEach(this::statusOk);
        }
        if (ctx.enabled(Rules.UNMAPPED_EXCEPTION)) {
            unmapped();
        }
        if (ctx.enabled(Rules.PROBLEM_DETAILS_DISABLED)) {
            problemDetails();
        }
        if (ctx.enabled(Rules.ERROR_RESPONSE_UNTRACED)) {
            untraced();
        }
    }

    /**
     * Micrometer Tracing and Spring MVC (a ResponseBodyAdvice) or, on a WebFlux-only service, Spring Framework 6.2+'s
     * ErrorResponse.Interceptor (registered through WebFluxConfigurer; Framework reference, WebFlux › Error Responses ›
     * Render) are there: an error response can carry the trace id.
     */
    static boolean traceableErrors(RuleContext ctx) {
        var probe = ctx.a.project.probe();
        return probe.hasClass("io.micrometer.tracing.Tracer")
                && (webfluxOnly(ctx) ? probe.hasClass("org.springframework.web.ErrorResponse$Interceptor")
                : probe.hasClass("org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice"))
                && ctx.a.entrypoints.stream().anyMatch(ep -> ep.kind() == Entrypoint.Kind.HTTP);
    }

    /**
     * ERROR_RESPONSE_UNTRACED is on and finds something to switch on. #291: never, any more: the trace id in every error
     * body changes what callers see (and may take a field name the service already fills), so it is proposed and the
     * team switches it on; nothing here needs the support starter.
     */
    static boolean untracedFixable(RuleContext ctx) {
        return false;
    }

    /**
     * ERROR_RESPONSE_UNTRACED: error responses without the trace id. A client that reports an error has nothing that
     * leads to its log lines; with {@code traceId} in every ProblemDetail (and Spring Boot's error map) it does. #291:
     * a proposal until the service sets logging-support.problem-trace-id.enabled itself (true or false: decided).
     */
    private void untraced() {
        if (!ctx.enabled(Rules.ERROR_RESPONSE_UNTRACED) || !traceableErrors(ctx)
                || ctx.a.springProperties.containsKey("logging-support.problem-trace-id.enabled")) {
            return;
        }
        ctx.order(Rules.ERROR_RESPONSE_UNTRACED, "problem-trace-id").at(ServiceYaml.path(ctx), 1)
                .title("Error responses do not carry the trace id" + (webflux ? " (Spring WebFlux)" : "")
                        + ": a client reporting an error has nothing that leads to its logs")
                .suggestion("Proposed: logging-support.problem-trace-id.enabled: true (the logging-support starter adds \"traceId\" to "
                        + (webflux ? "every ProblemDetail response through an ErrorResponse.Interceptor" : "every ProblemDetail response and to Spring Boot's error map")
                        + "; a traceId a handler already set is kept). It changes what callers see, so logast does not switch it on: "
                        + "check that no response already uses a \"traceId\" field for something else (a correlation id), then set it "
                        + "in application.yml (false: decided against, no longer reported).");
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
                        + "clients: internals (SQL, class names, paths) leak in error responses");
        // the message is part of the response body clients may read (as PROBLEM_DETAILS_DISABLED, a change for a person
        // to decide); stack traces and exception class names are not a contract (#47)
        boolean message = fix.remove("include-message") != null;
        String messageNote = message ? " include-message is left as it is: clients may rely on the message in the body; "
                + "set server.error.include-message: never once they don't." : "";
        if (fix.isEmpty()) {
            b.suggestion("Decide on include-message: clients may rely on the message in the error body. Once they don't, set "
                    + "server.error.include-message: never; the details stay in the logs, correlated by trace id.");
            return;
        }
        b.suggestion("Sets " + String.join(", ", fix.keySet()) + " to never/false; the details stay in the logs, correlated by "
                + "trace id." + messageNote);
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

    // ---- request bodies without @Valid ----------------------------------------------------------

    private static final String REEH = "org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler";
    /** WebFlux's ResponseEntityExceptionHandler (Framework reference, WebFlux › Error Responses). */
    private static final String REACTIVE_REEH = "org.springframework.web.reactive.result.method.annotation.ResponseEntityExceptionHandler";
    private static final List<String> BODY = List.of("org.springframework.web.bind.annotation.RequestBody",
            "org.springframework.web.bind.annotation.ModelAttribute");
    private static final List<String> VALID = List.of("jakarta.validation.Valid", "javax.validation.Valid",
            "org.springframework.validation.annotation.Validated");
    private static final List<String> CONSTRAINT_PACKAGES = List.of("jakarta.validation.constraints.",
            "javax.validation.constraints.", "org.hibernate.validator.constraints.");
    /** The types a handler may name for what a failed {@code @Valid} on a body or model attribute raises. */
    private static final Set<String> MANVE_TYPES = Set.of("org.springframework.web.bind.MethodArgumentNotValidException",
            "org.springframework.validation.BindException", "org.springframework.web.bind.support.WebExchangeBindException");

    /**
     * {@code @RequestBody} / {@code @ModelAttribute} parameters of HTTP entrypoints: {file, line, method label, param,
     * type} of those with {@code @Valid} / {@code @Validated} (on the method or the interface method it implements:
     * Spring merges parameter annotations from interfaces), and of those without whose type carries constraints.
     */
    private void requestBodies(List<String[]> validated, List<String[]> unvalidated) {
        Set<String> seen = new HashSet<>();
        for (Entrypoint ep : ctx.a.entrypoints) {
            MethodInfo m = ep.method();
            if (ep.kind() != Entrypoint.Kind.HTTP || !seen.add(m.key())) {
                continue;
            }
            List<MethodInfo> declared = new ArrayList<>(List.of(m));
            declared.addAll(ctx.a.index.overridden(m));
            List<Statement> params = m.decl().getParameters();
            for (int i = 0; i < params.size(); i++) {
                if (!(params.get(i) instanceof J.VariableDeclarations vd) || vd.getVariables().isEmpty()) {
                    continue;
                }
                List<J.Annotation> annotations = new ArrayList<>();
                for (MethodInfo d : declared) {
                    if (d.decl().getParameters().size() == params.size() && d.decl().getParameters().get(i) instanceof J.VariableDeclarations v) {
                        annotations.addAll(v.getLeadingAnnotations());
                    }
                }
                if (annotations.stream().noneMatch(a -> BODY.stream().anyMatch(b -> Annotations.is(a, b)))) {
                    continue;
                }
                JavaType.FullyQualified type = TypeUtils.asFullyQualified(vd.getType());
                String[] site = {m.file().relPath(), String.valueOf(Pos.line(vd)), m.label(),
                        vd.getVariables().getFirst().getSimpleName(), type == null ? "?" : type.getClassName()};
                if (annotations.stream().anyMatch(a -> VALID.stream().anyMatch(v -> Annotations.is(a, v)))) {
                    validated.add(site);
                } else if (type != null && constrained(type.getFullyQualifiedName(), new HashSet<>())) {
                    unvalidated.add(site);
                }
            }
        }
    }

    /** A source class with Bean Validation constraints on it, its fields / record components / getters, or a supertype. */
    private boolean constrained(String fqn, Set<String> visited) {
        ClassInfo c = ctx.a.index.clazz(fqn);
        if (c == null || !visited.add(fqn)) {
            return false;
        }
        boolean[] found = {false};
        new JavaIsoVisitor<Integer>() {
            @Override
            public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration cd, Integer p) {
                return cd == c.decl() ? super.visitClassDeclaration(cd, p) : cd;   // nested classes are their own types
            }

            @Override
            public J.Annotation visitAnnotation(J.Annotation a, Integer p) {
                found[0] |= constraint(a);
                return a;
            }
        }.visit(c.decl(), 0, new Cursor(null, Cursor.ROOT_VALUE));
        return found[0] || ctx.a.index.sourceSupertypes(c).stream().anyMatch(s -> constrained(s.type().getFullyQualifiedName(), visited));
    }

    /** A built-in constraint, or one of the service's own (an annotation type meta-annotated with @Constraint). */
    private static boolean constraint(J.Annotation a) {
        JavaType.FullyQualified t = TypeUtils.asFullyQualified(a.getType());
        if (t == null || a.getType() instanceof JavaType.Unknown) {
            return false;
        }
        return CONSTRAINT_PACKAGES.stream().anyMatch(p -> t.getFullyQualifiedName().startsWith(p))
                || t.getAnnotations().stream().anyMatch(m -> m.getFullyQualifiedName().equals("jakarta.validation.Constraint")
                || m.getFullyQualifiedName().equals("javax.validation.Constraint"));
    }

    /**
     * The handler a failed {@code @Valid} ends in when it answers 500: Spring MVC's DefaultHandlerExceptionResolver
     * answers MethodArgumentNotValidException with 400, but it runs after the {@code @ExceptionHandler} methods
     * (WebMvcConfigurationSupport: ExceptionHandlerExceptionResolver, then ResponseStatusExceptionResolver, then
     * DefaultHandlerExceptionResolver), so a catch-all handler for Exception / Throwable takes it first. Null when a
     * handler names it (or BindException), an advice extends ResponseEntityExceptionHandler, or no catch-all answers 500.
     */
    private Handler validationAnsweredBy500() {
        if (libraryAnswersMvcErrors || libraryHandled.stream().anyMatch(t -> MANVE_TYPES.contains(t.getFullyQualifiedName()))) {
            return null;
        }
        Handler catchAll = null;
        for (Handler h : handlers) {
            if (h.types().stream().anyMatch(t -> MANVE_TYPES.contains(t.getFullyQualifiedName()))
                    || TypeUtils.isAssignableTo(REEH, h.method().owner().type())
                    || TypeUtils.isAssignableTo(REACTIVE_REEH, h.method().owner().type())) {
                return null;
            }
            if (catchAll == null && h.types().stream().anyMatch(t -> t.getFullyQualifiedName().equals("java.lang.Exception")
                    || t.getFullyQualifiedName().equals("java.lang.Throwable")) && answers500(h.method())) {
                catchAll = h;
            }
        }
        return catchAll;
    }

    /** The handler's code answers 500 (comments left out). */
    private static boolean answers500(MethodInfo m) {
        String text = m.file().slice(Pos.start(m.decl()), Pos.end(m.decl()))
                .replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\\n]*", "");
        return text.contains("INTERNAL_SERVER_ERROR") || text.matches("(?s).*status\\(\\s*500\\s*\\).*") || text.contains("valueOf(500)");
    }

    /** CLIENT_ERROR_AS_SERVER_ERROR for validation: request bodies are validated, and the catch-all answers their failures 500. */
    private void validationAs500(Handler h, List<String[]> validated) {
        MethodInfo m = h.method();
        if (ctx.ignored(m.file(), m.line(), Rules.CLIENT_ERROR_AS_SERVER_ERROR)) {
            return;
        }
        String[] first = validated.getFirst();
        ctx.order(Rules.CLIENT_ERROR_AS_SERVER_ERROR, m, m.line(), "validation")
                .title("Handler for " + names(h.types()) + " also takes the validation failures of " + validated.size()
                        + " @Valid request bod" + (validated.size() == 1 ? "y" : "ies") + " (e.g. " + first[3] + " in "
                        + first[2] + ") and answers 500: a client's bad input is reported as a server failure")
                .suggestion("Map " + validationException() + " to 400 (an @ExceptionHandler returning a ProblemDetail with "
                        + "the field errors, or extend ResponseEntityExceptionHandler): @ExceptionHandler methods run before "
                        + "Spring's default 400 answer.")
                .doneWhen("an @ExceptionHandler names " + validationException() + " or BindException, or an advice extends "
                        + "ResponseEntityExceptionHandler");
    }

    /**
     * REQUEST_NOT_VALIDATED (report): Bean Validation runs on a {@code @RequestBody} / {@code @ModelAttribute} only with
     * {@code @Valid} or {@code @Validated} (Spring Framework reference, Web MVC Validation). Without it the constraints on
     * the type are decoration: bad input passes and fails deeper, often as a 500. Not applied automatically: adding it
     * changes which requests are accepted (and what the client gets for a rejected one).
     */
    private void notValidated(String[] site, Handler validationTo500) {
        int line = Integer.parseInt(site[1]);
        SourceFileInfo f = ctx.a.index.methods().stream().map(MethodInfo::file).filter(x -> x.relPath().equals(site[0]))
                .findFirst().orElse(null);
        if (f == null || ctx.ignored(f, line, Rules.REQUEST_NOT_VALIDATED)) {
            return;
        }
        String answer = validationTo500 != null
                ? " Map " + validationException() + " to 400 first: the catch-all " + validationTo500.method().label()
                        + " would answer the rejected requests with 500."
                : " A rejected request gets 400 (" + validationException() + ").";
        boolean provider = ctx.a.project.probe().hasClass("org.hibernate.validator.HibernateValidator")
                || ctx.a.project.probe().hasClass("org.apache.bval.jsr.ApacheValidationProvider");
        String noProvider = provider ? "" : " No Bean Validation provider is on the classpath (spring-boot-starter-validation): "
                + "without one, @Valid checks nothing either.";
        ctx.order(Rules.REQUEST_NOT_VALIDATED, "not-validated|" + site[2] + "|" + site[3]).at(site[0], line)
                .title(site[2] + " takes " + site[3] + " (" + site[4] + ", with Bean Validation constraints) without @Valid: "
                        + "the constraints are never checked, bad input passes into the service")
                .suggestion("Decide whether the constraints apply: @Valid on the parameter rejects invalid requests before "
                        + "the method runs. Not applied automatically: it changes which requests are accepted." + answer + noProvider)
                .doneWhen("the parameter (or the interface method's) has @Valid or @Validated, or its type has no constraints");
    }

    // ---- unexpected errors nobody logs ------------------------------------------------------------

    /** Catch-all types: what a handler for "anything else" names. */
    private static final Set<String> CATCH_ALL = Set.of("java.lang.Exception", "java.lang.RuntimeException", "java.lang.Throwable");

    /**
     * UNEXPECTED_ERROR_UNLOGGED: a handler for Exception / RuntimeException / Throwable that does not log. Once a handler
     * resolves the exception, Spring MVC writes no stack trace (AbstractHandlerExceptionResolver: "By default there is
     * no warn logging"), so the failure leaves no trace anywhere. AUTO: an ERROR line with the exception at the top.
     */
    private void unlogged(Handler h) {
        MethodInfo m = h.method();
        if (m.decl().getBody() == null || h.types().stream().noneMatch(t -> CATCH_ALL.contains(t.getFullyQualifiedName()))
                || !m.facts().logs.isEmpty()) {
            return;
        }
        String[] exVar = {null};
        for (Statement p : m.decl().getParameters()) {
            if (exVar[0] == null && p instanceof J.VariableDeclarations vd && TypeUtils.isAssignableTo("java.lang.Throwable", vd.getType())
                    && !vd.getVariables().isEmpty()) {
                exVar[0] = vd.getVariables().getFirst().getSimpleName();
            }
        }
        // delegates (super.handleException(…), a helper that gets the exception and may log) or rethrows: decided elsewhere
        boolean[] passesOn = {false};
        new JavaIsoVisitor<Integer>() {
            @Override
            public J.MethodInvocation visitMethodInvocation(J.MethodInvocation mi, Integer p) {
                passesOn[0] |= mi.getSelect() instanceof J.Identifier id && id.getSimpleName().equals("super");
                passesOn[0] |= exVar[0] != null && mi.getArguments().stream().anyMatch(a -> a instanceof J.Identifier id
                        && id.getSimpleName().equals(exVar[0]));
                return super.visitMethodInvocation(mi, p);
            }

            @Override
            public J.Throw visitThrow(J.Throw t, Integer p) {
                passesOn[0] = true;
                return t;
            }
        }.visit(m.decl().getBody(), 0);
        if (passesOn[0] || ctx.ignored(m.file(), m.line(), Rules.UNEXPECTED_ERROR_UNLOGGED)) {
            return;
        }
        OrderBuilder b = ctx.order(Rules.UNEXPECTED_ERROR_UNLOGGED, m, m.line(), "unlogged")
                .title("Handler for unexpected " + names(h.types()) + " answers without logging the exception: Spring logs "
                        + "nothing for a handled exception, so the failure leaves no stack trace anywhere")
                .doneWhen("the handler logs (with the exception), rethrows it or passes it on");
        JavaEdits.LoggerPlan logger = ctx.logger(m);
        SourceFileInfo f = m.file();
        String text = f.text();
        int open = Pos.start(m.decl().getBody());
        if (exVar[0] == null || logger.name() == null || !Text.blankOrComment(text, open + 1, Text.lineEnd(text, open))) {
            b.suggestion("Log the exception at ERROR at the top of the handler (log.error(\"…\", e), taking the exception as a "
                    + "parameter): the response only carries a trace id, the log has the cause.");
            return;
        }
        List<Statement> body = m.decl().getBody().getStatements();
        String indent = body.isEmpty() ? Text.indentAt(text, Pos.start(m.decl())) + Text.indentUnit(text)
                : Text.indentAt(text, Pos.start(body.getFirst()));
        String call = indent + logger.name() + ".error(" + Text.javaString("Request failed unexpectedly") + ", "
                + exVar[0] + "); " + DbRules.WORDING_MARKER + "\n";
        b.edit(Edit.insert(f.relPath(), Text.nextLine(text, open), call));
        b.edits(logger.edits());
        b.suggestion("Adds an ERROR line with the exception at the top of the handler; the wording is marked "
                + DbRules.WORDING_MARKER + ".");
    }

    // ---- error handlers that answer 200 -----------------------------------------------------------

    private static final String RESPONSE_STATUS = "org.springframework.web.bind.annotation.ResponseStatus";
    /** Return types whose value carries its own status (ProblemDetail, ErrorResponse) or that render a view. */
    private static final List<String> STATUS_CARRIERS = List.of("org.springframework.http.ProblemDetail",
            "org.springframework.web.ErrorResponse", "org.springframework.web.servlet.ModelAndView", "org.springframework.web.servlet.View");
    /** Parameters through which a handler writes the response itself: Servlet's and WebFlux's (#102). */
    private static final List<String> RESPONSE_WRITERS = List.of("jakarta.servlet.ServletResponse", "java.io.OutputStream",
            "org.springframework.web.server.ServerWebExchange", "org.springframework.http.server.reactive.ServerHttpResponse");

    /**
     * ERROR_HANDLER_STATUS_OK (report): an {@code @ExceptionHandler} whose answer sets no status: no {@code @ResponseStatus}
     * on it or its class, a body object (or {@code ResponseEntity.ok(…)}) rather than a ProblemDetail / ErrorResponse
     * ("The status property of ProblemDetail determines the HTTP status"), no response parameter. The client gets 200.
     */
    private void statusOk(Handler h) {
        MethodInfo m = h.method();
        J.MethodDeclaration d = m.decl();
        if (d.getBody() == null || m.hasAnnotation(RESPONSE_STATUS) || m.owner().decl().getLeadingAnnotations().stream()
                .anyMatch(a -> Annotations.is(a, RESPONSE_STATUS))) {
            return;
        }
        for (Statement p : d.getParameters()) {
            if (p instanceof J.VariableDeclarations vd && RESPONSE_WRITERS.stream().anyMatch(w -> TypeUtils.isAssignableTo(w, vd.getType()))) {
                return;   // writes the response itself
            }
        }
        JavaType rt = d.getReturnTypeExpression() == null ? null : d.getReturnTypeExpression().getType();
        JavaType.FullyQualified declared = TypeUtils.asFullyQualified(rt);
        // WebFlux (#102): a Mono<T> handler answers what T answers (a Mono<ProblemDetail> carries its status)
        JavaType.FullyQualified type = declared instanceof JavaType.Parameterized pt
                && pt.getFullyQualifiedName().equals("reactor.core.publisher.Mono") && pt.getTypeParameters().size() == 1
                ? TypeUtils.asFullyQualified(pt.getTypeParameters().getFirst()) : declared;
        if (statuslessProblem(h)) {
            return;
        }
        if (type == null || type instanceof JavaType.Unknown || STATUS_CARRIERS.stream().anyMatch(c -> TypeUtils.isAssignableTo(c, type))) {
            return;   // void (view name / handled response) or a value that carries its status
        }
        String code = m.file().slice(Pos.start(d.getBody()), Pos.end(d.getBody()))
                .replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\\n]*", "");
        String how;
        if (TypeUtils.isAssignableTo("org.springframework.http.ResponseEntity", type)) {
            if (!code.contains("ResponseEntity.ok(") && !code.matches("(?s).*HttpStatus\\.OK\\b.*")) {
                return;   // the status is set in code
            }
            how = "answers ResponseEntity.ok / HttpStatus.OK";
        } else if (type.getFullyQualifiedName().equals("java.lang.String") && !restful(m)) {
            return;   // a view name: the error page is a template decision
        } else {
            how = "returns " + type.getClassName() + " without @ResponseStatus";
        }
        if (ctx.ignored(m.file(), m.line(), Rules.ERROR_HANDLER_STATUS_OK)) {
            return;
        }
        ctx.order(Rules.ERROR_HANDLER_STATUS_OK, m, m.line(), "status-ok")
                .title("Handler for " + names(h.types()) + " " + how + ": the client gets 200 for a failure "
                        + "(clients and monitoring count it as a success)")
                .suggestion("Decide its status: @ResponseStatus(HttpStatus.…) on the handler, a ResponseEntity with that status, "
                        + "or a ProblemDetail.forStatus(…) (its status becomes the HTTP status).")
                .doneWhen("the handler (or its class) has @ResponseStatus, writes the response itself, or returns a "
                        + "ProblemDetail, an ErrorResponse or a ResponseEntity with an error status");
    }

    /**
     * ERROR_HANDLER_STATUS_OK for a ProblemDetail subclass built without a status: its no-arg constructor leaves the
     * status 0, and Spring builds the response with {@code ResponseEntity.of(detail)} ("The status property of
     * ProblemDetail determines the HTTP status"), which rejects 0 ({@code HttpStatusCode.valueOf}: "should be a
     * three-digit positive integer"). The handler fails, ExceptionHandlerExceptionResolver logs "Failure in
     * {@code @ExceptionHandler}" and continues with the original exception: the client gets the default answer
     * (usually 500) instead of this body. True when reported (or suppressed).
     */
    private boolean statuslessProblem(Handler h) {
        MethodInfo m = h.method();
        String handlerCode = m.file().slice(Pos.start(m.decl().getBody()), Pos.end(m.decl().getBody()));
        if (handlerCode.contains("setStatus(")) {
            return false;
        }
        J.NewClass[] hit = {null};
        new JavaIsoVisitor<Integer>() {
            @Override
            public J.NewClass visitNewClass(J.NewClass nc, Integer p) {
                JavaType.FullyQualified t = TypeUtils.asFullyQualified(nc.getType());
                if (hit[0] == null && t != null && !t.getFullyQualifiedName().equals("org.springframework.http.ProblemDetail")
                        && TypeUtils.isAssignableTo("org.springframework.http.ProblemDetail", t) && nc.getBody() == null
                        && ctx.a.index.clazz(t.getFullyQualifiedName()) != null && !setsStatus(nc)) {
                    hit[0] = nc;
                }
                return super.visitNewClass(nc, p);
            }
        }.visit(m.decl().getBody(), 0);
        if (hit[0] == null) {
            return false;
        }
        int line = Pos.line(hit[0]);
        if (ctx.ignored(m.file(), line, Rules.ERROR_HANDLER_STATUS_OK)) {
            return true;
        }
        String cls = TypeUtils.asFullyQualified(hit[0].getType()).getClassName();
        ctx.order(Rules.ERROR_HANDLER_STATUS_OK, m, line, "status-missing")
                .title("Handler for " + names(h.types()) + " returns " + cls + ", a ProblemDetail built without a status: Spring "
                        + "cannot answer status 0, the handler fails and the client gets the default answer (usually 500) "
                        + "instead of this body")
                .suggestion("Give it a status: super(HttpStatus.….value()) in " + cls + "'s constructor, or setStatus(…) before "
                        + "returning it (its status becomes the HTTP status).")
                .doneWhen("the constructor passes a status to ProblemDetail (super(status) or this(…)), or the class or the "
                        + "handler calls setStatus")
                .context(ctx.context(m, Pos.start(hit[0]), Text.oneLine(hit[0].printTrimmed(), 160), null));
        return true;
    }

    /**
     * The ProblemDetail subclass's constructor called here passes a status on: {@code super(…)} with arguments or
     * {@code this(…)} in it, or {@code setStatus} anywhere in the class (field / initializer / constructor).
     */
    private boolean setsStatus(J.NewClass nc) {
        JavaType.FullyQualified t = TypeUtils.asFullyQualified(nc.getType());
        ClassInfo c = ctx.a.index.clazz(t.getFullyQualifiedName());
        String classCode = c.file().slice(Pos.start(c.decl()), Pos.end(c.decl()));
        if (classCode.contains("setStatus(")) {
            return true;
        }
        List<MethodInfo> ctors = nc.getConstructorType() == null ? List.of() : ctx.a.index.resolve(nc.getConstructorType());
        for (MethodInfo ctor : ctors) {
            if (ctor.decl().getBody() == null) {
                continue;
            }
            String code = ctor.file().slice(Pos.start(ctor.decl().getBody()), Pos.end(ctor.decl().getBody()))
                    .replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\\n]*", "");
            if (code.matches("(?s).*\\bthis\\s*\\(.*") || code.matches("(?s).*\\bsuper\\s*\\(\\s*[^)\\s].*")) {
                return true;
            }
        }
        return false;   // the implicit constructor, or one that calls super() without a status
    }

    /** The handler writes its return value as the body: @ResponseBody on it or its class (@RestController / @RestControllerAdvice). */
    private static boolean restful(MethodInfo m) {
        return m.hasAnnotation("org.springframework.web.bind.annotation.ResponseBody")
                || m.owner().decl().getLeadingAnnotations().stream().anyMatch(a -> Annotations.is(a, "org.springframework.web.bind.annotation.ResponseBody")
                || Annotations.is(a, "org.springframework.web.bind.annotation.RestController")
                || Annotations.is(a, "org.springframework.web.bind.annotation.RestControllerAdvice"));
    }

    // ---- checked exceptions that commit -----------------------------------------------------------

    private static final List<String> TRANSACTIONAL = List.of("org.springframework.transaction.annotation.Transactional",
            "jakarta.transaction.Transactional");
    /** Rollback rules on the annotation (Spring's and JTA's): the rollback behaviour is a decision already made. */
    private static final Set<String> ROLLBACK_RULES = Set.of("rollbackFor", "rollbackForClassName", "noRollbackFor",
            "noRollbackForClassName", "rollbackOn", "dontRollbackOn");

    /**
     * TX_COMMITS_ON_CHECKED (report): "Checked exceptions that are thrown from a transactional method do not result in a
     * rollback in the default configuration" (Spring Framework reference, Rolling Back a Declarative Transaction). A
     * {@code @Transactional} method declaring a checked exception, without rollback rules, commits the work done before
     * the exception. Not reported once {@code @EnableTransactionManagement(rollbackOn = ALL_EXCEPTIONS)} (6.2) is set.
     */
    private void commitsOnChecked() {
        for (ClassInfo c : ctx.a.index.classes()) {
            for (J.Annotation a : c.decl().getLeadingAnnotations()) {
                if (Annotations.is(a, "org.springframework.transaction.annotation.EnableTransactionManagement")
                        && a.printTrimmed().contains("ALL_EXCEPTIONS")) {
                    return;
                }
            }
        }
        for (MethodInfo m : ctx.methods()) {
            J.MethodDeclaration d = m.decl();
            if (d.getBody() == null || d.hasModifier(J.Modifier.Type.Private) || d.getThrows() == null || m.owner().isInterface()) {
                continue;
            }
            // the method's annotation wins over the class's (attributes included)
            J.Annotation tx = transactional(d.getLeadingAnnotations());
            if (tx == null && d.hasModifier(J.Modifier.Type.Public) && !d.hasModifier(J.Modifier.Type.Static)) {
                tx = transactional(m.owner().decl().getLeadingAnnotations());
            }
            if (tx == null || tx.getArguments() != null && tx.getArguments().stream().anyMatch(arg -> arg instanceof J.Assignment as
                    && as.getVariable() instanceof J.Identifier id && ROLLBACK_RULES.contains(id.getSimpleName()))) {
                continue;
            }
            List<String> checked = new ArrayList<>();
            for (var t : d.getThrows()) {
                JavaType.FullyQualified fq = TypeUtils.asFullyQualified(t.getType());
                if (fq != null && !(fq instanceof JavaType.Unknown) && !TypeUtils.isAssignableTo("java.lang.RuntimeException", fq)
                        && !TypeUtils.isAssignableTo("java.lang.Error", fq)) {
                    checked.add(fq.getClassName());
                }
            }
            if (checked.isEmpty() || ctx.ignored(m.file(), m.line(), Rules.TX_COMMITS_ON_CHECKED)) {
                continue;
            }
            ctx.order(Rules.TX_COMMITS_ON_CHECKED, m, m.line(), "tx-checked")
                    .title(m.label() + " is @Transactional and throws " + String.join(", ", checked) + ": a checked exception "
                            + "does not roll back by default, so the work done before it is committed")
                    .suggestion("Decide what a failure means: rollbackFor = " + checked.getFirst() + ".class (or Exception.class) "
                            + "on @Transactional, or noRollbackFor if committing is intended. Not applied automatically: it "
                            + "changes what is committed.")
                    .doneWhen("its @Transactional has rollbackFor / noRollbackFor (rollbackOn / dontRollbackOn for JTA), it "
                            + "throws no checked exception, or @EnableTransactionManagement(rollbackOn = ALL_EXCEPTIONS) is set");
        }
    }

    private static J.Annotation transactional(List<J.Annotation> annotations) {
        return annotations.stream().filter(a -> TRANSACTIONAL.stream().anyMatch(t -> Annotations.is(a, t))).findFirst().orElse(null);
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
        Map<Downstream, List<String[]>> downstreamSites = new EnumMap<>(Downstream.class);
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
                    // the thrown expression's type: throw new X(…), a factory (throw Errors.notFound(id)) or a variable
                    thrown(t.getException().getType(), t);
                    return super.visitThrow(t, p);
                }

                @Override
                public J.MethodInvocation visitMethodInvocation(J.MethodInvocation mi, Integer p) {
                    // optional.orElseThrow(() -> new X(id)) / orElseThrow(X::new): thrown by the call, not by a throw
                    if (mi.getSimpleName().equals("orElseThrow") && mi.getArguments().size() == 1) {
                        thrown(supplied(mi), mi);
                    }
                    // Mono.error(new X(id)) / Flux.error(() -> new X(id)): the error signal reaches the handlers like a throw (#103)
                    if (mi.getSimpleName().equals("error") && mi.getArguments().size() == 1 && reactive(mi)) {
                        Expression arg = mi.getArguments().getFirst();
                        if (arg instanceof J.Lambda l && l.getBody() instanceof Expression body) {
                            arg = body;
                        }
                        signalled(arg.getType(), mi);
                    }
                    Downstream family = downstream(mi);
                    boolean signalled = false;
                    if (family == null && webClientResponse(mi) && !laterInChain(getCursor(), BLOCK)) {
                        // retrieve().bodyToMono(X.class) returned to WebFlux: the request fails in the pipeline, and the
                        // WebClientResponseException reaches the handlers (#103; blocked on, block() counts it). An
                        // onErrorResume / onErrorMap / onErrorReturn on the chain handles it there; a try catches nothing.
                        family = laterInChain(getCursor(), ON_ERROR) ? null : Downstream.WEBCLIENT;
                        signalled = true;
                    }
                    if (family != null && (signalled || !caughtLocally(getCursor(), family::catches))) {
                        downstreamSites.computeIfAbsent(family, k -> new ArrayList<>())
                                .add(new String[]{m.file().relPath(), String.valueOf(Pos.line(mi)), m.label(), call(mi)});
                    }
                    return super.visitMethodInvocation(mi, p);
                }

                private void thrown(JavaType type, J at) {
                    if (TypeUtils.asFullyQualified(type) instanceof JavaType.FullyQualified x
                            && !caughtLocally(getCursor(), caught -> TypeUtils.isAssignableTo(caught, x))) {
                        signalled(x, at);
                    }
                }

                /** An error signal is not thrown: a try around it catches nothing. */
                private void signalled(JavaType type, J at) {
                    if (TypeUtils.asFullyQualified(type) instanceof JavaType.FullyQualified x
                            && ctx.a.index.clazz(x.getFullyQualifiedName()) != null) {
                        types.put(x.getFullyQualifiedName(), x);
                        throwsByType.computeIfAbsent(x.getFullyQualifiedName(), k -> new ArrayList<>())
                                .add(new String[]{m.file().relPath(), String.valueOf(Pos.line(at))});
                    }
                }
            }.visit(m.decl().getBody(), 0, new Cursor(new Cursor(null, Cursor.ROOT_VALUE), m.decl()));
        }
        throwsByType.forEach((fqn, sites) -> {
            JavaType.FullyQualified x = types.get(fqn);
            if (mapped(x)) {
                return;
            }
            boolean catchAll = allHandled().anyMatch(t -> GENERIC.contains(t.getFullyQualifiedName())
                    && TypeUtils.isAssignableTo(t.getFullyQualifiedName(), x));
            ClassInfo c = ctx.a.index.clazz(fqn);
            int line = Pos.line(c.decl());
            if (ctx.ignored(c.file(), line, Rules.UNMAPPED_EXCEPTION)) {
                return;
            }
            OrderBuilder b = ctx.order(Rules.UNMAPPED_EXCEPTION, "unmapped|" + fqn).at(c.file().relPath(), line)
                    .title(c.simpleName() + " is thrown on HTTP paths (" + sites.size() + "×) but no handler or @ResponseStatus maps it"
                            + (catchAll ? ": it ends in the catch-all handler" : ": the client gets a 500"))
                    .suggestion("Decide its status: @ResponseStatus(HttpStatus.CONFLICT) on the class, or an @ExceptionHandler returning "
                            + "a ProblemDetail (with a stable error code) in the controller advice.")
                    .doneWhen("@ResponseStatus on it or a supertype, it extends ResponseStatusException (or is an ErrorResponse), "
                            + "or an @ExceptionHandler names it or a supertype narrower than Exception / RuntimeException");
            sites.stream().limit(10).forEach(s -> b.location(s[0], Integer.parseInt(s[1]), Rules.UNMAPPED_EXCEPTION));
        });
        downstreamSites.forEach(this::downstreamUnmapped);
    }

    /**
     * Client libraries whose calls fail with unchecked exceptions: RestClient / RestTemplate / HTTP interfaces throw
     * RestClientException (RestClientResponseException for an error status, ResourceAccessException for I/O errors and
     * timeouts), Feign clients FeignException.
     */
    private enum Downstream {
        SPRING("RestClient / RestTemplate", "RestClientException", List.of("org.springframework.web.client.")),
        /** WebClient blocked on: WebClientResponseException (error status), WebClientRequestException (I/O, timeouts). */
        WEBCLIENT("WebClient", "WebClientException", List.of("org.springframework.web.reactive.function.client.")),
        FEIGN("Feign client", "FeignException", List.of("feign."));

        final String clients;
        final String exception;
        final List<String> packages;

        Downstream(String clients, String exception, List<String> packages) {
            this.clients = clients;
            this.exception = exception;
            this.packages = packages;
        }

        boolean names(JavaType.FullyQualified t) {
            return packages.stream().anyMatch(p -> t.getFullyQualifiedName().startsWith(p));
        }

        /** A catch of the client's exceptions, or of a supertype they share with everything else. */
        boolean catches(JavaType caught) {
            if (caught instanceof JavaType.MultiCatch mc) {
                return mc.getThrowableTypes().stream().anyMatch(this::catches);
            }
            JavaType.FullyQualified fq = TypeUtils.asFullyQualified(caught);
            return fq != null && (names(fq) || GENERIC.contains(fq.getFullyQualifiedName())
                    || fq.getFullyQualifiedName().equals("org.springframework.core.NestedRuntimeException"));
        }
    }

    private static final List<String> SPRING_CLIENTS = List.of("org.springframework.web.client.RestOperations");
    /** RestOperations' calls (RestTemplate's setters and getters are not). */
    private static final Set<String> REST_OPERATIONS = Set.of("getForObject", "getForEntity", "headForHeaders", "postForLocation",
            "postForObject", "postForEntity", "put", "patchForObject", "delete", "optionsForAllow", "exchange", "execute");

    private static final Set<String> BLOCK = Set.of("block", "blockOptional", "blockFirst", "blockLast");

    /** The call goes out to another service: which client family's exceptions it throws, else null. */
    private Downstream downstream(J.MethodInvocation mi) {
        JavaType.Method t = mi.getMethodType();
        if (t == null || t.getDeclaringType() == null) {
            return null;
        }
        String fqn = t.getDeclaringType().getFullyQualifiedName();
        if (BLOCK.contains(t.getName()) && (fqn.equals("reactor.core.publisher.Mono") || fqn.equals("reactor.core.publisher.Flux"))) {
            // webClient.get()…retrieve().bodyToMono(X.class).block(): the request runs (and fails) in block()
            for (Expression s = mi.getSelect(); s instanceof J.MethodInvocation in; s = in.getSelect()) {
                if (in.getMethodType() != null && in.getMethodType().getDeclaringType() != null && in.getMethodType().getDeclaringType()
                        .getFullyQualifiedName().startsWith("org.springframework.web.reactive.function.client.WebClient")) {
                    return Downstream.WEBCLIENT;
                }
            }
            return null;
        }
        if (fqn.equals("org.springframework.web.client.RestClient$ResponseSpec")
                || fqn.startsWith("org.springframework.web.client.RestClient$") && t.getName().startsWith("exchange")
                || TypeUtils.isAssignableTo(SPRING_CLIENTS.getFirst(), t.getDeclaringType()) && REST_OPERATIONS.contains(t.getName())) {
            return Downstream.SPRING;   // RestClient: the call happens in retrieve()'s terminal or exchange(); RestTemplate: any
        }
        ClassInfo iface = ctx.a.index.clazz(fqn);
        if (iface == null || !iface.isInterface()) {
            return null;
        }
        for (J.Annotation a : iface.decl().getLeadingAnnotations()) {
            if (Annotations.is(a, "org.springframework.cloud.openfeign.FeignClient")) {
                return Downstream.FEIGN;
            }
            if (Annotations.is(a, "org.springframework.web.service.annotation.HttpExchange")) {
                return Downstream.SPRING;
            }
        }
        return null;
    }

    /** Mono.error / Flux.error. */
    private static boolean reactive(J.MethodInvocation mi) {
        JavaType.Method t = mi.getMethodType();
        return t != null && t.getDeclaringType() != null && (t.getDeclaringType().getFullyQualifiedName().equals("reactor.core.publisher.Mono")
                || t.getDeclaringType().getFullyQualifiedName().equals("reactor.core.publisher.Flux"));
    }

    /** WebClient.ResponseSpec's terminals: the publisher that runs the request (bodyToMono, bodyToFlux, toEntity, …). */
    private static final Set<String> RESPONSE_SPEC = Set.of("bodyToMono", "bodyToFlux", "toEntity", "toEntityList", "toEntityFlux",
            "toBodilessEntity");

    private static boolean webClientResponse(J.MethodInvocation mi) {
        JavaType.Method t = mi.getMethodType();
        return t != null && t.getDeclaringType() != null && RESPONSE_SPEC.contains(t.getName())
                && t.getDeclaringType().getFullyQualifiedName().equals("org.springframework.web.reactive.function.client.WebClient$ResponseSpec");
    }

    private static final Set<String> ON_ERROR = Set.of("onErrorResume", "onErrorMap", "onErrorReturn", "onErrorComplete");

    /** One of these calls further along the same call chain (a block(): that call is counted already). */
    private static boolean laterInChain(Cursor c, Set<String> names) {
        Object current = c.getValue();
        for (Cursor p = c.getParentTreeCursor(); p.getValue() instanceof J.MethodInvocation outer && outer.getSelect() == current;
             p = p.getParentTreeCursor()) {
            if (names.contains(outer.getSimpleName())) {
                return true;
            }
            current = outer;
        }
        return false;
    }

    private static String call(J.MethodInvocation mi) {
        JavaType.Method t = mi.getMethodType();
        return (t == null ? "" : t.getDeclaringType().getClassName().replace('$', '.') + ".") + mi.getSimpleName();
    }

    /**
     * UNMAPPED_EXCEPTION for a downstream service's failures (#91): a client call on an HTTP path whose exceptions no
     * handler maps ends as our own 500, although the failure is theirs: 502 (bad gateway), 504 (timeout), or their 404
     * passed on deliberately. Report: the mapping is a decision.
     */
    private void downstreamUnmapped(Downstream family, List<String[]> sites) {
        if (allHandled().anyMatch(family::names)) {
            return;
        }
        boolean catchAll = allHandled().anyMatch(t -> GENERIC.contains(t.getFullyQualifiedName()));
        String[] first = sites.getFirst();
        int line = Integer.parseInt(first[1]);
        SourceFileInfo f = ctx.a.index.methods().stream().map(MethodInfo::file).filter(x -> x.relPath().equals(first[0]))
                .findFirst().orElse(null);
        if (f != null && ctx.ignored(f, line, Rules.UNMAPPED_EXCEPTION)) {
            return;
        }
        OrderBuilder b = ctx.order(Rules.UNMAPPED_EXCEPTION, "downstream|" + family.name()).at(first[0], line)
                .title("Failures of downstream calls (" + family.clients + ", " + sites.size() + "× on HTTP paths, e.g. "
                        + first[3] + " in " + first[2] + ") are not mapped" + (catchAll ? " and end in the catch-all handler"
                        : "") + ": the client gets a 500, as for our own bug, instead of 502 / 504")
                .suggestion("Map " + family.exception + " in the controller advice: an error status from the other service "
                        + "→ 502 Bad Gateway (or pass a 404 on deliberately), a timeout / I/O error → 504 Gateway Timeout, as "
                        + "ProblemDetails naming the downstream service; log them with the downstream status.")
                .doneWhen("an @ExceptionHandler names " + family.exception + " or one of its subtypes, or every call catches them");
        sites.stream().skip(1).limit(10).forEach(s -> b.location(s[0], Integer.parseInt(s[1]), Rules.UNMAPPED_EXCEPTION));
    }

    /**
     * The exception an {@code orElseThrow(supplier)} throws: javac infers its type parameter from the supplier (a lambda,
     * {@code X::new}, a factory reference or a variable), and the attributed call declares it as thrown.
     */
    private static JavaType supplied(J.MethodInvocation mi) {
        JavaType.Method mt = mi.getMethodType();
        return mt != null && mt.getThrownExceptions().size() == 1 ? mt.getThrownExceptions().getFirst() : null;
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
        return allHandled().anyMatch(t -> !GENERIC.contains(t.getFullyQualifiedName())
                && TypeUtils.isAssignableTo(t.getFullyQualifiedName(), x));
    }

    /** The exception types every handler names: the sources' and the library advice's (#52). */
    private java.util.stream.Stream<JavaType.FullyQualified> allHandled() {
        return java.util.stream.Stream.concat(handlers.stream().flatMap(h -> h.types().stream()), libraryHandled.stream());
    }

    /** Thrown inside a try whose catch handles it right there (not from its catch or finally blocks: those propagate). */
    private static boolean caughtLocally(Cursor c, java.util.function.Predicate<JavaType> catches) {
        Object prev = c.getValue();
        for (Cursor p = c.getParent(); p != null; prev = p.getValue() instanceof J ? p.getValue() : prev, p = p.getParent()) {
            if (p.getValue() instanceof J.Try t && p.getParent() != null && (prev == t.getBody() || prev instanceof J.Try.Resource)) {
                for (J.Try.Catch k : t.getCatches()) {
                    JavaType caught = k.getParameter().getTree().getType();
                    if (caught instanceof JavaType.MultiCatch mc ? mc.getThrowableTypes().stream().anyMatch(catches) : catches.test(caught)) {
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

    /**
     * PROBLEM_DETAILS_DISABLED. Spring MVC: spring.mvc.problemdetails.enabled or an advice extending MVC's
     * ResponseEntityExceptionHandler. A WebFlux-only service (#102): spring.webflux.problemdetails.enabled (Boot 3.5 and
     * 4.x properties appendix, default false) or an advice extending WebFlux's ResponseEntityExceptionHandler
     * (org.springframework.web.reactive.result.method.annotation; Framework reference, WebFlux › Error Responses).
     */
    private void problemDetails() {
        String property = webflux ? "spring.webflux.problemdetails.enabled" : "spring.mvc.problemdetails.enabled";
        String handler = webflux ? REACTIVE_REEH : REEH;
        if ("true".equalsIgnoreCase(ctx.a.springProperties.getOrDefault(property, ""))) {
            return;
        }
        for (ClassInfo c : ctx.a.index.classes()) {
            if (TypeUtils.isAssignableTo(handler, c.type())) {
                return; // the advice already answers framework errors with ProblemDetail
            }
        }
        if (libraryAnswersMvcErrors) {
            return; // so does a library's (#52)
        }
        ctx.order(Rules.PROBLEM_DETAILS_DISABLED, "problemdetails").at(ServiceYaml.path(ctx), 1)
                .title((webflux ? "Spring WebFlux" : "Spring MVC") + " errors (400 bad input, 405, 415, ...) are not RFC 9457 "
                        + "ProblemDetail responses: every service answers them in its own shape")
                .suggestion("Set " + property + ": true (or extend " + (webflux ? "WebFlux's " : "") + "ResponseEntityExceptionHandler "
                        + "in the controller advice). Not applied automatically: it changes the error body API clients see.")
                .basis(property + " is " + Optional.ofNullable(ctx.a.springProperties.get(property))
                        .map(v -> "'" + v + "'").orElse("not set") + ", and no class extends ResponseEntityExceptionHandler")
                .doneWhen(property + ": true, or a class extends " + (webflux ? "WebFlux's " : "") + "ResponseEntityExceptionHandler");
    }

    private static String names(List<JavaType.FullyQualified> types) {
        return String.join(", ", types.stream().map(JavaType.FullyQualified::getClassName).toList());
    }
}
