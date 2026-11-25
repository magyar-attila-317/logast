package dev.logast.rules;

import dev.logast.model.Entrypoint;
import dev.logast.model.MdcOp;
import dev.logast.policy.Policy;
import dev.logast.model.MethodInfo;
import dev.logast.orders.Edit;
import dev.logast.parse.Pos;
import dev.logast.parse.SourceFileInfo;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.Statement;
import org.openrewrite.java.tree.TypeUtils;

import java.util.*;

/**
 * MDC_NOT_CLOSEABLE: every {@code MDC.put} becomes {@code try (var mdc = MdcScope.of(k, v)) {...}} covering the
 * statements up to its matching remove (sibling statement or finally of a following try) or to the block end; a
 * {@code try (var mdc = MDC.putCloseable(k, v))} resource becomes {@code MdcScope.of(k, v)} (restores instead of removing).
 * MANUAL_TRACE_MDC: put/remove of trace keys that OpenTelemetry already maintains are deleted.
 * TRACE_KEY_HOLDS_APP_ID (#269): a trace key the service fills with its own value is renamed, not deleted.
 */
final class MdcRules {
    private final RuleContext ctx;
    private final Set<String> ambient;
    /** try statements already turned into one order (all their puts are handled together). */
    private final Set<J.Try> handledTries = Collections.newSetFromMap(new IdentityHashMap<>());
    /** MDC.clear() calls a conversion order already removes. */
    private final Set<J.MethodInvocation> handledClears = Collections.newSetFromMap(new IdentityHashMap<>());

    /** Trace keys the service fills with its own value (#269): not the tracer's, so never deleted as one. */
    private final Set<String> appOwned = new TreeSet<>();
    /** Trace keys set from a value logast cannot place (a parameter, a field): kept and reported, never deleted (#269). */
    private final Set<String> unsure = new TreeSet<>();

    MdcRules(RuleContext ctx) {
        this.ctx = ctx;
        this.ambient = new HashSet<>(ctx.policy.mdc().ambientKeys());
        classifyTraceKeys();
        this.ambient.removeAll(appOwned);
    }

    void run() {
        for (MethodInfo m : ctx.methods()) {
            if (!m.facts().mdc.isEmpty()) {
                method(m);
                clears(m);
                nestedKeys(m);
                unvalidatedInput(m);
            }
        }
        appOwnedKeys();
    }

    /** A read of what the caller sent: a request header, a query parameter, a cookie. */
    private static final java.util.regex.Pattern REQUEST_INPUT = java.util.regex.Pattern.compile(
            "\\.getHeader\\(|\\.getHeaders\\(\\)\\.getFirst\\(|\\.getFirstHeader\\(|\\.getParameter\\(|\\.getCookies\\(");
    /** Parameters Spring binds from the request headers or cookies. */
    private static final Set<String> INPUT_PARAMETERS = Set.of("RequestHeader", "CookieValue");

    /**
     * MDC_VALUE_UNVALIDATED (#278): an MDC value the caller chooses (a request header, a parameter, a cookie) goes into
     * every log line of the request as it was sent. A forged one can fake a correlation id, carry line breaks or a
     * huge payload into the logs. Reported when nothing checks it first: a pattern, a UUID parse, a length check or a
     * validating helper on the value (or a variable it came from) counts as a check.
     */
    private void unvalidatedInput(MethodInfo m) {
        if (!ctx.enabled(Rules.MDC_VALUE_UNVALIDATED) || m.decl().getBody() == null) {
            return;
        }
        String body = m.decl().getBody().printTrimmed();
        Set<String> inputParams = new HashSet<>();
        for (org.openrewrite.java.tree.Statement p : m.decl().getParameters()) {
            if (p instanceof J.VariableDeclarations vd && vd.getLeadingAnnotations().stream()
                    .anyMatch(a -> INPUT_PARAMETERS.contains(a.getSimpleName()))) {
                vd.getVariables().forEach(v -> inputParams.add(v.getSimpleName()));
            }
        }
        for (MdcOp op : m.facts().mdc) {
            if (!(op.kind() == MdcOp.Kind.PUT || op.kind() == MdcOp.Kind.PUT_CLOSEABLE) || valueOf(op) == null) {
                continue;
            }
            Expression value = valueOf(op);
            Set<String> names = new java.util.LinkedHashSet<>();
            if (!fromRequest(value.printTrimmed(), body, inputParams, names, 0)) {
                continue;
            }
            if (checked(value, body, names)) {
                continue;
            }
            int line = Pos.line(op.call());
            if (ctx.ignored(m.file(), line, Rules.MDC_VALUE_UNVALIDATED)) {
                continue;
            }
            String key = op.key() != null ? op.key() : Text.oneLine(op.keyExpr().printTrimmed(), 40);
            OrderBuilder b = ctx.order(Rules.MDC_VALUE_UNVALIDATED, m, line, "input:" + key);
            b.context(ctx.context(m, Pos.start(op.call()), Text.oneLine(op.call().printTrimmed(), 160), null));
            b.title("MDC key '" + key + "' is set from what the caller sent (" + Text.oneLine(value.printTrimmed(), 60)
                            + ") without a check")
                    .suggestion("Every log line of the request carries this value as sent: a forged one fakes the id, or "
                            + "brings line breaks or a long payload into the logs. Accept it only if it matches the expected "
                            + "form (a UUID, a pattern with a length limit) and generate one otherwise.");
        }
    }

