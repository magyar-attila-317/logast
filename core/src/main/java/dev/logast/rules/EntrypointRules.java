package dev.logast.rules;

import dev.logast.graph.CallGraph;
import dev.logast.model.Entrypoint;
import dev.logast.model.Level;
import dev.logast.model.MethodInfo;
import dev.logast.orders.Edit;
import dev.logast.orders.Slot;
import dev.logast.parse.Pos;
import dev.logast.parse.SourceFileInfo;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.Statement;
import org.openrewrite.java.tree.TypeUtils;

import java.util.*;

/**
 * Entrypoint-level fixes. One order per entrypoint wraps the body in an {@code ObservedContext} (when the
 * framework does not observe it) and/or an {@code MdcScope} with the domain keys its parameters carry, and
 * adds an entry log when nothing below the entrypoint logs at INFO or above.
 */
final class EntrypointRules {
    private final RuleContext ctx;

    EntrypointRules(RuleContext ctx) {
        this.ctx = ctx;
    }

    void run() {
        for (Entrypoint ep : ctx.a.entrypoints) {
            if (ep.kind() == Entrypoint.Kind.BATCH || ep.method().owner().fqn().startsWith(ctx.a.supportPackage + ".")) {
                continue; // wrapped at runtime by logging-support's runner post-processor
            }
            entrypoint(ep);
        }
        for (var u : ctx.a.unresolvedHandlers) {
            if (!ctx.enabled(Rules.ENTRYPOINT_NOT_OBSERVED) || ctx.ignored(u.site().file(), u.line(), Rules.ENTRYPOINT_NOT_OBSERVED)) {
                continue;
            }
            ctx.order(Rules.ENTRYPOINT_NOT_OBSERVED, u.site(), u.line(), "handler-unresolved")
                    .title(u.what() + ": the handler Redis calls is not known (" + u.why() + "), so it is not traced or scoped")
                    .suggestion("Name the delegate method with a constant (new MessageListenerAdapter(delegate, \"onPledge\")), or "
                            + "implement MessageListener in the listener class: logast then wraps the handler in an ObservedContext "
                            + "with its MDC. RedisMessageListenerContainer opens no observation of its own.")
                    .doneWhen("the delegate method is resolvable, or the line is marked // logast:ignore ENTRYPOINT_NOT_OBSERVED");
        }
    }

