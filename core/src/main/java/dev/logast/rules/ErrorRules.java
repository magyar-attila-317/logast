package dev.logast.rules;

import dev.logast.model.Annotations;
import dev.logast.model.ClassInfo;
import dev.logast.model.Entrypoint;
import dev.logast.model.LogCall;
import dev.logast.model.MethodInfo;
import dev.logast.orders.Edit;
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
 *   <li>PROBLEM_DETAILS_DISABLED: Spring MVC errors are not RFC 9457 ProblemDetail responses;</li>
 *   <li>UNEXPECTED_ERROR_UNLOGGED: a catch-all handler answers without logging the exception (no stack trace anywhere);</li>
 *   <li>ERROR_HANDLER_STATUS_OK: a handler whose answer carries no error status (the client gets 200);</li>
 *   <li>TX_COMMITS_ON_CHECKED: a {@code @Transactional} method that throws a checked exception commits by default.</li>
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
        if (ctx.enabled(Rules.TX_COMMITS_ON_CHECKED)) {
            commitsOnChecked();   // not about HTTP: any transactional method
        }
        boolean any = ctx.enabled(Rules.ERROR_DETAILS_EXPOSED) || ctx.enabled(Rules.UNMAPPED_EXCEPTION)
                || ctx.enabled(Rules.CLIENT_ERROR_AS_SERVER_ERROR) || ctx.enabled(Rules.PROBLEM_DETAILS_DISABLED)
                || ctx.enabled(Rules.ERROR_RESPONSE_UNTRACED) || ctx.enabled(Rules.UNEXPECTED_ERROR_UNLOGGED)
                || ctx.enabled(Rules.ERROR_HANDLER_STATUS_OK);
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

    /** Spring MVC and Micrometer Tracing are there: an error response can carry the trace id. */
    static boolean traceableErrors(RuleContext ctx) {
        return ctx.a.project.probe().hasClass("io.micrometer.tracing.Tracer")
                && ctx.a.project.probe().hasClass("org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice")
                && ctx.a.entrypoints.stream().anyMatch(ep -> ep.kind() == Entrypoint.Kind.HTTP);
    }

    /** ERROR_RESPONSE_UNTRACED is on and finds something: what makes the errors pack need the support starter (#87). */
    static boolean untracedFixable(RuleContext ctx) {
        return ctx.enabled(Rules.ERROR_RESPONSE_UNTRACED) && traceableErrors(ctx)
                && !"true".equalsIgnoreCase(ctx.a.springProperties.getOrDefault("logging-support.problem-trace-id.enabled", ""));
    }

    /**
     * ERROR_RESPONSE_UNTRACED: error responses without the trace id. A client that reports an error has nothing that
     * leads to its log lines; with {@code traceId} in every ProblemDetail (and Spring Boot's error map) it does.
     */
    private void untraced() {
        if (!untracedFixable(ctx)) {
            return;
        }
        OrderBuilder b = ctx.order(Rules.ERROR_RESPONSE_UNTRACED, "problem-trace-id").at(ServiceYaml.path(ctx), 1)
                .title("Error responses do not carry the trace id: a client reporting an error has nothing that leads to its logs")
                .suggestion("logging-support adds \"traceId\" to every ProblemDetail response and to Spring Boot's error map "
                        + "(logging-support.problem-trace-id.enabled: true); a traceId a handler already set is kept.");
        // the errors pack on its own in vendor mode: no set-up copies the support classes, so this rule copies the one it
        // needs (in dependency mode the set-up adds the starter for it, Rules.SUPPORT_SINGLE_CLASS)
        boolean supportComing = ctx.a.supportStarterPresent || ctx.supportOrderId != null || ctx.supportUpdateOrderId != null;
        if (!supportComing && !ctx.policy.support().vendor()) {
            b.suggestion(b.suggestion + " Needs the logging-support starter (" + ctx.policy.support().coordinates()
                    + ") as a dependency; not applied without it.");
            return;
        }
        if (!supportComing) {
            Edit copy = SetupRules.vendorOne(ctx, Rules.SUPPORT_SINGLE_CLASS.get(Rules.ERROR_RESPONSE_UNTRACED));
            if (copy != null) {
                b.edit(copy);
            }
        }
        ServiceYaml.merge(ctx, b, "logging-support:\n  problem-trace-id:\n    enabled: true\n");
        ctx.dependOnStarter(b);
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
            if (p instanceof J.VariableDeclarations vd && (TypeUtils.isAssignableTo("jakarta.servlet.ServletResponse", vd.getType())
                    || TypeUtils.isAssignableTo("java.io.OutputStream", vd.getType()))) {
                return;   // writes the response itself
            }
        }
        JavaType rt = d.getReturnTypeExpression() == null ? null : d.getReturnTypeExpression().getType();
        JavaType.FullyQualified type = TypeUtils.asFullyQualified(rt);
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
                    Downstream family = downstream(mi);
                    if (family != null && !caughtLocally(getCursor(), family::catches)) {
                        downstreamSites.computeIfAbsent(family, k -> new ArrayList<>())
                                .add(new String[]{m.file().relPath(), String.valueOf(Pos.line(mi)), m.label(), call(mi)});
                    }
                    return super.visitMethodInvocation(mi, p);
                }

                private void thrown(JavaType type, J at) {
                    if (TypeUtils.asFullyQualified(type) instanceof JavaType.FullyQualified x
                            && ctx.a.index.clazz(x.getFullyQualifiedName()) != null
                            && !caughtLocally(getCursor(), caught -> TypeUtils.isAssignableTo(caught, x))) {
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
        SPRING("RestClient / RestTemplate", "RestClientException", List.of("org.springframework.web.client.",
                "org.springframework.web.reactive.function.client.")),
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

    /** The call goes out to another service: which client family's exceptions it throws, else null. */
    private Downstream downstream(J.MethodInvocation mi) {
        JavaType.Method t = mi.getMethodType();
        if (t == null || t.getDeclaringType() == null) {
            return null;
        }
        String fqn = t.getDeclaringType().getFullyQualifiedName();
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
        if (handlers.stream().anyMatch(h -> h.types().stream().anyMatch(family::names))) {
            return;
        }
        boolean catchAll = handlers.stream().anyMatch(h -> h.types().stream().anyMatch(t -> GENERIC.contains(t.getFullyQualifiedName())));
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
        return handlers.stream().anyMatch(h -> h.types().stream().anyMatch(t -> !GENERIC.contains(t.getFullyQualifiedName())
                && TypeUtils.isAssignableTo(t.getFullyQualifiedName(), x)));
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
                        + "advice). Not applied automatically: it changes the error body API clients see.")
                .basis("spring.mvc.problemdetails.enabled is " + Optional.ofNullable(ctx.a.springProperties.get("spring.mvc.problemdetails.enabled"))
                        .map(v -> "'" + v + "'").orElse("not set") + ", and no class extends ResponseEntityExceptionHandler")
                .doneWhen("spring.mvc.problemdetails.enabled: true, or a class extends ResponseEntityExceptionHandler");
    }

    private static String names(List<JavaType.FullyQualified> types) {
        return String.join(", ", types.stream().map(JavaType.FullyQualified::getClassName).toList());
    }
}
