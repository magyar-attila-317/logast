package dev.logast.rules;

import dev.logast.model.AsyncHop;
import dev.logast.model.LogCall;
import dev.logast.model.MethodInfo;
import dev.logast.orders.Edit;
import dev.logast.parse.Pos;
import org.openrewrite.Cursor;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.TypeUtils;
import org.openrewrite.java.tree.Statement;

import java.util.*;
import java.util.List;

/**
 * ASYNC_CONTEXT_LOSS for raw executors and CompletableFuture. (@Async and Spring executors are covered by the
 * TaskDecorator in logging-support, recorded as locations of the SETUP_SUPPORT_STARTER order.)
 */
final class AsyncRules {
    private final RuleContext ctx;

    AsyncRules(RuleContext ctx) {
        this.ctx = ctx;
    }

    void run() {
        if (ctx.enabled(Rules.SSE_BROADCAST_CONTEXT)) {
            ctx.methods().forEach(this::broadcasts);
        }
        boolean scheduled = ctx.enabled(Rules.SCHEDULED_TASK_UNTRACED);
        if (!ctx.enabled(Rules.ASYNC_CONTEXT_LOSS) && !scheduled) {
            return;
        }
        boolean async = ctx.enabled(Rules.ASYNC_CONTEXT_LOSS);
        Map<String, OrderBuilder> byField = new HashMap<>();
        for (MethodInfo m : ctx.methods()) {
            // a rejection policy re-runs a task that was wrapped (or not) when it was submitted; it adds no loss
            if (TypeUtils.isAssignableTo("java.util.concurrent.RejectedExecutionHandler", m.owner().type())) {
                continue;
            }
            for (AsyncHop hop : m.facts().asyncHops) {
                // periodic work has no caller context worth carrying: each run needs a trace of its own
                if (periodic(hop)) {
                    if (scheduled && !ctx.ignored(m.file(), hop.line(), Rules.SCHEDULED_TASK_UNTRACED)) {
                        scheduledTask(m, (J.MethodInvocation) hop.call());
                    }
                    continue;
                }
                if (!async || hop.springManaged() || ctx.ignored(m.file(), hop.line(), Rules.ASYNC_CONTEXT_LOSS)) {
                    continue;
                }
                if (hop.api().startsWith("ResponseBodyEmitter.")) {
                    emitterCallback(m, hop);
                    continue;
                }
                if (hop.api().equals("new Thread") || hop.api().startsWith("Thread.")) {
                    threadStart(m, hop);
                    continue;
                }
                if (hop.call() instanceof J.MethodInvocation mi && isParallelStream(mi)) {
                    ctx.orderNth(Rules.ASYNC_CONTEXT_LOSS, m, hop.line(), "parallel")
                            .title("Parallel stream lambdas run in the ForkJoin common pool without MDC/trace context")
                            .suggestion("Use a sequential stream, or open an MdcScope inside the lambda for the keys its logs need.")
                            .doneWhen("the stream is sequential (no parallel() or parallelStream() in the chain)")
                            .about(m.file().relPath(), hop.call())
                            .context(ctx.context(m, Pos.start(hop.call()), Text.oneLine(hop.call().printTrimmed(), 160), null));
                    continue;
                }
                J.VariableDeclarations.NamedVariable field = executorField(m, hop);
                if (field == null || field.getInitializer() == null) {
                    ctx.orderNth(Rules.ASYNC_CONTEXT_LOSS, m, hop.line(), "hop")
                            .title(hop.api() + " runs on another thread without MDC/trace context")
                            .suggestion(hop.api().startsWith("CompletableFuture")
                                    ? "Pass a context-propagating executor: ContextExecutors.wrap(executor) or a Spring TaskExecutor bean."
                                    : "Wrap the executor where it is created: ContextExecutors.wrap(...), or inject a Spring TaskExecutor.")
                            .doneWhen(hop.api().startsWith("CompletableFuture")
                                    ? "its last argument is ContextExecutors.wrap(executor), or a Spring TaskExecutor or TaskScheduler (a TaskDecorator bean carries the context)"
                                    : "it runs on a Spring TaskExecutor, or on a field initialised with ContextExecutors.wrap(…)")
                            .about(m.file().relPath(), hop.call())
                            .context(ctx.context(m, Pos.start(hop.call()), Text.oneLine(hop.call().printTrimmed(), 160), null));
                    continue;
                }
                String fieldKey = m.owner().fqn() + "#" + field.getSimpleName();
                OrderBuilder existing = byField.get(fieldKey);
                if (existing != null) {
                    existing.location(m.file().relPath(), hop.line(), Rules.ASYNC_CONTEXT_LOSS);
                    continue;
                }
                String init = m.file().slice(Pos.start(field.getInitializer()), Pos.end(field.getInitializer()));
                if (init.contains("ContextExecutors.wrap") || init.contains("ContextExecutorService.wrap")) {
                    continue;
                }
                OrderBuilder b = ctx.order(Rules.ASYNC_CONTEXT_LOSS, m, hop.line(), "field" + field.getSimpleName())
                        .title(hop.api() + " on '" + field.getSimpleName() + "' loses MDC and trace context on the worker thread");
                b.edit(Edit.replace(m.file().relPath(), Pos.start(field.getInitializer()), Pos.end(field.getInitializer()),
                        "ContextExecutors.wrap(" + init + ")"));
                b.edits(JavaEdits.imports(m.file(), ctx.support("ContextExecutors")));
                b.context(ctx.context(m, Pos.start(hop.call()), Text.oneLine(hop.call().printTrimmed(), 160), null));
                ctx.dependOnStarter(b);
                byField.put(fieldKey, b);
            }
        }
    }