    private void entrypoint(Entrypoint ep) {
        MethodInfo m = ep.method();
        SourceFileInfo f = m.file();
        String text = f.text();
        int line = m.line();

        boolean batch = dev.logast.graph.EntrypointFinder.batch(m, null)
                || m.decl().getLeadingAnnotations().stream().anyMatch(a -> dev.logast.graph.EntrypointFinder.batch(m, a));
        boolean observed = ep.observedByFramework()
                || ep.kind() == Entrypoint.Kind.KAFKA && ctx.kafkaListenerObservationPlanned && !batch
                || ep.kind() == Entrypoint.Kind.RABBIT && ctx.rabbitListenerObservationPlanned && !batch;
        // a Mono/Flux method only assembles the pipeline: a wrapper or scope around it would be gone when it runs
        boolean reactive = ctx.reactive(m);
        boolean notObserved = !observed && !reactive && ctx.enabled(Rules.ENTRYPOINT_NOT_OBSERVED)
                && !ctx.ignored(f, line, Rules.ENTRYPOINT_NOT_OBSERVED) && !alreadyObserved(m);
        if (notObserved && ep.kind() == Entrypoint.Kind.GRPC && !completesInBody(m)) {
            // a wrapper would stop its observation and log "completed" when the method returns, before onCompleted
            notObserved = false;
            ctx.order(Rules.ENTRYPOINT_NOT_OBSERVED, m, line, "entry-grpc")
                    .title(ep.label() + " is not observed (Spring gRPC observation off or absent), and it answers through its "
                            + "StreamObserver after the method returns")
                    .suggestion("Register Spring gRPC's ObservationGrpcServerInterceptor (spring.grpc.server.observation.enabled: "
                            + "true, the default) or io.micrometer.core.instrument.binder.grpc.ObservationGrpcServerInterceptor as a "
                            + "server interceptor: it observes the call until it closes. Not wrapped automatically: an observation "
                            + "around the method would end before onCompleted.")
                    .doneWhen("the call is observed by an interceptor, or the line is marked // logast:ignore ENTRYPOINT_NOT_OBSERVED");
        }
        List<DomainValues.Value> missing = ctx.enabled(Rules.MISSING_DOMAIN_MDC) && !reactive
                && !ctx.ignored(f, line, Rules.MISSING_DOMAIN_MDC) && !ctx.legacyMdc(m)
                ? entryValues(ep, m).stream()
                .filter(v -> !ctx.a.paths.mdcInside(m).contains(v.key())).map(v -> nullSafe(m, v)).toList()
                : List.of();
        if (reactive) {
            reactiveMdc(ep, m);
        }
        boolean readEndpoint = ep.kind() == Entrypoint.Kind.HTTP && (ep.label().startsWith("GET ") || ep.label().startsWith("HEAD "));
        // a run's started and outcome lines belong to JOB_OUTCOME_MISSING: split between two rules, the outcome line it adds
        // ended the silence, the started line lost its owner, and the order came back after its own fix
        boolean run = LifecyclePlan.kindOf(ep) == LifecyclePlan.Kind.RUN;
        boolean silent = !readEndpoint && !(run && ctx.enabled(Rules.JOB_OUTCOME_MISSING))
                && ctx.enabled(Rules.ENTRYPOINT_SILENT) && !ctx.ignored(f, line, Rules.ENTRYPOINT_SILENT)
                && !LifecyclePlan.ownFlowLogs(ctx, m, Level.parse(ctx.policy.logging().minEntrypointLevel()));
        if (!notObserved && missing.isEmpty() && !silent) {
            return;
        }

        String rule = notObserved ? Rules.ENTRYPOINT_NOT_OBSERVED
                : !missing.isEmpty() ? Rules.MISSING_DOMAIN_MDC : Rules.ENTRYPOINT_SILENT;
        OrderBuilder b = ctx.order(rule, m, line, "entry");
        if (!missing.isEmpty()) {
            b.fix(Rules.MISSING_DOMAIN_MDC);
        }
        if (silent) {
            b.fix(Rules.ENTRYPOINT_SILENT);
            ctx.silentPlanned.add(m.key());
        }
        b.title(title(ep, notObserved, missing, silent));

        int[] inner = m.decl().getBody() == null ? null : JavaEdits.blockInner(text, m.decl().getBody());
        if (inner == null) {
            b.suggestion("Method body layout not supported for automatic wrapping; wrap it manually.");
            return;
        }
        String unit = Text.indentUnit(text);
        String indent = Text.indentAt(text, Pos.start(m.decl().getBody())) + unit;
        String body = text.substring(inner[0], inner[1]);
        Set<String> taken = ctx.taken(m, inner[0]);

        StringBuilder top = new StringBuilder();
        List<Edit> extra = new ArrayList<>();
        if (silent) {
            JavaEdits.LoggerPlan logger = ctx.logger(m);
            if (logger.name() == null) {
                b.suggestion("Cannot add a logger to " + m.owner().simpleName() + " (interface/enum).");
                silent = false;
            } else {
                extra.addAll(logger.edits());
                top.append(logger.name()).append('.').append(ctx.policy.logging().entryLevel()).append("(\"{{MSG}}\");\n");
                String subject = Wording.entrySubject(ep);   // #231: written when nobody writes a text
                if (subject != null) {
                    // its family sees the line in this pass: siblings get theirs now, not on the rescan (LIFECYCLE_UNEVEN)
                    ctx.generatedStart.put(m.key(), subject);
                }
                b.slot("MSG", new Slot("Entry log for " + ep.label() + " (" + m.label()
                        + "): what is starting, in domain terms, e.g. 'Order shipment processing started'.",
                        List.of(), ctx.glossary.terms(), null,
                        ctx.phrasing.canonicalOf("started") == null ? null : "started", ctx.phrasing.canonicalOf("started"),
                        subject == null ? null : Wording.event(ctx, subject, "started")));
            }
        }

        String replacement;
        if (notObserved) {
            List<String> chain = new ArrayList<>();
            String quartz = ep.kind() == Entrypoint.Kind.QUARTZ ? quartzContext(m) : null;
            if (quartz != null) {
                // #234: a job started with a trace in its data (a chain's previous step) continues it; in a chain, the
                // job keeps its own trace where the JobListener that starts the next step finds it
                chain.add(".remoteParent(TraceContextEnv.carrier(" + quartz + ".getMergedJobDataMap()))");
                if (quartzChain()) {
                    String put = quartz + ".put(TraceContextEnv.KEY, TraceContextEnv.current());\n";
                    top.insert(0, top.isEmpty() ? put : put + indent + unit);
                }
                extra.addAll(JavaEdits.imports(f, ctx.support("TraceContextEnv")));
            }
            for (DomainValues.Value v : missing) {
                chain.add(".mdc(\"" + v.key() + "\", " + v.expr() + ")");
            }
            if (ep.kind() == Entrypoint.Kind.KAFKA || ep.kind() == Entrypoint.Kind.JMS || ep.kind() == Entrypoint.Kind.RABBIT
                    || ep.kind() == Entrypoint.Kind.WEBSOCKET || ep.kind() == Entrypoint.Kind.HTTP || ep.kind() == Entrypoint.Kind.SQS
                    || ep.kind() == Entrypoint.Kind.STREAM || ep.kind() == Entrypoint.Kind.ITEM || ep.kind() == Entrypoint.Kind.REDIS
                    || ep.kind() == Entrypoint.Kind.INTEGRATION || ep.kind() == Entrypoint.Kind.GRPC) {
                chain.add(".debugOnSuccess()"); // one per message/request: "completed" at DEBUG; failures stay ERROR
            }
            if (ep.kind() == Entrypoint.Kind.EVENT && !m.hasAnnotation("org.springframework.scheduling.annotation.Async")
                    && !m.owner().decl().getLeadingAnnotations().stream().anyMatch(a -> a.getSimpleName().equals("Async"))) {
                // runs in the publisher's call: the exception goes back to code that logs it; one failure = one ERROR
                chain.add(".failureLoggedByCaller()");
            }
            replacement = observedWrap(m, observationName(m), chain, (top.isEmpty() ? "" : indent + unit + top) + Text.reindent(body, unit),
                    indent, unit, taken, ctx.supportHasRun());
            extra.addAll(JavaEdits.imports(f, ctx.support("ObservedContext")));
            ctx.dependOnStarter(b);
            ctx.observedWrapped.add(m.key());   // its runtime line is the outcome; a plain MdcScope logs none
        } else if (!missing.isEmpty() && top.isEmpty() && observedMdcAt(m, text, inner[0], inner[1]) >= 0) {
            // #20: the method is one ObservedContext: its MDC goes into the chain, not around it
            replacement = null;
            b.edit(Edit.insert(f.relPath(), observedMdcAt(m, text, inner[0], inner[1]), observedMdc(m, text, missing)));
            ctx.dependOnStarter(b);
        } else if (!missing.isEmpty()) {
            String var = unique("mdc", taken);
            replacement = indent + "try (var " + var + " = " + mdcScope(missing) + ") {\n"
                    + (top.isEmpty() ? "" : indent + unit + top)
                    + Text.reindent(body, unit)
                    + indent + "}\n";
            extra.addAll(JavaEdits.imports(f, ctx.support("MdcScope")));
            ctx.dependOnStarter(b);
        } else {
            replacement = null;
            // inside the MDC scopes the body opens with, so the started line carries their keys (#12)
            J.Block at = startBlock(m);
            int[] atInner = JavaEdits.blockInner(text, at);
            if (atInner == null) {
                at = m.decl().getBody();
                atInner = inner;
            }
            b.edit(Edit.insert(f.relPath(), atInner[0], Text.indentAt(text, Pos.start(at)) + unit + top));
        }
        if (replacement != null) {
            ctx.wrappedMethods.add(m.key());
            b.edit(Edit.replace(f.relPath(), inner[0], inner[1], replacement));
        }
        b.edits(extra);
        b.context(ctx.context(m, inner[0], Text.oneLine(text.substring(Pos.start(m.decl()), Math.min(inner[0] + 200, text.length())), 200), null));
        if (!missing.isEmpty()) {
            ctx.plannedEntryKeys.put(m.key(), new TreeSet<>(missing.stream().map(DomainValues.Value::key).toList()));
            ctx.entryOrders.put(m.key(), b.id());
        }
    }

