package dev.logast.rules;

import dev.logast.graph.CallGraph;
import dev.logast.model.CatchInfo;
import dev.logast.model.Level;
import dev.logast.model.LogCall;
import dev.logast.model.MethodInfo;
import dev.logast.orders.Edit;
import dev.logast.orders.Slot;
import dev.logast.parse.Pos;
import dev.logast.parse.SourceFileInfo;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.TypeUtils;

import java.util.*;

/**
 * Per log call: LOG_AND_RETHROW, and one composite rewrite of the argument list covering
 * STRING_CONCAT_LOG, GLOSSARY_VIOLATION, LOG_FORMAT, LOST_THROWABLE and PII_IN_LOG.
 */
final class LogCallRules {
    private final RuleContext ctx;

    LogCallRules(RuleContext ctx) {
        this.ctx = ctx;
    }

    void run() {
        for (MethodInfo m : ctx.methods()) {
            List<LogCall> logs = m.facts().logs;
            for (int i = 0; i < logs.size(); i++) {
                LogCall lc = logs.get(i);
                if (lc.fluent() || lc.template() == null) {
                    continue;
                }
                boolean deleted = false;
                CatchInfo c = lc.inCatch();
                if (c != null && c.rethrowsCause && lc.level().compareTo(Level.WARN) >= 0
                        && (ctx.enabled(Rules.LOG_AND_RETHROW) || ctx.enabled(Rules.EXCEPTION_LOGGED_TWICE))
                        && !ctx.ignored(m.file(), Pos.line(lc.call()), Rules.LOG_AND_RETHROW)) {
                    deleted = logAndRethrow(m, lc, i);
                }
                if (!deleted) {
                    rewrite(m, lc, i);
                    level(m, lc, i);
                }
            }
        }
    }

    // ---- LOG_AND_RETHROW -----------------------------------------------------------------------

    private boolean logAndRethrow(MethodInfo m, LogCall lc, int ordinal) {
        int line = Pos.line(lc.call());
        List<String> handlers = loggingHandlers(m, thrownTypes(lc.inCatch()));
        boolean twice = !handlers.isEmpty() && ctx.enabled(Rules.EXCEPTION_LOGGED_TWICE);
        if (!twice && !ctx.enabled(Rules.LOG_AND_RETHROW)) {
            return false;
        }
        OrderBuilder b = ctx.order(twice ? Rules.EXCEPTION_LOGGED_TWICE : Rules.LOG_AND_RETHROW, m, line, "log" + ordinal)
                .title(twice ? "Exception is logged here and again by " + String.join(", ", handlers) + " (log once, where it is handled)"
                        : "Exception is logged here and rethrown: it will be logged again by whoever handles it");
        Set<String> inMdc = ctx.mdcInside(m);
        List<String> lostContext = new ArrayList<>();
        for (Expression arg : lc.args()) {
            ctx.domain.keyOf(arg).filter(k -> !inMdc.contains(k)).ifPresent(lostContext::add);
        }
        b.context(ctx.context(m, Pos.start(lc.call()), Text.oneLine(lc.call().printTrimmed(), 160), null));
        if (!lostContext.isEmpty() || !lc.statement()) {
            b.suggestion(lostContext.isEmpty() ? "Remove the log statement; let the handler log it."
                    : "Not removed: the message carries " + lostContext + " which is not in MDC here. Put it into MDC "
                    + "upstream (MdcScope) or attach it to the rethrown exception, then delete this log.");
            return false;
        }
        b.edit(JavaEdits.deleteStatement(m.file(), lc.call()));
        for (String dep : swallowingCallers(m, lc.inCatch())) {
            b.dependsOn(dep);
        }
        return true;
    }

    private static List<JavaType> thrownTypes(CatchInfo c) {
        List<JavaType> thrown = new ArrayList<>();
        for (J.Throw t : c.throwsList) {
            if (t.getException() instanceof J.Identifier id && id.getSimpleName().equals(c.param)) {
                thrown.addAll(c.types);
            } else if (t.getException().getType() != null) {
                thrown.add(t.getException().getType());
            }
        }
        return thrown;
    }

    private static boolean compatible(List<JavaType> catchTypes, List<JavaType> thrown) {
        return thrown.stream().anyMatch(t -> catchTypes.stream().anyMatch(ct -> TypeUtils.isAssignableTo(ct, t)));
    }