    /** {@code new Thread(r)} / {@code Thread.ofVirtual().start(r)}: wrap the Runnable so MDC and trace travel with it. */
    private void threadStart(MethodInfo m, AsyncHop hop) {
        List<Expression> args = hop.call() instanceof J.NewClass n ? n.getArguments() : ((J.MethodInvocation) hop.call()).getArguments();
        // already wrapped (matched by name: right after the fix the starter may not be on the classpath file yet)
        if (args.stream().anyMatch(a -> a instanceof J.MethodInvocation w && w.getSimpleName().startsWith("wrap")
                && w.getSelect() instanceof J.Identifier sel && sel.getSimpleName().equals("ContextPropagation"))) {
            return;
        }
        Expression runnable = args.stream().filter(a -> !(a instanceof J.Empty))
                .filter(a -> a instanceof J.Lambda || a instanceof J.MemberReference
                        || TypeUtils.isAssignableTo("java.lang.Runnable", a.getType()))
                .findFirst().orElse(null);
        OrderBuilder b = ctx.orderNth(Rules.ASYNC_CONTEXT_LOSS, m, hop.line(), "thread")
                .title(hop.api() + " starts a thread without the caller's MDC/trace context");
        b.context(ctx.context(m, Pos.start(hop.call()), Text.oneLine(hop.call().printTrimmed(), 160), null));
        if (runnable == null) {
            b.suggestion("Wrap the task: ContextPropagation.wrapRunnable(task).");
            return;
        }
        String src = m.file().slice(Pos.start(runnable), Pos.end(runnable));
        b.edit(Edit.replace(m.file().relPath(), Pos.start(runnable), Pos.end(runnable), "ContextPropagation.wrapRunnable(" + src + ")"));
        b.edits(JavaEdits.imports(m.file(), ctx.support("ContextPropagation")));
        ctx.dependOnStarter(b);
    }

    /** {@code scheduleAtFixedRate}/{@code scheduleWithFixedDelay}, or {@code TaskScheduler.schedule(task, trigger)}. */
    private static boolean periodic(AsyncHop hop) {
        if (!(hop.call() instanceof J.MethodInvocation mi)) {
            return false;
        }
        String name = mi.getSimpleName();
        if (name.equals("scheduleAtFixedRate") || name.equals("scheduleWithFixedDelay")) {
            return true;
        }
        List<Expression> args = mi.getArguments().stream().filter(a -> !(a instanceof J.Empty)).toList();
        return name.equals("schedule") && args.size() == 2
                && TypeUtils.isAssignableTo("org.springframework.scheduling.Trigger", args.get(1).getType());
    }