    /**
     * The MDC values an entrypoint's parameters give: by name and configured type, and for a request or message the
     * getter / record accessor its payload carries (#15, {@link DomainValues#fromParams}). Not for in-process application
     * events (EVENT): their listeners run inside the publisher's call, which has its own scope; reading their payloads
     * is a separate decision.
     */
    private List<DomainValues.Value> entryValues(Entrypoint ep, MethodInfo m) {
        if (ep.kind() == Entrypoint.Kind.QUARTZ) {
            return quartzValues(m);
        }
        return ep.kind() == Entrypoint.Kind.EVENT ? ctx.domain.fromVars(params(m)) : ctx.domain.fromParams(params(m));
    }

    /** The name of a Quartz job method's {@code JobExecutionContext} parameter, or null. */
    static String quartzContext(MethodInfo m) {
        for (Statement s : m.decl().getParameters()) {
            if (s instanceof J.VariableDeclarations vd && vd.getTypeExpression() != null
                    && (TypeUtils.isOfClassType(vd.getType(), "org.quartz.JobExecutionContext")
                    || vd.getTypeExpression().printTrimmed().replaceAll("\\s+", "").endsWith("JobExecutionContext"))) {
                return vd.getVariables().getFirst().getSimpleName();
            }
        }
        return null;
    }