    /**
     * Where the rethrown exception gets logged again: callers whose catch (at the call site) logs it, following
     * rethrows upward, and {@code @ExceptionHandler} methods that log it when the method serves HTTP requests.
     */
    private List<String> loggingHandlers(MethodInfo m, List<JavaType> thrown) {
        Set<String> found = new TreeSet<>();
        Deque<MethodInfo> queue = new ArrayDeque<>(List.of(m));
        Set<String> seen = new HashSet<>(List.of(m.key()));
        Set<String> httpEntrypoints = new HashSet<>();
        ctx.a.entrypoints.stream().filter(ep -> ep.kind() == dev.logast.model.Entrypoint.Kind.HTTP)
                .forEach(ep -> httpEntrypoints.add(ep.method().key()));
        boolean reachesHttp = false;
        while (!queue.isEmpty() && seen.size() < 300) {
            MethodInfo cur = queue.poll();
            if (httpEntrypoints.contains(cur.key())) {
                reachesHttp = true; // propagates out of a controller method: Spring's @ExceptionHandler takes it
            }
            for (CallGraph.Edge e : ctx.a.graph.in(cur)) {
                MethodInfo caller = e.from();
                CatchInfo handler = e.site().enclosingCatches().stream().filter(g -> compatible(g.types, thrown)).findFirst().orElse(null);
                if (handler != null && !handler.logs.isEmpty()) {
                    found.add(caller.label());
                } else if ((handler == null || handler.rethrowsCause) && seen.add(caller.key())) {
                    queue.add(caller);
                }
            }
        }
        if (reachesHttp) {
            MethodInfo handler = mostSpecificHandler(thrown);
            if (handler != null && !handler.facts().logs.isEmpty()) {
                found.add(handler.label());
            }
        }
        return List.copyOf(found);
    }

    /** The @ExceptionHandler Spring would pick: the one whose handled type is closest to the thrown type. */
    private MethodInfo mostSpecificHandler(List<JavaType> thrown) {
        MethodInfo best = null;
        JavaType bestType = null;
        for (MethodInfo h : ctx.a.index.methods()) {
            for (JavaType handled : handledTypes(h)) {
                if (thrown.stream().anyMatch(t -> TypeUtils.isAssignableTo(handled, t))
                        && (bestType == null || TypeUtils.isAssignableTo(bestType, handled))) {
                    best = h;
                    bestType = handled;
                }
            }
        }
        return best;
    }

    /** Exception types an {@code @ExceptionHandler} method handles (annotation value, else Throwable parameters). */
    static List<JavaType> handledTypes(MethodInfo h) {
        List<JavaType> out = new ArrayList<>();
        for (J.Annotation a : h.decl().getLeadingAnnotations()) {
            if (!dev.logast.model.Annotations.is(a, "org.springframework.web.bind.annotation.ExceptionHandler")) {
                continue;
            }
            new org.openrewrite.java.JavaIsoVisitor<List<JavaType>>() {
                @Override
                public J.FieldAccess visitFieldAccess(J.FieldAccess fa, List<JavaType> acc) {
                    if (fa.getSimpleName().equals("class") && fa.getTarget().getType() != null) {
                        acc.add(fa.getTarget().getType());
                    }
                    return fa;
                }
            }.visit(a, out);
            if (out.isEmpty()) {
                for (var p : h.type().getParameterTypes()) {
                    if (TypeUtils.isAssignableTo("java.lang.Throwable", p)) {
                        out.add(p);
                    }
                }
            }
        }
        return out;
    }

    // ---- LOG_LEVEL -------------------------------------------------------------------------------

    /** Client errors are never ERROR; INFO per loop iteration becomes DEBUG. Edits only the method name. */
    private void level(MethodInfo m, LogCall lc, int ordinal) {
        if (lc.fluent() || !ctx.enabled(Rules.LOG_LEVEL)) {
            return;
        }
        String target = null;
        String why = null;
        if (lc.level() == Level.ERROR) {
            List<JavaType> types = lc.inCatch() != null ? lc.inCatch().types : handledTypes(m);
            List<String> client = ctx.policy.levels().clientErrors();
            String hit = types.stream().map(t -> client.stream().filter(cl -> TypeUtils.isAssignableTo(cl, t)).findFirst().orElse(null))
                    .filter(Objects::nonNull).findFirst().orElse(null);
            if (hit != null) {
                target = ctx.policy.levels().clientErrorLevel();
                why = hit.substring(hit.lastIndexOf('.') + 1) + " is a client error (expected outcome); ERROR means someone must act";
            }
        }
        if (target == null && lc.inLargeLoop(ctx.policy.levels().boundedLoopMax()) && lc.level() == Level.INFO
                && ctx.policy.levels().noInfoInLoops()) {
            target = "debug";
            why = "INFO once per loop iteration floods the logs; log a summary after the loop at INFO instead";
        }
        int line = Pos.line(lc.call());
        if (target == null || target.equals(lc.level().method()) || ctx.ignored(m.file(), line, Rules.LOG_LEVEL)) {
            return;
        }
        J.Identifier name = lc.call().getName();
        ctx.order(Rules.LOG_LEVEL, m, line, "level" + ordinal)
                .title(lc.level() + " → " + target.toUpperCase(Locale.ROOT) + ": " + why)
                .edit(Edit.replace(m.file().relPath(), Pos.start(name), Pos.end(name), target))
                .context(ctx.context(m, Pos.start(lc.call()), Text.oneLine(lc.call().printTrimmed(), 160), null));
    }