    /** {@code executor.scheduleAtFixedRate(task, ..)} → {@code executor.scheduleAtFixedRate(ObservedContext.observed("x.sweep", task), ..)}. */
    private void scheduledTask(MethodInfo m, J.MethodInvocation call) {
        Expression task = call.getArguments().stream().filter(a -> !(a instanceof J.Empty)).findFirst().orElse(null);
        if (task == null || task instanceof J.MethodInvocation w && w.getSimpleName().equals("observed")
                && w.getSelect() instanceof J.Identifier sel && sel.getSimpleName().equals("ObservedContext")) {
            return;
        }
        String taskName = task instanceof J.MemberReference ref ? ref.getReference().getSimpleName()
                : task instanceof J.Identifier id ? id.getSimpleName() : m.name();
        String name = Text.kebab(m.owner().simpleName()) + "." + Text.kebab(taskName);
        int line = Pos.line(call.getName());
        String src = m.file().slice(Pos.start(task), Pos.end(task));
        OrderBuilder b = ctx.orderNth(Rules.SCHEDULED_TASK_UNTRACED, m, line, "periodic")
                .title(call.getSimpleName() + " runs '" + Text.oneLine(src, 60) + "' periodically outside any trace: "
                        + "its logs have no trace id")
                .context(ctx.context(m, Pos.start(call), Text.oneLine(call.printTrimmed(), 160), null));
        b.edit(Edit.replace(m.file().relPath(), Pos.start(task), Pos.end(task), "ObservedContext.observed(\"" + name + "\", " + src + ")"));
        b.edits(JavaEdits.imports(m.file(), ctx.support("ObservedContext")));
        ctx.dependOnStarter(b);
    }

    /** {@code emitter.onCompletion(() -> ..)}: the callback runs on a container thread; give it the registering request's context. */
    private void emitterCallback(MethodInfo m, AsyncHop hop) {
        J.MethodInvocation call = (J.MethodInvocation) hop.call();
        Expression cb = call.getArguments().stream().filter(a -> !(a instanceof J.Empty)).findFirst().orElse(null);
        if (cb == null || cb instanceof J.MethodInvocation w && w.getSimpleName().startsWith("wrap")
                && w.getSelect() instanceof J.Identifier sel && sel.getSimpleName().equals("ContextPropagation")) {
            return;
        }
        String wrapper = call.getSimpleName().equals("onError") ? "wrapConsumer" : "wrapRunnable";
        String src = m.file().slice(Pos.start(cb), Pos.end(cb));
        OrderBuilder b = ctx.orderNth(Rules.ASYNC_CONTEXT_LOSS, m, hop.line(), "emitter")
                .title("SseEmitter." + call.getSimpleName() + " callback runs later on a container thread without MDC/trace context")
                .context(ctx.context(m, Pos.start(call), Text.oneLine(call.printTrimmed(), 160), null));
        b.edit(Edit.replace(m.file().relPath(), Pos.start(cb), Pos.end(cb), "ContextPropagation." + wrapper + "(" + src + ")"));
        b.edits(JavaEdits.imports(m.file(), ctx.support("ContextPropagation")));
        ctx.dependOnStarter(b);
    }

    private static boolean isParallelStream(J.MethodInvocation mi) {
        Expression sel = mi.getSelect();
        while (sel instanceof J.MethodInvocation s) {
            if (s.getSimpleName().equals("parallelStream") || s.getSimpleName().equals("parallel")) {
                return true;
            }
            sel = s.getSelect();
        }
        return false;
    }

    private static J.VariableDeclarations.NamedVariable executorField(MethodInfo m, AsyncHop hop) {
        if (!(hop.call() instanceof J.MethodInvocation mi) || !(mi.getSelect() instanceof J.Identifier id) || id.getFieldType() == null) {
            return null;
        }
        for (Statement s : m.owner().decl().getBody().getStatements()) {
            if (s instanceof J.VariableDeclarations vd) {
                for (J.VariableDeclarations.NamedVariable v : vd.getVariables()) {
                    if (v.getSimpleName().equals(id.getSimpleName())) {
                        return v;
                    }
                }
            }
        }
        return null;
    }