    private Boolean quartzChain;

    /** #234: a JobListener of the project starts jobs ({@code triggerJob}): the jobs run as a chain. */
    private boolean quartzChain() {
        if (quartzChain == null) {
            quartzChain = ctx.a.index.classes().stream().anyMatch(c -> TracingRules.jobListener(c)
                    && c.file().text().contains("triggerJob("));
        }
        return quartzChain;
    }

    private static final java.util.Set<String> DATA_GETTERS = java.util.Set.of("get", "getString", "getInt", "getIntValue",
            "getLong", "getLongValue", "getBoolean", "getBooleanValue", "getDouble", "getDoubleValue", "getIntegerFromString",
            "getLongFromString", "getBooleanFromString", "getDoubleFromString", "getFloat", "getFloatValue", "getChar");

    /**
     * #233: a Quartz job's MDC: the name of the JobDetail it runs as ({@code jobName}: one class runs under several
     * JobDetails and in chains), and each JobDataMap key its {@code execute} reads by a literal or constant name, read
     * again from the merged map (the job's data with the trigger's on top, as Quartz merges it).
     */
    private List<DomainValues.Value> quartzValues(MethodInfo m) {
        String context = quartzContext(m);
        if (context == null || m.decl().getBody() == null) {
            return List.of();
        }
        List<DomainValues.Value> out = new ArrayList<>();
        out.add(new DomainValues.Value(ctx.policy.styledKey("jobName"), context + ".getJobDetail().getKey().getName()"));
        Set<String> maps = new HashSet<>();   // local variables holding the job's data map
        Set<String> keys = new LinkedHashSet<>();
        new org.openrewrite.java.JavaIsoVisitor<Integer>() {
            @Override
            public J.VariableDeclarations.NamedVariable visitVariable(J.VariableDeclarations.NamedVariable v, Integer p) {
                if (v.getInitializer() != null && dataMap(v.getInitializer(), maps)) {
                    maps.add(v.getSimpleName());
                }
                return super.visitVariable(v, p);
            }

            @Override
            public J.MethodInvocation visitMethodInvocation(J.MethodInvocation mi, Integer p) {
                if (DATA_GETTERS.contains(mi.getSimpleName()) && mi.getSelect() != null && dataMap(mi.getSelect(), maps)
                        && mi.getArguments().size() == 1) {
                    String key = dev.logast.graph.Constants.string(ctx.a.index, mi.getArguments().getFirst());
                    if (key != null && key.matches("[A-Za-z][A-Za-z0-9_.-]*")) {
                        keys.add(key);
                    }
                }
                return super.visitMethodInvocation(mi, p);
            }
        }.visit(m.decl().getBody(), 0);
        for (String k : keys) {
            String mdc = ctx.policy.styledKey(k);
            if (out.stream().noneMatch(v -> v.key().equals(mdc))) {
                out.add(new DomainValues.Value(mdc, context + ".getMergedJobDataMap().get(" + Text.javaString(k) + ")"));
            }
        }
        return out;
    }