    /** SWALLOWED orders of callers that catch (and drop) what this catch rethrows. */
    private List<String> swallowingCallers(MethodInfo m, CatchInfo c) {
        List<JavaType> thrown = thrownTypes(c);
        List<String> out = new ArrayList<>();
        for (CallGraph.Edge e : ctx.a.graph.in(m)) {
            for (CatchInfo g : e.site().enclosingCatches()) {
                String id = ctx.swallowedOrders.get(g);
                if (id != null && thrown.stream().anyMatch(t -> g.types.stream().anyMatch(gt -> TypeUtils.isAssignableTo(gt, t)))) {
                    out.add(id);
                }
            }
        }
        return out;
    }

    // ---- composite rewrite ---------------------------------------------------------------------

    private record Arg(Expression expr, String src, String key, boolean registry) {
    }

    private void rewrite(MethodInfo m, LogCall lc, int ordinal) {
        SourceFileInfo f = m.file();
        int line = Pos.line(lc.call());
        MessageTemplate.Parsed parsed = MessageTemplate.parse(lc.template());
        if (parsed == null) {
            return;
        }
        List<String> issues = new ArrayList<>();
        if (parsed.converted()) {
            issues.add(Rules.STRING_CONCAT_LOG);
        }

        // arguments: inline (from concat/format) first, then explicit ones; split off the throwable
        List<Expression> rawArgs = new ArrayList<>(parsed.inline());
        rawArgs.addAll(lc.args());
        Expression throwable = null;
        if (!rawArgs.isEmpty() && TypeUtils.isAssignableTo("java.lang.Throwable", rawArgs.getLast().getType())) {
            throwable = rawArgs.removeLast();
        }
        String throwableSrc = throwable == null ? null : src(f, throwable);
        CatchInfo c = lc.inCatch();
        if (throwable == null && c != null && lc.level().compareTo(Level.WARN) >= 0
                && rawArgs.stream().noneMatch(a -> a instanceof J.Identifier id && id.getSimpleName().equals(c.param))) {
            throwableSrc = c.param;
            issues.add(Rules.LOST_THROWABLE);
        }

        String raw = parsed.raw();
        if (!ctx.policy.logging().rewriteExistingMessages()) {
            keepStyle(m, lc, ordinal, raw, rawArgs, throwableSrc, issues);
            return;
        }
        // "device={}" names its own placeholder: that name becomes the suffix key
        List<String> named = MessageTemplate.placeholderNames(raw);
        List<Arg> args = new ArrayList<>();
        Map<String, String> mdcAliases = ctx.policy.mdcAliases();
        for (int i = 0; i < rawArgs.size(); i++) {
            Expression e = rawArgs.get(i);
            Optional<String> reg = ctx.domain.keyOf(e);
            String given = i < named.size() ? named.get(i) : null;
            String key;
            if (reg.isPresent()) {
                key = reg.get();
            } else if (given != null && mdcAliases.containsKey(given)) {
                key = mdcAliases.get(given);
            } else {
                String raw0 = given != null ? given : DomainValues.derivedKey(e);
                key = styled(raw0 == null ? Text.camel(src(f, e)) : raw0);
            }
            args.add(new Arg(e, src(f, e), key, reg.isPresent() || mdcAliases.containsValue(key)));
        }

        boolean mismatch = MessageTemplate.count(raw) != args.size();
        List<String> existingKeys = MessageTemplate.compliantKeys(raw);
        boolean compliant = existingKeys != null && existingKeys.size() == args.size() && !mismatch;
        if (compliant && !existingKeys.equals(args.stream().map(a -> logKey(a.key())).toList())) {
            issues.add(Rules.LOG_KEY_STYLE);
        }
        String unnamed = MessageTemplate.removeNamedPlaceholders(raw);
        boolean mechanical = !mismatch && (compliant || MessageTemplate.placeholdersTrailing(unnamed));
        if (!compliant) {
            raw = unnamed;
        }
        if (!compliant) {
            issues.add(Rules.LOG_FORMAT);
        }

        // drop values already in MDC, PII and e.getMessage() when the exception itself is logged
        Set<String> inMdc = ctx.mdcInside(m);
        List<String> plannedDeps = new ArrayList<>();
        List<Arg> kept = new ArrayList<>();
        List<String> droppedStems = new ArrayList<>();
        for (Arg a : args) {
            if (a.registry() && inMdc.contains(a.key())) {
                String wrap = ctx.plannedBy(m, a.key());
                if (wrap != null) {
                    plannedDeps.add(wrap);
                }
                droppedStems.add(MessageTemplate.stem(a.key()));
                issues.add(Rules.LOG_FORMAT);
            } else if (ctx.domain.isPii(a.expr())) {
                droppedStems.add(MessageTemplate.stem(a.key()));
                issues.add(Rules.PII_IN_LOG);
            } else if (throwableSrc != null && a.src().equals(throwableSrc + ".getMessage()")) {
                issues.add(Rules.LOG_FORMAT);
            } else {
                kept.add(a);
            }
        }

        String base = compliant ? MessageTemplate.compliantBase(raw) : MessageTemplate.stripPlaceholders(raw);
        if (!compliant && mechanical) {
            List<String> stems = new ArrayList<>(droppedStems);
            kept.forEach(a -> stems.add(MessageTemplate.stem(a.key())));
            base = MessageTemplate.stripTrailingPhrase(base, stems);
        }
        String normalized = ctx.glossary.normalize(base, ctx.area(m));
        if (!normalized.equals(base)) {
            issues.add(Rules.GLOSSARY_VIOLATION);
        }
        if (mechanical && ctx.enabled(Rules.MESSAGE_PHRASING)) {
            Optional<String> phrased = ctx.phrasing.rewrite(normalized);
            if (phrased.isPresent()) {
                normalized = phrased.get();
                issues.add(Rules.MESSAGE_PHRASING);
            }
        }
        LinkedHashSet<String> unique = new LinkedHashSet<>(issues);
        unique.removeIf(r -> !ctx.enabled(r));
        if (unique.isEmpty() || ctx.ignored(f, line, unique.iterator().next())) {
            return;
        }

        String rule = unique.stream().max(Comparator.comparingInt(r -> Rules.rank(Rules.severity(ctx.policy, r)))).orElseThrow();
        OrderBuilder b = ctx.order(rule, m, line, "log" + ordinal);
        plannedDeps.forEach(b::dependsOn);
        unique.forEach(b::fix);
        b.title(title(unique, lc));
        String keysText = String.join("", kept.stream().map(a -> ", " + a.src()).toList());
        String tail = throwableSrc == null ? "" : ", " + throwableSrc;
        int start = Pos.start(lc.template());
        int end = Pos.end(lc.args().isEmpty() ? lc.template() : lc.args().getLast());
        List<String> keys = kept.stream().map(a -> logKey(a.key())).toList();
        b.context(ctx.context(m, start, Text.oneLine(lc.call().printTrimmed(), 200), "MSG"));
        if (mechanical && droppedPiiMidSentence(unique, raw)) {
            mechanical = false;
        }
        // never write a message whose placeholders don't match its arguments, or with leftovers like "funders=()":
        // hand it to the text slot instead
        if (mechanical && (MessageTemplate.count(normalized) > 0 || KEY_LEFTOVER.matcher(normalized).find())) {
            mechanical = false;
        }
        if (mechanical) {
            String message = normalized + MessageTemplate.suffix(keys);
            String replacement = "\"" + message + "\"" + keysText + tail;
            if (replacement.equals(f.slice(start, end))) {
                ctx.drop(b);
                return;
            }
            b.edit(Edit.replace(f.relPath(), start, end, replacement));
        } else {
            b.edit(Edit.replace(f.relPath(), start, end, "\"{{MSG}}\"" + keysText + "{{ARGS}}" + tail));
            String event = ctx.phrasing.eventOf(MessageTemplate.stripPlaceholders(raw));
            b.slot("MSG", new Slot("Reword this " + lc.level() + " message in plain text without placeholders; values move "
                    + "into the [k=v] suffix the tool appends" + (mismatch ? " (placeholder/argument count was wrong)" : ""),
                    keys, ctx.glossary.terms(), raw, event, event == null ? null : ctx.phrasing.canonicalOf(event)));
        }
    }