    /**
     * SSE_BROADCAST_CONTEXT (#17): {@code emitter.send(..)} to every subscriber's emitter held in a field (a for-each
     * over it, or a lambda handed to its {@code forEach} / {@code removeIf}). {@code ResponseBodyEmitter.send} writes on the
     * calling thread (Spring Framework 7.0 ResponseBodyEmitter: under its write lock, straight to the response), so the
     * lines logged per subscriber carry the sender's MDC and trace: no executor hop, nothing a TaskDecorator or
     * ContextExecutors could carry. The subscriber's own request observation stays open until the async request ends
     * (ServerHttpObservationFilter), and its context reaches only the emitter callbacks registered when it subscribed
     * (onCompletion / onTimeout / onError, wrapped by ASYNC_CONTEXT_LOSS). A line in the loop that does not name the
     * subscriber (the map key, or another part of the loop variable than the emitter) cannot be matched to it.
     * Report only: which value identifies a subscriber is the service's decision.
     */
    private void broadcasts(MethodInfo m) {
        if (m.decl().getBody() == null || m.facts().logs.isEmpty()) {
            return;
        }
        Set<J> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        new JavaIsoVisitor<Integer>() {
            @Override
            public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration c, Integer p) {
                return c;
            }

            @Override
            public J.MethodInvocation visitMethodInvocation(J.MethodInvocation mi, Integer p) {
                if (mi.getSimpleName().equals("send") && mi.getMethodType() != null && TypeUtils.isAssignableTo(
                        "org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter", mi.getMethodType().getDeclaringType())) {
                    String root = rootName(mi.getSelect());
                    for (Cursor c = getCursor().getParent(); root != null && c != null; c = c.getParent()) {
                        Object v = c.getValue();
                        if (v instanceof J.MethodDeclaration || v instanceof J.ClassDeclaration) {
                            break;
                        }
                        List<String> bound = v instanceof J.ForEachLoop loop && fieldRooted(loop.getControl().getIterable())
                                && loop.getControl().getVariable() instanceof J.VariableDeclarations vd
                                ? vd.getVariables().stream().map(J.VariableDeclarations.NamedVariable::getSimpleName).toList()
                                : v instanceof J.Lambda lambda && overField(c) ? lambdaParams(lambda) : List.of();
                        if (bound.contains(root)) {
                            if (seen.add((J) v)) {
                                broadcast(m, mi, (J) v, v instanceof J.ForEachLoop loop ? loop.getBody() : ((J.Lambda) v).getBody(), bound);
                            }
                            break;
                        }
                    }
                }
                return super.visitMethodInvocation(mi, p);
            }
        }.visit(m.decl().getBody(), 0, new Cursor(new Cursor(null, Cursor.ROOT_VALUE), m.decl()));
    }

    private void broadcast(MethodInfo m, J.MethodInvocation send, J loop, J body, List<String> bound) {
        String emitter = send.getSelect().printTrimmed();
        int from = Pos.start(body);
        int to = Pos.end(body);
        List<LogCall> unnamed = m.facts().logs.stream()
                .filter(l -> Pos.start(l.call()) >= from && Pos.end(l.call()) <= to)
                .filter(l -> !namesSubscriber(l, emitter, bound))
                .toList();
        int line = Pos.line(send.getName());
        if (unnamed.isEmpty() || ctx.ignored(m.file(), line, Rules.SSE_BROADCAST_CONTEXT)) {
            return;
        }
        ctx.orderNth(Rules.SSE_BROADCAST_CONTEXT, m, line, "broadcast")
                .title("SseEmitter.send to every subscriber in " + m.label() + ": the lines logged per subscriber carry the "
                        + "sender's MDC and trace and do not name the subscriber")
                .suggestion("send() writes on the calling thread, so no TaskDecorator or ContextExecutors carries the subscriber's "
                        + "context here. Name the subscriber in each line (iterate the map's entries and log the key, or keep the "
                        + "subscriber's id next to its emitter), or log the per-subscriber outcome in the emitter's "
                        + "onError/onCompletion callback registered when it subscribed, which runs with the subscriber's context.")
                .doneWhen("every log line inside the loop names the subscriber (a loop variable other than the emitter), or the loop logs nothing")
                .basis("logged per subscriber without naming it: line " + String.join(", ",
                        unnamed.stream().map(l -> String.valueOf(Pos.line(l.call()))).toList()))
                .about(m.file().relPath(), loop)
                .context(ctx.context(m, Pos.start(send), Text.oneLine(send.printTrimmed(), 160), null));
    }

    /** A log argument (or the message) uses a loop variable other than through the emitter expression itself. */
    private static boolean namesSubscriber(LogCall l, String emitter, List<String> bound) {
        List<Expression> parts = new ArrayList<>(l.args());
        if (l.template() != null) {
            parts.add(l.template());
        }
        boolean[] named = {false};
        for (Expression e : parts) {
            new JavaIsoVisitor<Integer>() {
                @Override
                public J.FieldAccess visitFieldAccess(J.FieldAccess fa, Integer p) {
                    return fa.printTrimmed().equals(emitter) ? fa : super.visitFieldAccess(fa, p);
                }

                @Override
                public J.MethodInvocation visitMethodInvocation(J.MethodInvocation mi, Integer p) {
                    return mi.printTrimmed().equals(emitter) ? mi : super.visitMethodInvocation(mi, p);
                }

                @Override
                public J.Identifier visitIdentifier(J.Identifier id, Integer p) {
                    if (!id.printTrimmed().equals(emitter) && bound.contains(id.getSimpleName())) {
                        named[0] = true;
                    }
                    return id;
                }
            }.visit(e, 0);
        }
        return named[0];
    }

    /** {@code e} in {@code e.send(..)}, {@code entry.getValue().send(..)}, {@code subscriber.emitter().send(..)}. */
    private static String rootName(Expression e) {
        while (e instanceof J.MethodInvocation mi && mi.getSelect() != null) {
            e = mi.getSelect();
        }
        while (e instanceof J.FieldAccess fa) {
            e = fa.getTarget();
        }
        return e instanceof J.Identifier id && !isField(id) ? id.getSimpleName() : null;
    }

    /** {@code emitters}, {@code this.emitters}, {@code emitters.values()}, {@code emitters.entrySet()}: a field of the class. */
    private static boolean fieldRooted(Expression e) {
        while (e instanceof J.MethodInvocation mi && mi.getSelect() != null) {
            e = mi.getSelect();
        }
        if (e instanceof J.FieldAccess fa && fa.getTarget() instanceof J.Identifier t && t.getSimpleName().equals("this")) {
            return true;
        }
        return e instanceof J.Identifier id && isField(id);
    }

    private static boolean isField(J.Identifier id) {
        return id.getFieldType() != null && id.getFieldType().getOwner() instanceof org.openrewrite.java.tree.JavaType.FullyQualified;
    }

    /** The lambda is an argument of a call on a field: {@code emitters.forEach((id, e) -> ..)}, {@code emitters.values().removeIf(..)}. */
    private static boolean overField(Cursor lambda) {
        Cursor c = lambda.getParentTreeCursor();
        return c.getValue() instanceof J.MethodInvocation call && call.getSelect() != null && fieldRooted(call.getSelect())
                && call.getArguments().stream().anyMatch(a -> a == lambda.getValue());
    }

    private static List<String> lambdaParams(J.Lambda lambda) {
        List<String> out = new ArrayList<>();
        for (J p : lambda.getParameters().getParameters()) {
            if (p instanceof J.VariableDeclarations vd) {
                vd.getVariables().forEach(v -> out.add(v.getSimpleName()));
            } else if (p instanceof J.Identifier id) {
                out.add(id.getSimpleName());
            }
        }
        return out;
    }
}