    /** {@code context.getMergedJobDataMap()}, {@code context.getJobDetail().getJobDataMap()}, or a variable holding one. */
    private static boolean dataMap(Expression e, Set<String> maps) {
        if (e instanceof J.Identifier id) {
            return maps.contains(id.getSimpleName());
        }
        return e instanceof J.MethodInvocation mi && (mi.getSimpleName().equals("getMergedJobDataMap")
                || mi.getSimpleName().equals("getJobDataMap"));
    }

    /**
     * MISSING_DOMAIN_MDC on a Mono/Flux entrypoint (#14), report only: an MdcScope around the body would set the keys
     * while the pipeline is assembled and remove them before it runs (on other threads), so the lines in the operators
     * would still lack them. Automatic context propagation does not help by itself either: Reactor restores only the
     * ThreadLocals registered in the ContextRegistry (Micrometer's observation, and through it the trace ids), not
     * arbitrary MDC keys. Sources: Reactor reference › Context-Propagation Support; context-propagation's
     * {@code io.micrometer.context.integration.Slf4jThreadLocalAccessor} (key {@code cp.slf4j}, the MDC keys it is built for).
     */
    private void reactiveMdc(Entrypoint ep, MethodInfo m) {
        if (!ctx.enabled(Rules.MISSING_DOMAIN_MDC) || ctx.ignored(m.file(), m.line(), Rules.MISSING_DOMAIN_MDC) || ctx.legacyMdc(m)) {
            return;
        }
        List<String> keys = entryValues(ep, m).stream().map(DomainValues.Value::key)
                .filter(k -> !ctx.a.paths.mdcInside(m).contains(k)).distinct().toList();
        if (keys.isEmpty()) {
            return;
        }
        ctx.order(Rules.MISSING_DOMAIN_MDC, m, m.line(), "entry-reactive")
                .title(String.join(", ", keys) + " not in MDC for " + m.label() + ": it returns a Mono/Flux, so an MdcScope around "
                        + "the body would end before the pipeline runs")
                .suggestion("Put the value into the Reactor Context and let Reactor restore it as MDC in every operator: register "
                        + "new Slf4jThreadLocalAccessor(" + String.join(", ", keys.stream().map(k -> "\"" + k + "\"").toList())
                        + ") (io.micrometer.context.integration) once in ContextRegistry, switch on automatic context propagation "
                        + "(REACTIVE_CONTEXT_LOSS), and end the pipeline with .contextWrite(Context.of(Slf4jThreadLocalAccessor.KEY, "
                        + "Map.of(" + String.join(", ", keys.stream().map(k -> "\"" + k + "\", " + k).toList()) + "))). "
                        + "Or carry it as baggage (management.tracing.baggage.correlation.fields). Not applied automatically: "
                        + "it changes how the pipeline is built.")
                .doneWhen("the key reaches MDC inside the pipeline (an MDC scope below the entrypoint, or the values come through "
                        + "the Reactor Context), or the line is marked // logast:ignore MISSING_DOMAIN_MDC");
    }

    /**
     * The block a started line goes into: the body, or the body of the MDC scope it opens with ({@code try (var mdc =
     * MdcScope.of(..))} / {@code MDC.putCloseable}, nested ones too), so the line logs with the keys the method sets.
     */
    private static J.Block startBlock(MethodInfo m) {
        J.Block block = m.decl().getBody();
        while (!block.getStatements().isEmpty() && block.getStatements().getFirst() instanceof J.Try t && t.getResources() != null
                && !t.getResources().isEmpty() && t.getResources().stream().allMatch(r -> mdcScope(m, r))) {
            block = t.getBody();
        }
        return block;
    }

    private static boolean mdcScope(MethodInfo m, J.Try.Resource r) {
        return r.getVariableDeclarations() instanceof J.VariableDeclarations vd && vd.getVariables().size() == 1
                && m.facts().mdc.stream().anyMatch(op -> op.kind() == dev.logast.model.MdcOp.Kind.PUT_CLOSEABLE
                && op.call() == vd.getVariables().getFirst().getInitializer());
    }