    /**
     * {@code rewriteExistingMessages: false}: keep the message's own wording and placeholders; fix only
     * concatenation, glossary terms and a missing exception. PII is reported, not rewritten.
     */
    private void keepStyle(MethodInfo m, LogCall lc, int ordinal, String raw, List<Expression> rawArgs,
                           String throwableSrc, List<String> issues) {
        SourceFileInfo f = m.file();
        int line = Pos.line(lc.call());
        List<Expression> pii = rawArgs.stream().filter(ctx.domain::isPii).toList();
        String normalized = ctx.glossary.normalize(raw, ctx.area(m));
        if (!normalized.equals(raw)) {
            issues.add(Rules.GLOSSARY_VIOLATION);
        }
        if (ctx.enabled(Rules.MESSAGE_PHRASING)) {
            Optional<String> phrased = ctx.phrasing.rewrite(normalized);
            if (phrased.isPresent()) {
                normalized = phrased.get();
                issues.add(Rules.MESSAGE_PHRASING);
            }
        }
        LinkedHashSet<String> unique = new LinkedHashSet<>(issues);
        if (!pii.isEmpty()) {
            unique.add(Rules.PII_IN_LOG);
        }
        unique.removeIf(r -> !ctx.enabled(r));
        if (unique.isEmpty() || ctx.ignored(f, line, unique.iterator().next())) {
            return;
        }
        String rule = unique.stream().max(Comparator.comparingInt(r -> Rules.rank(Rules.severity(ctx.policy, r)))).orElseThrow();
        OrderBuilder b = ctx.order(rule, m, line, "log" + ordinal);
        unique.forEach(b::fix);
        b.title(title(unique, lc));
        int start = Pos.start(lc.template());
        int end = Pos.end(lc.args().isEmpty() ? lc.template() : lc.args().getLast());
        b.context(ctx.context(m, start, Text.oneLine(lc.call().printTrimmed(), 200), null));
        if (!pii.isEmpty()) {
            b.suggestion("Remove " + pii.stream().map(e -> src(f, e)).toList() + " from this log line (PII).");
            return;
        }
        String args = String.join("", rawArgs.stream().map(e -> ", " + src(f, e)).toList());
        if (MessageTemplate.count(normalized) != rawArgs.size()) {
            b.suggestion("Not rewritten: the message has " + MessageTemplate.count(normalized) + " placeholders for "
                    + rawArgs.size() + " arguments; fix it by hand.");
            return;
        }
        String replacement = "\"" + normalized + "\"" + args + (throwableSrc == null ? "" : ", " + throwableSrc);
        if (replacement.equals(f.slice(start, end))) {
            ctx.drop(b);
            return;
        }
        b.edit(Edit.replace(f.relPath(), start, end, replacement));
    }

