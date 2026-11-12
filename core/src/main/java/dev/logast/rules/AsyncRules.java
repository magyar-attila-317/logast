package dev.logast.rules;

import dev.logast.model.AsyncHop;
import dev.logast.model.MethodInfo;
import dev.logast.orders.Edit;
import dev.logast.parse.Pos;
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
                    ctx.order(Rules.ASYNC_CONTEXT_LOSS, m, hop.line(), "parallel" + hop.line())
                            .title("Parallel stream lambdas run in the ForkJoin common pool without MDC/trace context")
                            .suggestion("Use a sequential stream, or open an MdcScope inside the lambda for the keys its logs need.")
                            .context(ctx.context(m, Pos.start(hop.call()), Text.oneLine(hop.call().printTrimmed(), 160), null));
                    continue;
                }
                J.VariableDeclarations.NamedVariable field = executorField(m, hop);
                if (field == null || field.getInitializer() == null) {
                    ctx.order(Rules.ASYNC_CONTEXT_LOSS, m, hop.line(), "hop" + hop.line())
                            .title(hop.api() + " runs on another thread without MDC/trace context")
                            .suggestion(hop.api().startsWith("CompletableFuture")
                                    ? "Pass a context-propagating executor: ContextExecutors.wrap(executor) or a Spring TaskExecutor bean."
                                    : "Wrap the executor where it is created: ContextExecutors.wrap(...), or inject a Spring TaskExecutor.")
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
        OrderBuilder b = ctx.order(Rules.ASYNC_CONTEXT_LOSS, m, hop.line(), "thread" + hop.line())
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
        OrderBuilder b = ctx.order(Rules.SCHEDULED_TASK_UNTRACED, m, line, "periodic" + line)
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
        OrderBuilder b = ctx.order(Rules.ASYNC_CONTEXT_LOSS, m, hop.line(), "emitter" + hop.line())
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
}