    /**
     * The body inside an ObservedContext: {@code ObservedContext.entrypoint(name)…run(() -> { body });} (or
     * {@code return …call(…)} for a result). Falls back to the try/catch/finally form where a lambda would not compile:
     * a parameter reassigned in the body (not effectively final), or several checked exceptions declared.
     *
     * @param inner the body, already indented one level deeper than {@code indent}
     */
    static String observedWrap(MethodInfo m, String name, List<String> chain, String inner, String indent, String unit, Set<String> taken,
                               boolean runAvailable) {
        String head = "ObservedContext.entrypoint(\"" + name + "\")";
        String cont = indent + unit + unit;
        if (runAvailable && lambdaFits(m)) {
            boolean result = !(m.decl().getReturnTypeExpression() instanceof J.Primitive p && p.getType() == JavaType.Primitive.Void);
            StringBuilder sb = new StringBuilder(indent).append(result ? "return " : "").append(head);
            chain.forEach(c -> sb.append('\n').append(cont).append(c));
            sb.append('\n').append(cont).append(result ? ".call(() -> {\n" : ".run(() -> {\n");
            sb.append(Text.reindent(inner, unit + unit));
            sb.append(cont).append("});\n");
            return sb.toString();
        }
        String obs = unique("observed", taken);
        String err = unique("t", taken);
        StringBuilder header = new StringBuilder("ObservedContext " + obs + " = " + head);
        chain.forEach(c -> header.append('\n').append(cont).append(c));
        return indent + header + ".start();\n"
                + indent + "try (" + obs + ") {\n"
                + inner
                + indent + "} catch (Throwable " + err + ") {\n"
                + indent + unit + obs + ".error(" + err + ");\n"
                + indent + unit + "throw " + err + ";\n"
                + indent + "} finally {\n"
                + indent + unit + obs + ".stop();\n"
                + indent + "}\n";
    }

    private static boolean lambdaFits(MethodInfo m) {
        if (m.decl().getThrows() != null && m.decl().getThrows().size() > 1 || m.decl().getBody() == null) {
            return false;
        }
        Set<String> params = params(m).keySet();
        boolean[] reassigned = {false};
        new org.openrewrite.java.JavaIsoVisitor<Integer>() {
            private void check(Expression target) {
                if (target instanceof J.Identifier id && params.contains(id.getSimpleName())) {
                    reassigned[0] = true;
                }
            }

            @Override
            public J.Assignment visitAssignment(J.Assignment a, Integer p) {
                check(a.getVariable());
                return super.visitAssignment(a, p);
            }

            @Override
            public J.AssignmentOperation visitAssignmentOperation(J.AssignmentOperation a, Integer p) {
                check(a.getVariable());
                return super.visitAssignmentOperation(a, p);
            }

            @Override
            public J.Unary visitUnary(J.Unary u, Integer p) {
                if (u.getOperator().isModifying()) {
                    check(u.getExpression());
                }
                return super.visitUnary(u, p);
            }
        }.visit(m.decl().getBody(), 0);
        return !reassigned[0];
    }

    /**
     * #20: where {@code .mdc(k, v)} goes instead of an MdcScope around {@code [start, end)}: right after
     * {@code ObservedContext.entrypoint(..)} when the method is one ObservedContext chain ({@code …run(..)} /
     * {@code return …call(..)}) and the range is its whole body or the whole body of the chain's lambda. One scope then,
     * not an MdcScope around (or inside) the observation. -1 when the range is anything else.
     */
    static int observedMdcAt(MethodInfo m, String text, int start, int end) {
        J.Block body = m.decl().getBody();
        if (body == null || body.getStatements().size() != 1) {
            return -1;
        }
        Statement st = body.getStatements().getFirst();
        Expression e = st instanceof J.Return r ? r.getExpression() : st instanceof J.MethodInvocation mi ? mi : null;
        if (!(e instanceof J.MethodInvocation last) || !(last.getSimpleName().equals("run") || last.getSimpleName().equals("call"))) {
            return -1;
        }
        J.MethodInvocation head = last;
        while (head.getSelect() instanceof J.MethodInvocation sel) {
            head = sel;
        }
        if (!head.getSimpleName().equals("entrypoint") || !(head.getSelect() instanceof J.Identifier id)
                || !id.getSimpleName().equals("ObservedContext")) {
            return -1;
        }
        // the same code, whatever whitespace the range starts or ends with (a rule may replace whole lines)
        String range = text.substring(start, end).strip();
        int[] whole = JavaEdits.blockInner(text, body);
        boolean wholeBody = whole != null && text.substring(whole[0], whole[1]).strip().equals(range);
        boolean wholeLambda = false;
        List<Expression> args = last.getArguments();
        if (args.size() == 1 && args.getFirst() instanceof J.Lambda l && l.getBody() instanceof J.Block lb) {
            int[] li = JavaEdits.blockInner(text, lb);
            wholeLambda = li != null && !range.isEmpty() && text.substring(li[0], li[1]).strip().equals(range);
        }
        return wholeBody || wholeLambda ? Pos.end(head) : -1;
    }