    private static final java.util.regex.Pattern KEY_LEFTOVER = java.util.regex.Pattern.compile("\\b[A-Za-z][\\w.\\-]*=|[\\[(]\\s*[,;]");

    /** Suffix name for an MDC/registry key (vocabulary logKey), or the key itself. */
    private String logKey(String key) {
        return ctx.policy.logKey(key);
    }

    /** policy.logging.keyStyle for keys outside the vocabulary: dotted, camel, pascal, snake, keep. */
    private String styled(String name) {
        return ctx.policy.logging().style().apply(name);
    }

    private static boolean droppedPiiMidSentence(Set<String> issues, String raw) {
        return issues.contains(Rules.PII_IN_LOG) && !MessageTemplate.placeholdersTrailing(raw);
    }

    private static String title(Set<String> issues, LogCall lc) {
        List<String> parts = new ArrayList<>();
        for (String r : issues) {
            parts.add(switch (r) {
                case Rules.STRING_CONCAT_LOG -> "message built by concatenation";
                case Rules.GLOSSARY_VIOLATION -> "non-glossary wording";
                case Rules.LOST_THROWABLE -> "exception not passed to the logger";
                case Rules.PII_IN_LOG -> "logs PII";
                case Rules.LOG_FORMAT -> "not in 'Text [k=v]' format or repeats MDC values";
                case Rules.MESSAGE_PHRASING -> "lifecycle phrasing differs from the house form";
                case Rules.LOG_KEY_STYLE -> "[k=v] keys differ from the vocabulary/key style";
                default -> r;
            });
        }
        return lc.level() + " log: " + String.join(", ", parts);
    }

    private static String src(SourceFileInfo f, Expression e) {
        int s = Pos.start(e);
        int en = Pos.end(e);
        return s < 0 ? e.printTrimmed() : f.slice(s, en);
    }
}