    /** {@code text} reads request input, directly or through local variables assigned from it (collected in {@code names}). */
    private static boolean fromRequest(String text, String body, Set<String> inputParams, Set<String> names, int depth) {
        if (REQUEST_INPUT.matcher(text).find()) {
            return true;
        }
        if (depth > 3) {
            return false;
        }
        var ids = java.util.regex.Pattern.compile("\\b[a-zA-Z_][a-zA-Z0-9_]*\\b").matcher(text);
        while (ids.find()) {
            String id = ids.group();
            if (names.contains(id)) {
                continue;
            }
            if (inputParams.contains(id)) {
                names.add(id);
                return true;
            }
            var assigned = java.util.regex.Pattern.compile("\\b" + java.util.regex.Pattern.quote(id) + "\\s*=(?!=)\\s*([^;]+);").matcher(body);
            while (assigned.find()) {
                names.add(id);
                if (fromRequest(assigned.group(1), body, inputParams, names, depth + 1)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** A check of the value or a variable it came from: a pattern, a UUID parse, a length check, a validating helper. */
    private static boolean checked(Expression value, String body, Set<String> names) {
        if (value instanceof J.MethodInvocation mi && CHECKING.matcher(mi.getSimpleName()).find()) {
            return true;
        }
        for (String n : names) {
            String q = java.util.regex.Pattern.quote(n);
            if (java.util.regex.Pattern.compile("\\b" + q + "\\s*\\.\\s*(matches|length)\\s*\\(|"
                    + "(matcher|fromString|" + CHECKING.pattern() + ")[A-Za-z]*\\s*\\(\\s*" + q + "\\b").matcher(body).find()) {
                return true;
            }
        }
        return false;
    }

    private static final java.util.regex.Pattern CHECKING = java.util.regex.Pattern.compile(
            "(?i)valid|saniti|clean|normali|check|verify|safe|parse|trunc|abbreviate|limit");

    /** A value read from the tracer: OpenTelemetry's Span / SpanContext, Micrometer Tracing's Tracer / TraceContext. */
    private static final java.util.regex.Pattern FROM_TRACER = java.util.regex.Pattern.compile(
            "getTraceId\\(|getSpanId\\(|traceIdString\\(|context\\(\\)\\.(traceId|spanId)\\(|Span\\.current\\(|currentSpan\\(|currentTraceContext\\(");

    enum Origin { TRACER, SERVICE, UNKNOWN }

    /**
     * Where the value an MDC put sets comes from: the tracer (the expression, or what the local variable it names is
     * assigned), the service itself (a header, a generated id, any other expression), or not known here (a parameter,
     * a field: the caller decides).
     */
    static Origin origin(MethodInfo m, Expression value) {
        if (FROM_TRACER.matcher(value.printTrimmed()).find()) {
            return Origin.TRACER;
        }
        if (value instanceof J.FieldAccess) {
            return Origin.UNKNOWN;
        }
        if (value instanceof J.Identifier id) {
            if (m.decl().getBody() == null) {
                return Origin.UNKNOWN;
            }
            var assigned = java.util.regex.Pattern.compile("\\b" + java.util.regex.Pattern.quote(id.getSimpleName()) + "\\s*=(?!=)\\s*([^;]+);")
                    .matcher(m.decl().getBody().printTrimmed());
            boolean any = false;
            while (assigned.find()) {
                any = true;
                if (FROM_TRACER.matcher(assigned.group(1)).find()) {
                    return Origin.TRACER;
                }
            }
            return any ? Origin.SERVICE : Origin.UNKNOWN;
        }
        return Origin.SERVICE;
    }

    /** A trace key with any put of the service's own value is the service's; else one of unknown origin makes it unsure. */
    private void classifyTraceKeys() {
        for (MethodInfo m : ctx.methods()) {
            for (MdcOp op : m.facts().mdc) {
                if ((op.kind() == MdcOp.Kind.PUT || op.kind() == MdcOp.Kind.PUT_CLOSEABLE) && op.key() != null
                        && ambient.contains(op.key()) && valueOf(op) != null) {
                    switch (origin(m, valueOf(op))) {
                        case SERVICE -> appOwned.add(op.key());
                        case UNKNOWN -> unsure.add(op.key());
                        case TRACER -> { }
                    }
                }
            }
        }
        unsure.removeAll(appOwned);
    }

    /** MANUAL_TRACE_MDC may delete this trace-key op: the rule is on there and the key's value is the tracer's. */
    private boolean deletesTraceKey(SourceFileInfo f, MdcOp op) {
        return folds(f, Pos.line(op.call()), Rules.MANUAL_TRACE_MDC) && !unsure.contains(op.key());
    }

    /**
     * TRACE_KEY_HOLDS_APP_ID (#269): the service puts its own id (a correlation id from a header, a generated one) under
     * a key the tracer owns. Deleting it as a manual trace copy would hand every reader the tracer's id instead, and
     * the id callers and support see (a response header, an error body, a stored column) would no longer match the
     * logs. The key moves to a key of its own in every put, remove and get, so each reader keeps the value it had and
     * the tracer fills the trace key. When a use cannot be renamed in place (the key is a constant) or the new key is
     * already taken, it is reported.
     */
    private void appOwnedKeys() {
        if (appOwned.isEmpty() && unsure.isEmpty() || !ctx.enabled(Rules.TRACE_KEY_HOLDS_APP_ID)) {
            return;
        }
        String target = ctx.policy.logging().style().apply("correlationId");
        for (String key : new TreeSet<>(java.util.stream.Stream.concat(appOwned.stream(), unsure.stream()).toList())) {
            List<MethodInfo> methods = new ArrayList<>();
            List<MdcOp> uses = new ArrayList<>();
            boolean taken = false;
            for (MethodInfo m : ctx.methods()) {
                for (MdcOp op : m.facts().mdc) {
                    if (key.equals(op.key())) {
                        methods.add(m);
                        uses.add(op);
                    }
                    taken |= target.equals(op.key());
                }
            }
            int first = 0;
            while (first < uses.size() && !(uses.get(first).kind() == MdcOp.Kind.PUT || uses.get(first).kind() == MdcOp.Kind.PUT_CLOSEABLE)) {
                first++;
            }
            if (first == uses.size()) {
                continue;
            }
            MethodInfo at = methods.get(first);
            MdcOp put = uses.get(first);
            int line = Pos.line(put.call());
            if (ctx.ignored(at.file(), line, Rules.TRACE_KEY_HOLDS_APP_ID)) {
                continue;
            }
            boolean literals = uses.stream().allMatch(op -> op.keyExpr() instanceof J.Literal l && key.equals(l.getValue()));
            String value = Text.oneLine(valueOf(put).printTrimmed(), 80);
            OrderBuilder b = ctx.order(Rules.TRACE_KEY_HOLDS_APP_ID, at, line, "appkey:" + key);
            b.context(ctx.context(at, Pos.start(put.call()), Text.oneLine(put.call().printTrimmed(), 160), null));
            if (unsure.contains(key)) {
                b.title("MDC key '" + key + "' is set from " + value + ": logast cannot tell whether that is the trace id")
                        .suggestion("If it is the tracer's id, delete the manual MDC handling: tracing maintains '" + key + "'. If it is the "
                                + "service's own id (one callers or support see), move it to '" + target + "' in every MDC put, remove and get.");
                continue;
            }
            b.title("MDC key '" + key + "' holds the service's own id (" + value + "), not the trace id");
            if (taken || !literals) {
                b.suggestion("Tracing fills '" + key + "' with the trace id. Move this value to a key of its own ("
                        + (taken ? "'" + target + "' is already used: pick another" : "the key is a constant: rename it where it is defined")
                        + ") in every MDC put, remove and get, and list it in the log encoder if that names its MDC keys.");
                continue;
            }
            b.suggestion("Renamed to '" + target + "' in every MDC put, remove and get (" + uses.size() + "), so its readers keep this value; "
                    + "tracing fills '" + key + "' with the trace id. If the log encoder names its MDC keys (includeMdcKeyName), add '"
                    + target + "' there.");
            for (int i = 0; i < uses.size(); i++) {
                J.Literal lit = (J.Literal) uses.get(i).keyExpr();
                b.edit(Edit.replace(methods.get(i).file().relPath(), Pos.start(lit), Pos.end(lit), "\"" + target + "\""));
            }
        }
    }

    private static final Set<String> INIT_ANNOTATIONS = Set.of("jakarta.annotation.PostConstruct", "javax.annotation.PostConstruct",
            "org.springframework.context.annotation.Bean");
    private static final List<String> BOUNDARY_TYPES = List.of("jakarta.servlet.Filter", "javax.servlet.Filter",
            "org.springframework.web.servlet.HandlerInterceptor", "org.springframework.core.task.TaskDecorator",
            "org.springframework.messaging.support.ChannelInterceptor", "org.springframework.web.server.WebFilter",
            "java.util.concurrent.ThreadFactory");

    /**
     * MDC_NOT_OWNER: MDC belongs to the flow, set where it starts (the entrypoint), not by the beans it uses.
     * <ul>
     *   <li>a bean writing MDC while it is created (constructor, @PostConstruct, afterPropertiesSet, @Bean): the value
     *       sticks to the startup thread. Deleted when an entrypoint already scopes the same key, else reported;</li>
     *   <li>a plain method (not an entrypoint, not a filter/interceptor/decorator) that puts a key its entrypoint owns:
     *       reported (two owners of one key).</li>
     * </ul>
     */
    private void notOwner(MethodInfo m, List<int[]> claimed) {
        if (!ctx.enabled(Rules.MDC_NOT_OWNER) || BOUNDARY_TYPES.stream().anyMatch(t -> TypeUtils.isAssignableTo(t, m.owner().type()))
                || m.owner().fqn().startsWith(ctx.a.supportPackage + ".")) {
            return;
        }
        boolean init = m.decl().isConstructor() || m.name().equals("afterPropertiesSet") || INIT_ANNOTATIONS.stream().anyMatch(m::hasAnnotation);
        boolean entrypoint = ctx.a.entrypoints.stream().anyMatch(ep -> ep.method().key().equals(m.key()));
        if (entrypoint) {
            return;
        }
        for (MdcOp op : m.facts().mdc) {
            boolean write = op.kind() == MdcOp.Kind.PUT || init && op.kind() == MdcOp.Kind.PUT_CLOSEABLE;
            if (!write || op.key() == null || ambient.contains(op.key())) {
                continue;
            }
            List<String> owners = ownersOf(op.key());
            boolean reached = ctx.a.paths.entrypointsReaching(m).stream().anyMatch(ep -> owners.contains(ep.method().label()));
            if (!init && !reached) {
                continue;   // a helper setting context nobody else owns: MDC_NOT_CLOSEABLE handles the scoping
            }
            int line = Pos.line(op.call());
            if (ctx.ignored(m.file(), line, Rules.MDC_NOT_OWNER)) {
                continue;
            }
            OrderBuilder b = ctx.order(Rules.MDC_NOT_OWNER, m, line, "owner|" + op.key())
                    .title((init ? m.owner().simpleName() + " puts '" + op.key() + "' into MDC while the bean is being created: the value "
                            + "sticks to the startup thread and leaks into whatever runs there next"
                            : m.label() + " puts '" + op.key() + "' into MDC, but " + String.join(", ", owners) + " already owns it")
                            + ". MDC belongs to the flow that uses the value, set where it starts.")
                    .context(ctx.context(m, Pos.start(op.call()), Text.oneLine(op.call().printTrimmed(), 160), null));
            if (init && !owners.isEmpty() && op.block() != null && op.kind() == MdcOp.Kind.PUT) {
                int[] lines = JavaEdits.statementLines(m.file().text(), op.call());
                b.edit(Edit.replace(m.file().relPath(), lines[0], lines[1], ""))
                        .suggestion("Removed: " + String.join(", ", owners) + " scopes '" + op.key() + "' for its run.");
                claimed.add(lines);
                String rest = m.file().text().substring(0, lines[0]) + m.file().text().substring(lines[1]);
                if (!rest.contains("MDC.")) {
                    b.edit(removeImport(m.file(), "org.slf4j.MDC"));
                }
            } else {
                b.suggestion("Expose the value (a getter) and scope it where the flow starts: try (var mdc = MdcScope.of(\""
                        + op.key() + "\", " + Text.lowerFirst(m.owner().simpleName()) + ".…())) { … }"
                        + (owners.isEmpty() ? "" : " — " + String.join(", ", owners) + " already does; then delete this put."));
                claimed.add(new int[]{Pos.start(op.call()), Pos.end(op.call())});
            }
        }
    }

    /** Entrypoints that write the key themselves (put, putCloseable, MdcScope). */
    private List<String> ownersOf(String key) {
        List<String> out = new ArrayList<>();
        for (Entrypoint ep : ctx.a.entrypoints) {
            if (ep.method().facts().mdc.stream().anyMatch(o -> key.equals(o.key())
                    && (o.kind() == MdcOp.Kind.PUT || o.kind() == MdcOp.Kind.PUT_CLOSEABLE))) {
                out.add(ep.method().label());
            }
        }
        return out;
    }

    /**
     * MDC_CLEAR: {@code MDC.clear()} not already part of a converted put/clear block. It removes every key on the thread,
     * the caller's included (trace ids, request context), in the middle of a flow.
     */
    private void clears(MethodInfo m) {
        if (!ctx.enabled(Rules.MDC_CLEAR)) {
            return;
        }
        for (MdcOp op : m.facts().mdc) {
            if (op.kind() != MdcOp.Kind.CLEAR) {
                continue;
            }
            J.Try owner = op.block() == null ? null : tryWithFinally(m, op.block());
            if (owner != null && handledTries.contains(owner) || handledClears.contains(op.call())) {
                continue;   // the MDC_NOT_CLOSEABLE order already replaces it
            }
            int line = Pos.line(op.call());
            if (ctx.ignored(m.file(), line, Rules.MDC_CLEAR)) {
                continue;
            }
            ctx.orderNth(Rules.MDC_CLEAR, m, line, "clear")
                    .title("MDC.clear() removes every MDC key on this thread, including the caller's (trace ids, request "
                            + "context): later logs of the same flow lose their context")
                    .suggestion("Scope what this code sets with try (var mdc = MdcScope.of(..)) and drop the clear(); a thread "
                            + "hand-off restores the previous map (ContextPropagation) instead of clearing.")
                    .doneWhen("this MDC.clear() is gone")
                    .context(ctx.context(m, Pos.start(op.call()), Text.oneLine(op.call().printTrimmed(), 160), null));
        }
    }

    /** The try whose finally block is {@code block}. */
    private static J.Try tryWithFinally(MethodInfo m, J.Block block) {
        J.Try[] found = new J.Try[1];
        new org.openrewrite.java.JavaIsoVisitor<Integer>() {
            @Override
            public J.Try visitTry(J.Try t, Integer p) {
                if (t.getFinally() == block) {
                    found[0] = t;
                }
                return super.visitTry(t, p);
            }
        }.visit(m.decl().getBody(), 0);
        return found[0];
    }

    /**
     * The try-with-resources whose only resource is this single-key MdcScope (or MDC.putCloseable), without catch or
     * finally; else null.
     */
    private static J.Try scopeTry(MethodInfo m, MdcOp op) {
        J.Try[] found = new J.Try[1];
        new org.openrewrite.java.JavaIsoVisitor<Integer>() {
            @Override
            public J.Try visitTry(J.Try t, Integer p) {
                if (t.getResources() != null && t.getResources().size() == 1 && t.getCatches().isEmpty() && t.getFinally() == null
                        && Pos.start(t) <= Pos.start(op.call()) && Pos.end(op.call()) <= Pos.start(t.getBody())   // in the try header
                        && (op.call().getSimpleName().equals("of") || op.call().getSimpleName().equals("putCloseable"))
                        && op.call().getArguments().size() == 2) {
                    found[0] = t;
                }
                return super.visitTry(t, p);
            }
        }.visit(m.decl().getBody(), 0);
        return found[0];
    }

    /**
     * MDC_NESTED_KEY: the method scopes a key that every path into it already has in MDC. Two owners of one key: the
     * inner value hides the outer one, and the code that set it first no longer decides it.
     */
    private void nestedKeys(MethodInfo m) {
        if (!ctx.enabled(Rules.MDC_NESTED_KEY)) {
            return;   // MDC on entry also covers flows that start where nothing in the code calls (PathContext roots)
        }
        Set<String> atEntry = ctx.a.paths.mdcAtEntry(m);
        Set<String> seen = new HashSet<>();
        for (MdcOp op : m.facts().mdc) {
            if ((op.kind() == MdcOp.Kind.PUT || op.kind() == MdcOp.Kind.PUT_CLOSEABLE) && op.key() != null
                    && atEntry.contains(op.key()) && !ambient.contains(op.key()) && seen.add(op.key())) {
                int line = Pos.line(op.call());
                if (ctx.ignored(m.file(), line, Rules.MDC_NESTED_KEY)) {
                    continue;
                }
                Expression value = valueOf(op);
                OrderBuilder b = ctx.order(Rules.MDC_NESTED_KEY, m, line, "nested|" + op.key())
                        .context(ctx.context(m, Pos.start(op.call()), Text.oneLine(op.call().printTrimmed(), 160), null));
                if (value == null || !repeatsCallerValue(ctx.a, m, op.key(), value)) {
                    // #79: the inner value may differ from the caller's (k.trim(), "L-" + k, a local): unwrapping it
                    // would silently log the caller's value instead. Only reported; the owner decides.
                    String shown = value == null ? "its value" : Text.oneLine(src(m.file(), value), 80);
                    b.title("MDC key '" + op.key() + "' is already set on every path into " + m.label() + ", and this nested scope "
                                    + "sets it to " + shown + ", not provably the caller's value: inside, the logs carry this value instead")
                            .suggestion("If " + shown + " is worth logging, give it its own key (e.g. source." + op.key()
                                    + ") so both stay visible; if the caller's value is enough, drop this scope.");
                    continue;
                }
                b.title("MDC key '" + op.key() + "' is already set on every path into " + m.label()
                        + ": a nested scope for the same key");
                // a single-key scope (often left by an earlier run) is unwrapped: one key, one value per flow
                J.Try t = op.kind() == MdcOp.Kind.PUT_CLOSEABLE && op.inResource() ? scopeTry(m, op) : null;
                int[] lines = t == null ? null : JavaEdits.statementLines(m.file().text(), t);
                int[] inner = t == null ? null : JavaEdits.blockInner(m.file().text(), t.getBody());
                List<Expression> args = op.call().getArguments();
                int at = op.kind() == MdcOp.Kind.PUT_CLOSEABLE && op.call().getSimpleName().equals("of") ? args.indexOf(op.keyExpr()) : -1;
                if (t == null && at >= 0 && at % 2 == 0 && args.size() > 2 && args.size() % 2 == 0) {
                    // MdcScope.of("pledgeId", a, "sponsorshipId", b): only the duplicate pair goes, the other keys stay
                    int from = at + 2 < args.size() ? Pos.start(args.get(at)) : Pos.end(args.get(at - 1));
                    int to = at + 2 < args.size() ? Pos.start(args.get(at + 2)) : Pos.end(args.get(at + 1));
                    b.edit(Edit.replace(m.file().relPath(), from, to, ""));
                    b.suggestion("Removes " + op.key() + " from this scope: it is in MDC on every path into " + m.label()
                            + " already; the other keys stay.");
                } else if (t != null && lines != null && inner != null) {
                    String text = m.file().text();
                    String body = Text.dedent(text.substring(inner[0], inner[1]), Text.indentUnit(text));
                    b.edit(Edit.replace(m.file().relPath(), lines[0], lines[1], body));
                    if (!(text.substring(0, lines[0]) + text.substring(lines[1])).contains("MDC.")) {
                        b.edit(removeImport(m.file(), "org.slf4j.MDC"));   // the unwrapped scope was MDC.putCloseable
                    }
                    b.suggestion("Unwraps the scope: " + op.key() + " is in MDC on every path into " + m.label()
                            + " already. If the value really differs, keep it under a distinct key (e.g. source." + op.key() + ").");
                } else {
                    b.suggestion("Drop this scope if the value is the same; if it differs, use a distinct key "
                            + "(e.g. source." + op.key() + ") so both stay visible.");
                }
            }
        }
    }

    private void method(MethodInfo m) {
        SourceFileInfo f = m.file();
        String text = f.text();
        List<MdcOp> ambientOps = m.facts().mdc.stream()
                .filter(op -> op.key() != null && ambient.contains(op.key()) && op.block() != null
                        && (op.kind() == MdcOp.Kind.PUT || op.kind() == MdcOp.Kind.REMOVE))
                .toList();
        Set<MdcOp> coveredAmbient = Collections.newSetFromMap(new IdentityHashMap<>());
        List<int[]> claimed = new ArrayList<>();
        notOwner(m, claimed);

        if (ctx.enabled(Rules.MDC_NOT_CLOSEABLE)) {
            int ordinal = 0;
            for (MdcOp op : m.facts().mdc) {
                if (op.kind() != MdcOp.Kind.PUT || op.key() == null || ambient.contains(op.key()) || op.block() == null
                        || op.inFinally()) {
                    continue;
                }
                int line = Pos.line(op.call());
                int start = Pos.start(op.call());
                if (claimed.stream().anyMatch(r -> r[0] <= start && start < r[1]) || ctx.ignored(f, line, Rules.MDC_NOT_CLOSEABLE)) {
                    continue;
                }
                int[] region = notCloseable(m, op, ambientOps, coveredAmbient, ordinal++);
                if (region != null) {
                    claimed.add(region);
                }
            }
            int n = 0;
            for (MdcOp op : m.facts().mdc) {
                if (op.kind() != MdcOp.Kind.PUT_CLOSEABLE || !op.call().getSimpleName().equals("putCloseable")
                        || op.call().getArguments().size() != 2 || op.key() != null && ambient.contains(op.key())) {
                    continue;
                }
                int line = Pos.line(op.call());
                int start = Pos.start(op.call());
                if (claimed.stream().anyMatch(r -> r[0] <= start && start < r[1]) || ctx.ignored(f, line, Rules.MDC_NOT_CLOSEABLE)
                        || op.key() != null && folds(f, line, Rules.MDC_NESTED_KEY) && ctx.a.paths.mdcAtEntry(m).contains(op.key())
                        && repeatsCallerValue(ctx.a, m, op.key(), op.call().getArguments().get(1))) {
                    continue;   // a key the caller already scopes: MDC_NESTED_KEY unwraps the scope instead
                }
                int[] region = putCloseable(m, op, n++);
                if (region != null) {
                    claimed.add(region);
                }
            }
        }

        if (ctx.enabled(Rules.MDC_KEY_ALIAS)) {
            Map<String, String> aliases = ctx.policy.mdcAliases();
            int n = 0;
            for (MdcOp op : m.facts().mdc) {
                String canonical = ctx.policy.canonicalMdcKey(op.key());
                int st = Pos.start(op.call());
                if (canonical == null || claimed.stream().anyMatch(r -> r[0] <= st && st < r[1])) {
                    continue;
                }
                J.Literal lit = op.call().getArguments().stream()
                        .filter(a -> a instanceof J.Literal l && op.key().equals(l.getValue()))
                        .map(J.Literal.class::cast).findFirst().orElse(null);
                int line = Pos.line(op.call());
                if (lit == null || ctx.ignored(f, line, Rules.MDC_KEY_ALIAS)) {
                    continue;
                }
                ctx.order(Rules.MDC_KEY_ALIAS, m, line, "alias" + n++)
                        .title(aliases.containsKey(op.key()) ? "MDC key '" + op.key() + "' is an alias of '" + canonical + "' (vocabulary)"
                                : "MDC key '" + op.key() + "' is not spelled in keyStyle " + ctx.policy.logging().keyStyle() + ": '" + canonical + "'")
                        .edit(Edit.replace(f.relPath(), Pos.start(lit), Pos.end(lit), "\"" + canonical + "\""))
                        .context(ctx.context(m, st, Text.oneLine(op.call().printTrimmed(), 160), null));
            }
        }

        if (ctx.enabled(Rules.MANUAL_TRACE_MDC)) {
            List<MdcOp> remaining = ambientOps.stream().filter(op -> !coveredAmbient.contains(op) && !unsure.contains(op.key())).toList();
            if (!remaining.isEmpty() && !ctx.ignored(f, Pos.line(remaining.getFirst().call()), Rules.MANUAL_TRACE_MDC)) {
                MdcOp first = remaining.stream().filter(op -> op.kind() == MdcOp.Kind.PUT).findFirst().orElse(remaining.getFirst());
                OrderBuilder b = ctx.order(Rules.MANUAL_TRACE_MDC, m, Pos.line(first.call()), "trace")
                        .title("Sets " + first.key() + " in MDC by hand; OpenTelemetry/Micrometer Tracing maintains it")
                        .suggestion("Trace context comes from W3C traceparent propagation; delete the manual MDC handling.");
                remaining.forEach(op -> b.edit(JavaEdits.deleteStatement(f, op.call())));
                b.context(ctx.context(m, Pos.start(first.call()), Text.oneLine(text.substring(Pos.start(first.call()), JavaEdits.statementEnd(text, first.call())), 160), null));
            }
        }
    }

    /**
     * A rewrite folds another rule's fix in (drops a trace key, renames an alias, lists it under "also fixes") only when
     * that rule is enabled and not suppressed on the line: a switched-off rule edits nothing (#75).
     */
    private boolean folds(SourceFileInfo f, int line, String rule) {
        return ctx.enabled(rule) && !ctx.ignored(f, line, rule);
    }

    /** @return the replaced range, or null when no edit was produced */
    private int[] notCloseable(MethodInfo m, MdcOp op, List<MdcOp> ambientOps, Set<MdcOp> coveredAmbient, int ordinal) {
        SourceFileInfo f = m.file();
        String text = f.text();
        int line = Pos.line(op.call());
        List<Statement> stmts = op.block().getStatements();
        int i = indexOf(stmts, op.call());
        OrderBuilder b = ctx.order(Rules.MDC_NOT_CLOSEABLE, m, line, "put" + ordinal);
        b.context(ctx.context(m, Pos.start(op.call()), Text.oneLine(text.substring(Pos.start(op.call()), JavaEdits.statementEnd(text, op.call())), 160), null));
        int[] pLines = JavaEdits.statementLines(text, op.call());
        J.Try enclosing = enclosingTry(m, op.block());
        if (i >= 0 && enclosing != null && enclosing.getFinally() != null && enclosing.getFinally().getStatements().stream()
                .anyMatch(fs -> isMdc(fs, "clear", null) || isMdc(fs, "remove", op.key()))) {
            if (!handledTries.add(enclosing)) {
                ctx.drop(b);
                return null;
            }
            return finallyScoped(m, op, b, enclosing, ambientOps, coveredAmbient);
        }
        if (i < 0 || pLines[0] != Text.lineStart(text, Pos.start(op.call()))) {
            b.title("MDC.put(\"" + op.key() + "\") is not scoped").suggestion("Use try (var mdc = MdcScope.of(...)) { ... }.");
            return null;
        }

        // consecutive puts belong together: MDC.put(a); MDC.put(b); → one MdcScope.of(a, .., b, ..)
        Map<J.MethodInvocation, String> keyOf = new IdentityHashMap<>();
        m.facts().mdc.forEach(o -> {
            if (o.key() != null) {
                keyOf.put(o.call(), o.key());
            }
        });
        List<J.MethodInvocation> group = new ArrayList<>(List.of(op.call()));
        while (i + group.size() < stmts.size() && stmts.get(i + group.size()) instanceof J.MethodInvocation next
                && isMdc(next, "put", null) && keyOf.containsKey(next) && !ambient.contains(keyOf.get(next))) {
            group.add(next);
        }
        Set<String> keys = new LinkedHashSet<>();
        group.forEach(g -> keys.add(keyOf.get(g)));
        int after = i + group.size();

        String mode = "leak";
        int j = -1;
        for (int k = after; k < stmts.size(); k++) {
            Statement s = stmts.get(k);
            if (isMdc(s, "clear", null)) {
                mode = "clear";
                j = k;
                break;
            }
            if (keys.stream().anyMatch(key -> isMdc(s, "remove", key))) {
                mode = "remove";
                j = k;
                break;
            }
            if (s instanceof J.Try t && t.getFinally() != null && t.getFinally().getStatements().stream()
                    .anyMatch(fs -> isMdc(fs, "clear", null) || keys.stream().anyMatch(key -> isMdc(fs, "remove", key)))) {
                mode = "try";
                j = k;
                break;
            }
        }
        String what = keys.size() == 1 ? "MDC.put(\"" + op.key() + "\")" : "MDC.put of " + keys;
        if (mode.equals("leak") && after == stmts.size()) {
            b.severity("high").title(what + " leaks: never removed")
                    .suggestion("The key outlives this method on a pooled thread. Scope it with MdcScope around the work it describes.");
            return null;
        }
        b.title(switch (mode) {
            case "leak" -> what + " leaks into later work on this thread (never removed)";
            case "clear" -> what + " then MDC.clear(): clear() also wipes the caller's keys; one MdcScope restores exactly what it set";
            default -> what + " with manual cleanup: use one try-with-resources MdcScope (restores previous values)";
        });
        if (mode.equals("leak") || mode.equals("clear")) {
            b.severity("high");
        }
        if (mode.equals("clear") || mode.equals("try") && ((J.Try) stmts.get(j)).getFinally().getStatements().stream().anyMatch(fs -> isMdc(fs, "clear", null))) {
            J.MethodInvocation clear = mode.equals("clear") ? (J.MethodInvocation) stmts.get(j)
                    : (J.MethodInvocation) ((J.Try) stmts.get(j)).getFinally().getStatements().stream().filter(fs -> isMdc(fs, "clear", null)).findFirst().orElseThrow();
            if (folds(f, Pos.line(clear), Rules.MDC_CLEAR)) {
                b.fix(Rules.MDC_CLEAR);
            }
            handledClears.add(clear);
        }

        int regionEnd = mode.equals("leak") ? stmts.size() : j;
        List<int[]> cuts = new ArrayList<>();
        for (MdcOp a : ambientOps) {
            int s = Pos.start(a.call());
            if (s > Pos.start(op.call()) && within(stmts, after, regionEnd, a.call(), mode.equals("try") ? stmts.get(j) : null)
                    && deletesTraceKey(f, a)) {
                cuts.add(JavaEdits.statementLines(text, a.call()));
                coveredAmbient.add(a);
                if (a.kind() == MdcOp.Kind.PUT) {
                    b.location(f.relPath(), Pos.line(a.call()), Rules.MANUAL_TRACE_MDC);
                }
            }
        }

        String unit = Text.indentUnit(text);
        String indent = Text.indentAt(text, Pos.start(op.call()));
        int from = JavaEdits.statementLines(text, group.getLast())[1];
        String region;
        int replaceEnd;
        switch (mode) {
            case "remove", "clear" -> {
                int[] rl = JavaEdits.statementLines(text, stmts.get(j));
                replaceEnd = rl[1];
                // further removes of the group's keys right after the first one go too
                for (int k = j + 1; k < stmts.size(); k++) {
                    Statement more = stmts.get(k);
                    if (keys.stream().noneMatch(key -> isMdc(more, "remove", key))) {
                        break;
                    }
                    replaceEnd = JavaEdits.statementLines(text, more)[1];
                }
                region = Text.reindent(JavaEdits.cut(text, from, rl[0], cuts), unit);
            }
            case "try" -> {
                J.Try t = (J.Try) stmts.get(j);
                cuts.addAll(t.getFinally().getStatements().stream()
                        .filter(fs -> isMdc(fs, "clear", null) || keys.stream().anyMatch(key -> isMdc(fs, "remove", key)))
                        .map(fs -> JavaEdits.statementLines(text, fs)).toList());
                boolean finallyEmpties = t.getFinally().getStatements().stream()
                        .allMatch(fs -> cuts.stream().anyMatch(c -> c[0] <= Pos.start(fs) && Pos.start(fs) < c[1]));
                int tStart = Text.lineStart(text, Pos.start(t));
                replaceEnd = Text.nextLine(text, Pos.end(t) - 1);
                int[] bodyInner = JavaEdits.blockInner(text, t.getBody());
                if (finallyEmpties && t.getCatches().isEmpty() && t.getResources() == null && bodyInner != null) {
                    region = Text.reindent(JavaEdits.cut(text, from, tStart, cuts), unit)
                            + JavaEdits.cut(text, bodyInner[0], bodyInner[1], cuts);
                } else {
                    region = Text.reindent(JavaEdits.cut(text, from, replaceEnd, cuts), unit);
                }
            }
            default -> {
                Statement last = stmts.getLast();
                replaceEnd = Text.nextLine(text, JavaEdits.statementEnd(text, last) - 1);
                region = Text.reindent(JavaEdits.cut(text, from, replaceEnd, cuts), unit);
            }
        }
        if (!region.endsWith("\n")) {
            region += "\n";
        }
        List<String> pairs = new ArrayList<>();
        for (J.MethodInvocation put : group) {
            String key = keyOf.get(put);
            boolean literal = put.getArguments().getFirst() instanceof J.Literal;
            String canonical = literal && folds(f, Pos.line(put), Rules.MDC_KEY_ALIAS) ? ctx.policy.canonicalMdcKey(key) : null;
            if (canonical != null) {
                b.fix(Rules.MDC_KEY_ALIAS);
            }
            String effective = canonical != null ? canonical : key;
            if (folds(f, Pos.line(put), Rules.MDC_NESTED_KEY) && sameValueAlreadyInMdc(m, effective, put.getArguments().get(1))) {
                b.fix(Rules.MDC_NESTED_KEY);
                continue;
            }
            pairs.add((canonical != null ? "\"" + canonical + "\"" : src(f, put.getArguments().getFirst())) + ", "
                    + src(f, put.getArguments().get(1)));
        }
        String var = EntrypointRules.unique("mdc", ctx.taken(m, Pos.start(op.call())));
        int observedAt = pairs.isEmpty() ? -1 : EntrypointRules.observedMdcAt(m, text, pLines[0], replaceEnd);
        if (observedAt >= 0) {
            // #20: the scope would be the whole observation: its MDC goes into the ObservedContext chain
            String cont = Text.indentAt(text, Pos.start(m.decl().getBody().getStatements().getFirst())) + unit + unit;
            StringBuilder chain = new StringBuilder();
            pairs.forEach(pair -> chain.append('\n').append(cont).append(".mdc(").append(pair).append(')'));
            b.edit(Edit.insert(f.relPath(), observedAt, chain.toString()));
            b.edit(Edit.replace(f.relPath(), pLines[0], replaceEnd, Text.dedent(region, unit)));
            if (!text.substring(0, pLines[0]).concat(text.substring(replaceEnd)).contains("MDC.")) {
                b.edit(removeImport(f, "org.slf4j.MDC"));
            }
            return new int[]{pLines[0], replaceEnd};
        }
        String replacement = pairs.isEmpty() ? Text.dedent(region, unit)
                : indent + "try (var " + var + " = MdcScope.of(" + String.join(", ", pairs) + ")) {\n" + region + indent + "}\n";
        b.edit(Edit.replace(f.relPath(), pLines[0], replaceEnd, replacement));
        if (!pairs.isEmpty()) {
            b.edits(JavaEdits.imports(f, ctx.support("MdcScope")));
            ctx.dependOnStarter(b);
        }
        if (!text.substring(0, pLines[0]).concat(text.substring(replaceEnd)).contains("MDC.")) {
            b.edit(removeImport(f, "org.slf4j.MDC"));
        }
        return new int[]{pLines[0], replaceEnd};
    }

    /**
     * {@code try (var mdc = MDC.putCloseable(k, v)) {...}}: closing removes the key instead of restoring the value it
     * had, so a nested scope of the same key wipes the outer one (#11). Resource is rewritten to {@code MdcScope.of(k, v)}
     * (the alias renamed when MDC_KEY_ALIAS is on); a putCloseable that is not the initializer of an otherwise unused
     * try-with-resources variable is reported.
     * @return the call's range (claimed against the other MDC rewrites), or null when only reported
     */
    private int[] putCloseable(MethodInfo m, MdcOp op, int ordinal) {
        SourceFileInfo f = m.file();
        String text = f.text();
        J.MethodInvocation call = op.call();
        int line = Pos.line(call);
        String what = "MDC.putCloseable(" + (op.key() != null ? "\"" + op.key() + "\"" : src(f, call.getArguments().getFirst())) + ")";
        OrderBuilder b = ctx.order(Rules.MDC_NOT_CLOSEABLE, m, line, "closeable" + ordinal)
                .context(ctx.context(m, Pos.start(call), Text.oneLine(call.printTrimmed(), 160), null));
        int[] range = {Pos.start(call), Pos.end(call)};
        J.VariableDeclarations resource = op.inResource() ? resourceOf(m, call) : null;
        if (resource == null || referenced(m, resource.getVariables().getFirst().getSimpleName(), resource)) {
            b.severity(op.block() != null ? "high" : "low")
                    .title(what + (op.block() != null ? " leaks: its MDCCloseable is never closed"
                            : " outside a try-with-resources header: closing it removes the key instead of restoring the previous value"))
                    .suggestion("Use try (var mdc = MdcScope.of(k, v)) { ... } around the work it describes: it restores what the "
                            + "caller had set.");
            return null;   // nothing rewritten: MDC_KEY_ALIAS may still rename the key
        }
        b.title(what + " removes the key on close, so an outer value of it is lost: use MdcScope (restores the previous value)");
        Expression keyArg = call.getArguments().getFirst();
        String canonical = keyArg instanceof J.Literal && folds(f, line, Rules.MDC_KEY_ALIAS) ? ctx.policy.canonicalMdcKey(op.key()) : null;
        if (canonical != null) {
            b.fix(Rules.MDC_KEY_ALIAS);
        }
        b.edit(Edit.replace(f.relPath(), range[0], range[1], "MdcScope.of(" + (canonical != null ? "\"" + canonical + "\"" : src(f, keyArg))
                + ", " + src(f, call.getArguments().get(1)) + ")"));
        String rest = text.substring(0, range[0]) + text.substring(range[1]);
        J type = resource.getTypeExpression();
        if (type != null && !f.slice(Pos.start(type), Pos.end(type)).equals("var")) {
            int ts = Pos.start(type);
            int te = Pos.end(type);
            b.edit(Edit.replace(f.relPath(), ts, te, "MdcScope"));
            rest = text.substring(0, ts) + text.substring(te, range[0]) + text.substring(range[1]);
        }
        b.edits(JavaEdits.imports(f, ctx.support("MdcScope")));
        if (!rest.contains("MDC.")) {
            b.edit(removeImport(f, "org.slf4j.MDC"));
        }
        ctx.dependOnStarter(b);
        return range;
    }

    /** The try-with-resources variable {@code call} directly initializes, or null. */
    private static J.VariableDeclarations resourceOf(MethodInfo m, J.MethodInvocation call) {
        J.VariableDeclarations[] found = new J.VariableDeclarations[1];
        new org.openrewrite.java.JavaIsoVisitor<Integer>() {
            @Override
            public J.Try visitTry(J.Try t, Integer p) {
                for (J.Try.Resource r : t.getResources() == null ? List.<J.Try.Resource>of() : t.getResources()) {
                    if (r.getVariableDeclarations() instanceof J.VariableDeclarations vd && vd.getVariables().size() == 1
                            && vd.getVariables().getFirst().getInitializer() == call) {
                        found[0] = vd;
                    }
                }
                return super.visitTry(t, p);
            }
        }.visit(m.decl(), 0);
        return found[0];
    }

    /** {@code name} is used somewhere in the method besides its declaration (an explicit close, passed on, ...). */
    private static boolean referenced(MethodInfo m, String name, J.VariableDeclarations decl) {
        boolean[] used = {false};
        new org.openrewrite.java.JavaIsoVisitor<Integer>() {
            @Override
            public J.VariableDeclarations visitVariableDeclarations(J.VariableDeclarations vd, Integer p) {
                return vd == decl ? vd : super.visitVariableDeclarations(vd, p);
            }

            @Override
            public J.VariableDeclarations.NamedVariable visitVariable(J.VariableDeclarations.NamedVariable v, Integer p) {
                if (v.getInitializer() != null) {
                    visit(v.getInitializer(), p);   // another declaration's name is not a use
                }
                return v;
            }

            @Override
            public J.Identifier visitIdentifier(J.Identifier id, Integer p) {
                if (id.getSimpleName().equals(name) && id.getFieldType() != null) {
                    used[0] = true;
                }
                return id;
            }
        }.visit(m.decl(), 0);
        return used[0];
    }

    /**
     * {@code try { MDC.put(a); MDC.put(b); ...work } finally { MDC.clear(); }}: not a leak, but clear() also wipes keys
     * the caller set (request context, propagated context). Rewritten to one multi-key MdcScope when the block has no
     * other (dynamic) MDC writes and the try is plain; otherwise reported.
     */
    private int[] finallyScoped(MethodInfo m, MdcOp first, OrderBuilder b, J.Try t, List<MdcOp> ambientOps,
                                Set<MdcOp> coveredAmbient) {
        SourceFileInfo f = m.file();
        String text = f.text();
        boolean clears = t.getFinally().getStatements().stream().anyMatch(fs -> isMdc(fs, "clear", null));
        b.title("MDC set inside try/finally with " + (clears ? "MDC.clear(), which also wipes the caller's MDC context"
                : "MDC.remove()") + ": use one MdcScope (restores exactly what it changed)");
        List<Statement> body = t.getBody().getStatements();
        List<J.MethodInvocation> puts = new ArrayList<>();
        int k = 0;
        Map<J.MethodInvocation, String> keyOf = new IdentityHashMap<>();
        m.facts().mdc.forEach(o -> {
            if (o.key() != null) {
                keyOf.put(o.call(), o.key());
            }
        });
        while (k < body.size() && body.get(k) instanceof J.MethodInvocation mi && isMdc(mi, "put", null)
                && keyOf.containsKey(mi)) {   // literal or a resolved String constant
            puts.add(mi);
            k++;
        }
        boolean otherMdcWrites = m.facts().mdc.stream().anyMatch(o -> o.kind() == MdcOp.Kind.PUT && !puts.contains(o.call())
                && Pos.start(o.call()) > Pos.start(t) && Pos.start(o.call()) < Pos.end(t.getBody()));
        boolean finallyOnlyMdc = t.getFinally().getStatements().stream()
                .allMatch(fs -> isMdc(fs, "clear", null) || isMdc(fs, "remove", null));
        int[] inner = JavaEdits.blockInner(text, t.getBody());
        // the rewrite drops the try's trace-key puts and removes: only when MANUAL_TRACE_MDC may delete them
        boolean keptTraceKeys = ambientOps.stream().anyMatch(o -> Pos.start(o.call()) > Pos.start(t) && Pos.start(o.call()) < Pos.end(t)
                && !deletesTraceKey(f, o));
        J.MethodInvocation clear = (J.MethodInvocation) t.getFinally().getStatements().stream()
                .filter(fs -> isMdc(fs, "clear", null)).findFirst().orElse(null);
        boolean foldsClear = clear != null && folds(f, Pos.line(clear), Rules.MDC_CLEAR);
        if (puts.isEmpty() || otherMdcWrites || keptTraceKeys || !finallyOnlyMdc || !t.getCatches().isEmpty() || t.getResources() != null
                || inner == null) {
            if (clears) {
                b.severity("high");
            }
            if (foldsClear) {
                b.fix(Rules.MDC_CLEAR);
            }
            b.suggestion("Replace the puts with try (var mdc = MdcScope.of(k1, v1, k2, v2 ...)) and drop the finally cleanup"
                    + (otherMdcWrites ? "; the block also writes computed MDC keys, scope those explicitly too" : "")
                    + (clears ? ". MDC.clear() removes keys this method never set." : "."));
            return null;
        }
        Map<String, String> aliases = ctx.policy.mdcAliases();
        List<String> pairs = new ArrayList<>();
        for (J.MethodInvocation put : puts) {
            String key = keyOf.get(put);
            boolean literal = put.getArguments().getFirst() instanceof J.Literal;
            if (ambient.contains(key)) {
                b.location(f.relPath(), Pos.line(put), Rules.MANUAL_TRACE_MDC);
                ambientOps.stream().filter(o -> o.call() == put).forEach(coveredAmbient::add);
                continue;
            }
            // a constant keeps its name; rename it at its declaration
            String canonical = literal && folds(f, Pos.line(put), Rules.MDC_KEY_ALIAS) ? ctx.policy.canonicalMdcKey(key) : null;
            String effective = canonical != null ? canonical : key;
            if (folds(f, Pos.line(put), Rules.MDC_NESTED_KEY) && sameValueAlreadyInMdc(m, effective, put.getArguments().get(1))) {
                b.fix(Rules.MDC_NESTED_KEY);
                if (!ctx.a.paths.mdcAtEntry(m).contains(effective)) {
                    // only planned: the entrypoint fixes that add it must be applied first
                    for (Entrypoint ep : ctx.a.paths.entrypointsReaching(m)) {
                        String id = ctx.entry.entryOrders().get(ep.method().key());
                        if (id != null) {
                            b.dependsOn(id);
                        }
                    }
                }
                continue;   // every caller already scoped this value: a second scope would only hide the first
            }
            if (canonical != null) {
                b.fix(Rules.MDC_KEY_ALIAS);
            }
            pairs.add((canonical != null ? "\"" + canonical + "\"" : src(f, put.getArguments().getFirst())) + ", " + src(f, put.getArguments().get(1)));
        }
        ambientOps.stream().filter(o -> Pos.start(o.call()) > Pos.start(t) && Pos.start(o.call()) < Pos.end(t)).forEach(coveredAmbient::add);
        int restStart = puts.isEmpty() ? inner[0] : Text.nextLine(text, JavaEdits.statementEnd(text, puts.getLast()) - 1);
        String rest = text.substring(restStart, inner[1]);
        rest = rest.replaceFirst("^(\\s*\n)+", "");
        String indent = Text.indentAt(text, Pos.start(t));
        String var = EntrypointRules.unique("mdc", ctx.taken(m, Pos.start(t)));
        String replacement = pairs.isEmpty()
                ? Text.dedent(rest, Text.indentUnit(text))
                : indent + "try (var " + var + " = MdcScope.of(" + String.join(", ", pairs) + ")) {\n" + rest + indent + "}\n";
        int from = Text.lineStart(text, Pos.start(t));
        int to = Text.nextLine(text, Pos.end(t) - 1);
        b.severity(clears ? "high" : "low");
        if (foldsClear) {
            b.fix(Rules.MDC_CLEAR);
        }
        b.edit(Edit.replace(f.relPath(), from, to, replacement));
        b.edits(JavaEdits.imports(f, ctx.support("MdcScope")));
        if (!text.substring(0, from).concat(text.substring(to)).contains("MDC.")) {
            b.edit(removeImport(f, "org.slf4j.MDC"));
        }
        ctx.dependOnStarter(b);
        return new int[]{from, to};
    }

    /**
     * The key is in MDC on every path into {@code m} (or will be, by planned entrypoint fixes), and the value is provably
     * the one the caller scoped ({@link #repeatsCallerValue}).
     */
    private boolean sameValueAlreadyInMdc(MethodInfo m, String key, Expression value) {
        return ctx.a.paths.reachable(m) && ctx.mdcAtEntry(m).contains(key) && repeatsCallerValue(ctx.a, m, key, value);
    }

    /**
     * #79: a scope of {@code key} in {@code m} sets provably the value the caller already scoped: the source parameter
     * unchanged, or a parameter (and accessors on it) that every call into {@code m} passes from a scope of the same
     * key with the identical expression, or from its own parameter that the same holds for, further up.
     */
    static boolean repeatsCallerValue(dev.logast.Analysis a, MethodInfo m, String key, Expression value) {
        if (carriesSourceValue(a.policy, m, key, value)) {
            return true;
        }
        String[] s = stable(value);
        return s != null && passedFromScope(a, m, key, s[0], s[1], 0);
    }

    /**
     * {@code root + suffix} (a parameter of {@code m}, accessors on it) is what every caller has under {@code key} at the
     * call: the argument it passes, with the same accessors, is the value of a scope around the call, or a parameter of
     * the caller that comes in that way itself.
     */
    private static boolean passedFromScope(dev.logast.Analysis a, MethodInfo m, String key, String root, String suffix, int depth) {
        int index = paramIndex(m, root);
        List<dev.logast.graph.CallGraph.Edge> in = a.graph.in(m);
        if (depth > 8 || index < 0 || assigned(m, root) || in.isEmpty()) {
            return false;
        }
        for (dev.logast.graph.CallGraph.Edge e : in) {
            if (e.ambiguous() || e.async() != dev.logast.graph.CallGraph.Async.NONE
                    || !(e.site().tree() instanceof J.MethodInvocation call) || call.getArguments().size() <= index) {
                return false;
            }
            MethodInfo c = e.from();
            String[] arg = stable(call.getArguments().get(index));
            if (arg == null || assigned(c, arg[0])) {
                return false;
            }
            String expected = arg[0] + arg[1] + suffix;
            if (!scopedAround(c, key, call, expected)
                    && !(a.paths.mdcAtEntry(c).contains(key) && passedFromScope(a, c, key, arg[0], arg[1] + suffix, depth + 1))) {
                return false;
            }
        }
        return true;
    }

    /** A try-with-resources scope of {@code key} in {@code c} whose body holds {@code site} and whose value reads {@code expected}. */
    private static boolean scopedAround(MethodInfo c, String key, J site, String expected) {
        for (MdcOp op : c.facts().mdc) {
            Expression v = valueOf(op);
            if (op.kind() != MdcOp.Kind.PUT_CLOSEABLE || !op.inResource() || !key.equals(op.key()) || v == null
                    || !expected.equals(c.file().slice(Pos.start(v), Pos.end(v)).replaceAll("\\s+", ""))) {
                continue;
            }
            boolean[] around = {false};
            new org.openrewrite.java.JavaIsoVisitor<Integer>() {
                @Override
                public J.Try visitTry(J.Try t, Integer p) {
                    around[0] |= t.getResources() != null && Pos.start(t) <= Pos.start(op.call()) && Pos.end(op.call()) <= Pos.start(t.getBody())
                            && Pos.start(t.getBody()) <= Pos.start(site) && Pos.end(site) <= Pos.end(t.getBody());
                    return super.visitTry(t, p);
                }
            }.visit(c.decl(), 0);
            if (around[0]) {
                return true;
            }
        }
        return false;
    }

    /**
     * A value that reads the same wherever it is evaluated in the method: a variable, optionally followed by record
     * accessors or getters without arguments ({@code row.pledgeId()}). {root name, accessor suffix}, or null.
     */
    private static String[] stable(Expression e) {
        if (e instanceof J.Identifier id) {
            return new String[]{id.getSimpleName(), ""};
        }
        if (e instanceof J.MethodInvocation mi && mi.getSelect() != null && mi.getArguments().stream().allMatch(x -> x instanceof J.Empty)
                && mi.getMethodType() != null && (mi.getMethodType().getDeclaringType().getKind() == org.openrewrite.java.tree.JavaType.FullyQualified.Kind.Record
                || mi.getSimpleName().matches("(get|is)[A-Z].*"))) {
            String[] inner = stable(mi.getSelect());
            return inner == null ? null : new String[]{inner[0], inner[1] + "." + mi.getSimpleName() + "()"};
        }
        return null;
    }

    private static int paramIndex(MethodInfo m, String name) {
        List<Statement> params = m.decl().getParameters();
        for (int i = 0; i < params.size(); i++) {
            if (params.get(i) instanceof J.VariableDeclarations vd && vd.getVariables().stream().anyMatch(v -> v.getSimpleName().equals(name))) {
                return i;
            }
        }
        return -1;
    }

    /**
     * {@code value} is a parameter of {@code m} that is a source parameter of the vocabulary key and is never reassigned
     * in the method: what the caller scoped flows in unchanged. A derived value ({@code k.trim()}, {@code "L-" + k}, a
     * local) is not (#79).
     */
    static boolean carriesSourceValue(Policy policy, MethodInfo m, String key, Expression value) {
        if (!(value instanceof J.Identifier id)) {
            return false;
        }
        Policy.KeySource src = policy.mdcRegistry().get(key);
        boolean param = m.decl().getParameters().stream().anyMatch(p -> p instanceof J.VariableDeclarations vd
                && vd.getVariables().stream().anyMatch(v -> v.getSimpleName().equals(id.getSimpleName())));
        return param && src != null && src.params().contains(id.getSimpleName()) && !assigned(m, id.getSimpleName());
    }

    /** {@code name} is assigned somewhere in the method ({@code k = k.trim()}, {@code k += ..}, {@code k++}): no longer what came in. */
    private static boolean assigned(MethodInfo m, String name) {
        boolean[] found = {false};
        new org.openrewrite.java.JavaIsoVisitor<Integer>() {
            @Override
            public J.Assignment visitAssignment(J.Assignment a, Integer p) {
                found[0] |= a.getVariable() instanceof J.Identifier v && v.getSimpleName().equals(name);
                return super.visitAssignment(a, p);
            }

            @Override
            public J.AssignmentOperation visitAssignmentOperation(J.AssignmentOperation a, Integer p) {
                found[0] |= a.getVariable() instanceof J.Identifier v && v.getSimpleName().equals(name);
                return super.visitAssignmentOperation(a, p);
            }

            @Override
            public J.Unary visitUnary(J.Unary u, Integer p) {
                found[0] |= u.getOperator().isModifying() && u.getExpression() instanceof J.Identifier v && v.getSimpleName().equals(name);
                return super.visitUnary(u, p);
            }
        }.visit(m.decl().getBody(), 0);
        return found[0];
    }

    /** The value an MDC write sets: the argument after its key ({@code put(k, v)}, {@code putCloseable(k, v)}, {@code of(.., k, v, ..)}). */
    static Expression valueOf(MdcOp op) {
        List<Expression> args = op.call().getArguments();
        int at = op.keyExpr() == null ? -1 : args.indexOf(op.keyExpr());
        return at >= 0 && at + 1 < args.size() ? args.get(at + 1) : null;
    }

    /** The try statement whose body is {@code block}, if any. */
    private static J.Try enclosingTry(MethodInfo m, J.Block block) {
        if (block == null) {
            return null;
        }
        J.Try[] found = new J.Try[1];
        new org.openrewrite.java.JavaIsoVisitor<Integer>() {
            @Override
            public J.Try visitTry(J.Try t, Integer p) {
                if (t.getBody() == block) {
                    found[0] = t;
                }
                return super.visitTry(t, p);
            }
        }.visit(m.decl(), 0);
        return found[0];
    }

    private static Edit removeImport(SourceFileInfo f, String fqn) {
        String text = f.text();
        int i = text.indexOf("import " + fqn + ";");
        return i < 0 ? null : Edit.replace(f.relPath(), i, Text.nextLine(text, i), "");
    }

    private static boolean within(List<Statement> stmts, int from, int to, J call, Statement tryStmt) {
        int s = Pos.start(call);
        for (int k = from; k < to; k++) {
            if (Pos.start(stmts.get(k)) <= s && s < Pos.end(stmts.get(k))) {
                return true;
            }
        }
        return tryStmt != null && Pos.start(tryStmt) <= s && s < Pos.end(tryStmt);
    }

    private static int indexOf(List<Statement> stmts, J call) {
        for (int k = 0; k < stmts.size(); k++) {
            if (stmts.get(k) == call) {
                return k;
            }
        }
        return -1;
    }

    /** {@code key == null} matches any key (and argument-less calls such as clear()). */
    private static boolean isMdc(Statement s, String method, String key) {
        return s instanceof J.MethodInvocation mi && mi.getSimpleName().equals(method)
                && mi.getMethodType() != null && "org.slf4j.MDC".equals(mi.getMethodType().getDeclaringType().getFullyQualifiedName())
                && (key == null || !mi.getArguments().isEmpty() && mi.getArguments().getFirst() instanceof J.Literal l && key.equals(l.getValue()));
    }

    private static String src(SourceFileInfo f, Expression e) {
        return f.slice(Pos.start(e), Pos.end(e));
    }
}