    /** The {@code .mdc(k, v)} calls for {@link #observedMdcAt}, one per line at the chain's indentation. */
    static String observedMdc(MethodInfo m, String text, List<DomainValues.Value> values) {
        String cont = Text.indentAt(text, Pos.start(m.decl().getBody().getStatements().getFirst())) + Text.indentUnit(text) + Text.indentUnit(text);
        StringBuilder sb = new StringBuilder();
        values.forEach(v -> sb.append('\n').append(cont).append(".mdc(\"").append(v.key()).append("\", ").append(v.expr()).append(')'));
        return sb.toString();
    }

    static String mdcScope(List<DomainValues.Value> values) {
        // one call with all pairs: values are evaluated before MDC is touched (no half-open scope on exceptions)
        return "MdcScope.of(" + String.join(", ", values.stream().map(v -> "\"" + v.key() + "\", " + v.expr()).toList()) + ")";
    }

    private String title(Entrypoint ep, boolean notObserved, List<DomainValues.Value> missing, boolean silent) {
        List<String> parts = new ArrayList<>();
        if (notObserved) {
            parts.add("no Observation/trace is started");
        }
        if (!missing.isEmpty()) {
            parts.add("MDC lacks " + String.join(", ", missing.stream().map(DomainValues.Value::key).toList()));
        }
        if (silent) {
            parts.add("its own flow logs nothing at INFO (shared code deeper down and catch blocks do not count)");
        }
        return ep.label() + ": " + String.join("; ", parts);
    }

    /**
     * The scope opens before the method's own code runs, so its null checks have not happened yet (#49): an accessor
     * on a parameter that may be null ({@code @Nullable}, {@code required = false}, or null-checked in the method, like a
     * Kafka tombstone) is guarded; MdcScope leaves null values out.
     */
    static DomainValues.Value nullSafe(MethodInfo m, DomainValues.Value v) {
        int dot = v.expr().indexOf('.');
        if (dot < 0) {
            return v;
        }
        String root = v.expr().substring(0, dot);
        return mayBeNull(m, root) ? new DomainValues.Value(v.key(), root + " == null ? null : " + v.expr()) : v;
    }

    private static boolean mayBeNull(MethodInfo m, String name) {
        for (Statement s : m.decl().getParameters()) {
            if (s instanceof J.VariableDeclarations vd && vd.getVariables().stream().anyMatch(x -> x.getSimpleName().equals(name))) {
                for (J.Annotation a : vd.getLeadingAnnotations()) {
                    String text = a.printTrimmed().replaceAll("\\s+", "");
                    if (a.getSimpleName().equals("Nullable") || text.contains("required=false")) {
                        return true;
                    }
                }
            }
        }
        if (m.decl().getBody() == null) {
            return false;
        }
        boolean[] checked = {false};
        new org.openrewrite.java.JavaIsoVisitor<Integer>() {
            @Override
            public J.Binary visitBinary(J.Binary b, Integer p) {
                if ((b.getOperator() == J.Binary.Type.Equal || b.getOperator() == J.Binary.Type.NotEqual)
                        && (isName(b.getLeft(), name) && isNull(b.getRight()) || isName(b.getRight(), name) && isNull(b.getLeft()))) {
                    checked[0] = true;
                }
                return super.visitBinary(b, p);
            }

            @Override
            public J.MethodInvocation visitMethodInvocation(J.MethodInvocation mi, Integer p) {
                if (Set.of("isNull", "nonNull", "ofNullable").contains(mi.getSimpleName())
                        && mi.getArguments().stream().anyMatch(x -> isName(x, name))) {
                    checked[0] = true;
                }
                return super.visitMethodInvocation(mi, p);
            }
        }.visit(m.decl().getBody(), 0);
        return checked[0];
    }

    private static boolean isName(org.openrewrite.java.tree.Expression e, String name) {
        return e instanceof J.Identifier id && id.getSimpleName().equals(name);
    }

    private static boolean isNull(org.openrewrite.java.tree.Expression e) {
        return e instanceof J.Literal l && l.getValue() == null && "null".equals(l.getValueSource());
    }

    /**
     * A gRPC method that ends its call itself: {@code responseObserver.onCompleted()} in its own body (not in a lambda or
     * an inner class), and the observer is not handed to another call that could complete it later.
     */
    static boolean completesInBody(MethodInfo m) {
        if (!(m.decl().getParameters().getLast() instanceof J.VariableDeclarations vd) || vd.getVariables().isEmpty()) {
            return false;
        }
        String observer = vd.getVariables().getFirst().getSimpleName();
        boolean[] completes = {false};
        boolean[] escapes = {false};
        new org.openrewrite.java.JavaIsoVisitor<Integer>() {
            @Override
            public J.Lambda visitLambda(J.Lambda l, Integer p) {
                if (l.printTrimmed().contains(observer)) {
                    escapes[0] = true;
                }
                return l;
            }

            @Override
            public J.NewClass visitNewClass(J.NewClass nc, Integer p) {
                if (nc.printTrimmed().contains(observer)) {
                    escapes[0] = true;
                }
                return nc;
            }

            @Override
            public J.MethodInvocation visitMethodInvocation(J.MethodInvocation mi, Integer p) {
                if (mi.getSimpleName().equals("onCompleted") && mi.getSelect() instanceof J.Identifier id && id.getSimpleName().equals(observer)) {
                    completes[0] = true;
                }
                if (mi.getArguments().stream().anyMatch(a -> a instanceof J.Identifier id && id.getSimpleName().equals(observer))) {
                    escapes[0] = true;
                }
                return super.visitMethodInvocation(mi, p);
            }
        }.visit(m.decl().getBody(), 0);
        return completes[0] && !escapes[0];
    }

    private static Map<String, JavaType> params(MethodInfo m) {
        Map<String, JavaType> out = new LinkedHashMap<>();
        for (Statement s : m.decl().getParameters()) {
            if (s instanceof J.VariableDeclarations vd) {
                vd.getVariables().forEach(v -> out.put(v.getSimpleName(), v.getType()));
            }
        }
        return out;
    }

    /**
     * The method observes itself: ObservedContext / Observation in its body, or {@code @Observed} / {@code @NewSpan} while
     * Spring Boot processes them (property and aspectjweaver, or an order of this scan adds them). An inactive annotation
     * does nothing at runtime, so it does not count (OBSERVED_ANNOTATION_INACTIVE).
     */
    private boolean alreadyObserved(MethodInfo m) {
        String body = m.decl().getBody() == null ? "" : m.file().slice(Pos.start(m.decl().getBody()), Pos.end(m.decl().getBody()));
        return body.contains("ObservedContext.") || body.contains("Observation.start(") || body.contains("Observation.createNotStarted(")
                || ctx.observedAnnotationsActive && TracingRules.observedAnnotation(m);
    }

    /** How deep a log may sit and still count as the entrypoint's own: the endpoint, its service, one level below. */
    static final int OWN_FLOW_DEPTH = 2;

    /**
     * Whether the entrypoint's own flow logs: a line at {@code min} or above on the normal path (not in a catch) of
     * the entrypoint or of a method at most {@link #OWN_FLOW_DEPTH} calls below it. Lines deeper down sit in shared
     * code (event publishers, audit, helpers) that many flows reach: they don't tell which flow ran.
     */
    private static String observationName(MethodInfo m) {
        return Text.kebab(m.owner().simpleName()) + "." + Text.kebab(m.name());
    }

    static String unique(String base, Set<String> taken) {
        String name = base;
        for (int i = 2; taken.contains(name); i++) {
            name = base + i;
        }
        return name;
    }
}
