package dev.logast.rules;

import dev.logast.LogLedger;
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
            if (ctx.logHelper(m)) {
                m.facts().logs.forEach(lc -> ctx.skip(lc, LogLedger.HELPER));
                continue;   // LOG_HELPER: inline it at the call sites instead of polishing it
            }
            List<LogCall> logs = m.facts().logs;
            for (int i = 0; i < logs.size(); i++) {
                LogCall lc = logs.get(i);
                if (lc.fluent() || lc.template() == null) {
                    ctx.skip(lc, lc.fluent() ? LogLedger.FLUENT : LogLedger.NO_MESSAGE);
                    continue;
                }
                String written = MessageTemplate.text(lc.template());
                if (written != null && !MessageTemplate.stripPlaceholders(MessageTemplate.compliantKeys(written) != null
                        ? MessageTemplate.compliantBase(written) : written).matches(".*\\p{L}.*")) {
                    ctx.skip(lc, LogLedger.MESSAGE_ARGUMENT);
                    continue;   // "{} [count={}]": the message itself comes from an argument; nothing to restructure
                }
                if (ctx.policy.logging().protectedMessage(MessageTemplate.text(lc.template()))) {
                    ctx.skip(lc, LogLedger.PROTECTED);
                    continue;   // alert-linked (logging.protectedMessages): never reworded or reformatted
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

    /**
     * The MDC keys at this log line. A key the method itself scopes (a try-with-resources MdcScope / putCloseable) is in
     * MDC only inside that try's block: a line before it or in a catch of the method's own try (D1b) is outside. A key
     * from callers or a whole-body scope is everywhere in the method.
     */
    private Set<String> mdcAt(MethodInfo m, LogCall lc) {
        Set<String> all = ctx.mdcInside(m);
        J.Block body = m.decl().getBody();
        if (body == null || all.isEmpty()) {
            return all;
        }
        int at = Pos.start(lc.call());
        Set<String> out = new java.util.TreeSet<>(all);
        out.removeAll(ctx.a.paths.notCoveredAt(m, at));
        // planned in this scan, in a body that is one try with catches (D1b): the scope goes inside the try, not the catch
        J.Block guarded = RepeatedValueRules.guardedBlock(body);
        Set<String> local = ctx.mdc.localKeys().get(m.key());
        if (guarded != null && local != null && (at < Pos.start(guarded) || at >= Pos.end(guarded))) {
            out.removeAll(local);
        }
        return out;
    }

    // ---- LOG_AND_RETHROW -----------------------------------------------------------------------

    private boolean logAndRethrow(MethodInfo m, LogCall lc, int ordinal) {
        int line = Pos.line(lc.call());
        // #304: a line that does not log the caught exception is a status line (the FAILED half of an OK/FAILED pair),
        // not "logged and rethrown": the handler that logs the exception does not log what this line says
        String param = lc.inCatch().param;
        java.util.regex.Pattern mentions = java.util.regex.Pattern.compile("\\b" + java.util.regex.Pattern.quote(param) + "\\b");
        if (param == null || param.equals("_") || lc.args().stream().noneMatch(a -> mentions.matcher(a.printTrimmed()).find())) {
            return false;
        }
        List<String> handlers = loggingHandlers(m, thrownTypes(lc.inCatch()));
        boolean twice = !handlers.isEmpty() && ctx.enabled(Rules.EXCEPTION_LOGGED_TWICE);
        if (!twice && !ctx.enabled(Rules.LOG_AND_RETHROW)) {
            return false;
        }
        OrderBuilder b = ctx.order(twice ? Rules.EXCEPTION_LOGGED_TWICE : Rules.LOG_AND_RETHROW, m, line, "log" + ordinal)
                .title(twice ? "Exception is logged here and again by " + String.join(", ", handlers) + " (log once, where it is handled)"
                        : "Exception is logged here and rethrown: it will be logged again by whoever handles it");
        b.about(m.file().relPath(), lc.inCatch().clause)
                .doneWhen("this catch no longer logs the exception it rethrows");
        Set<String> inMdc = ctx.mdcInside(m);
        List<String> lostContext = new ArrayList<>();
        for (Expression arg : lc.args()) {
            var key = ctx.domain.keyOf(arg);
            if (key.isPresent()) {
                // D3: in MDC here is not in MDC where the rethrown exception is logged: an @ExceptionHandler or a caller's
                // catch runs after this method's scopes closed. The id goes with the line, so the line stays
                lostContext.add(key.get());
            } else if (!mentions.matcher(arg.printTrimmed()).find() && !(arg instanceof J.Literal)) {
                // #304: a value the exception does not carry (a duration, a count, a name) would be lost with the line
                lostContext.add(Text.oneLine(arg.printTrimmed(), 40));
            }
        }
        b.context(ctx.context(m, Pos.start(lc.call()), Text.oneLine(lc.call().printTrimmed(), 160), null));
        if (!lc.statement()) {   // not a statement of its own: logast cannot delete it
            b.suggestion(lostContext.isEmpty() ? "Remove the log statement; let the handler log it."
                    : "Not removed: the line carries " + lostContext + ", which the rethrown exception does not. Keep it, or "
                    + "put the values into the exception (its message or a field) and delete this log.");
            return false;
        }
        // deleting it is only safe when something else logs the exception on every way it can travel (#46); with values
        // the exception does not carry too, the note and done-when line name both conditions, not only the first (#85)
        String silent = silentEnd(m, thrownTypes(lc.inCatch()));
        if (silent != null) {
            b.title("Exception is logged here and rethrown; deleting this line is only safe once every caller path logs it")
                    .suggestion("Not removed: " + (lostContext.isEmpty() ? "" : "the line carries " + lostContext
                            + ", which the rethrown exception does not, and ") + "nothing else is known to log it on the path through "
                            + silent + ", so this line may be its only record. " + (lostContext.isEmpty() ? "Log it" : "Put the values into "
                            + "the exception (its message or a field) and log the exception") + " there (or in an @ExceptionHandler), "
                            + "then delete this one.")
                    .doneWhen((lostContext.isEmpty() ? "" : "the line logs nothing but the exception and ") + "a catch or "
                            + "@ExceptionHandler logs it on the path through " + silent + "; then logast deletes this line itself. "
                            + "Or the line is gone");
            return false;
        }
        if (!lostContext.isEmpty()) {
            b.suggestion("Not removed: the line carries " + lostContext + ", which the rethrown exception does not; whoever "
                            + "logs the exception (an @ExceptionHandler, a caller's catch) runs after this method's MDC scopes "
                            + "closed. Keep the line, or put the values into the exception (its message or a field) and delete it.")
                    .doneWhen("the line logs nothing but the exception (then logast deletes it itself), or the line is gone");
            return false;
        }
        b.edit(JavaEdits.deleteStatement(m.file(), lc.call()));
        for (Expression arg : lc.args()) {   // the context it carried must be in MDC first
            ctx.domain.keyOf(arg).map(k -> ctx.plannedBy(m, k)).ifPresent(b::dependsOn);
        }
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

    /**
     * Where the rethrown exception can end up without being logged, or null when every way up ends at a log: a catch
     * that logs it, an @ExceptionHandler that logs, or an entrypoint whose framework logs uncaught exceptions (the
     * servlet container, @Scheduled, the listener containers, Spring Batch, startup). Not proven, and so reported: a
     * catch that swallows it, an @ExceptionHandler that maps it without logging, a call handed to another thread (the
     * failure goes to a Future), a method nobody in the code calls, or a search too large to finish.
     */
    private String silentEnd(MethodInfo m, List<JavaType> thrown) {
        java.util.Map<String, dev.logast.model.Entrypoint> entrypoints = new HashMap<>();
        ctx.a.entrypoints.forEach(ep -> entrypoints.putIfAbsent(ep.method().key(), ep));
        Deque<MethodInfo> queue = new ArrayDeque<>(List.of(m));
        Set<String> seen = new HashSet<>(List.of(m.key()));
        while (!queue.isEmpty()) {
            if (seen.size() > 300) {
                return m.label() + "'s callers (too many to check)";
            }
            MethodInfo cur = queue.poll();
            dev.logast.model.Entrypoint ep = entrypoints.get(cur.key());
            if (ep != null) {
                switch (ep.kind()) {
                    case HTTP -> {
                        MethodInfo handler = mostSpecificHandler(thrown);
                        if (handler != null && handler.facts().logs.isEmpty()) {
                            return "@ExceptionHandler " + handler.label() + " (maps it without logging)";
                        }
                    }
                    // Spring Batch (AbstractStep) and Spring Cloud AWS (the listener sink) log what a step or message throws
                    // RedisMessageListenerContainer logs a listener exception at WARN when no ErrorHandler is set
                    case SCHEDULED, KAFKA, JMS, RABBIT, BATCH, MAIN, STEP, ITEM, SQS, REDIS -> {
                    }
                    default -> {
                        return ep.label() + " (" + ep.kind().name().toLowerCase(java.util.Locale.ROOT) + ": not known to log it)";
                    }
                }
                continue;
            }
            List<CallGraph.Edge> callers = ctx.a.graph.in(cur);
            if (callers.isEmpty()) {
                return cur.label() + " (no caller in the code)";
            }
            for (CallGraph.Edge e : callers) {
                MethodInfo caller = e.from();
                if (e.site().async()) {
                    return caller.label() + " (runs it on another thread: the failure goes to a Future)";
                }
                CatchInfo handler = e.site().enclosingCatches().stream().filter(g -> compatible(g.types, thrown)).findFirst().orElse(null);
                if (handler != null && !handler.logs.isEmpty()) {
                    continue;
                }
                if (handler != null && !handler.rethrowsCause) {
                    return caller.label() + " (catches it without logging)";
                }
                if (seen.add(caller.key())) {
                    queue.add(caller);
                }
            }
        }
        return null;
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

    /** Exception names that say the case is an expected business outcome, not a fault. */
    private static final java.util.regex.Pattern OUTCOME_EXCEPTION = java.util.regex.Pattern.compile(
            "(Conflict|Rejected|Rejection|NotFound|Invalid|Validation|Duplicate|AlreadyExists|Forbidden|Unauthorized|BadRequest|"
                    + "StateConflict|NotAllowed|Denied)\\w*(Exception|Error)?$");

    /**
     * #326 L4: the caught exception is an expected signal, not a fault: a client error (levels.clientErrors), one named
     * like a business outcome (…Rejected…, …Conflict…, …NotFound…, …Validation…), or the IllegalArgumentException an
     * enum's valueOf throws for an unknown code. Its line says what happened; the stack trace adds nothing.
     */
    private boolean expectedSignal(CatchInfo c) {
        List<String> client = ctx.policy.levels().clientErrors();
        for (JavaType t : c.types) {
            JavaType.FullyQualified fq = TypeUtils.asFullyQualified(t);
            String simple = fq == null ? String.valueOf(t) : fq.getClassName();
            List<String> words = ctx.policy.levels().expectedExceptions();   // #327: levels.expectedExceptions
            boolean named = words.isEmpty() ? OUTCOME_EXCEPTION.matcher(simple).find() : words.stream().anyMatch(simple::contains);
            if (client.stream().anyMatch(cl -> TypeUtils.isAssignableTo(cl, t)) || named) {
                return true;
            }
            if (fq != null && fq.getFullyQualifiedName().equals("java.lang.IllegalArgumentException")
                    && c.tryStatement.getBody().printTrimmed().contains(".valueOf(")) {
                return true;
            }
        }
        return false;
    }

    /** A throw in the catch that answers the caller with a 4xx (a status, a ResponseStatusException, a …BadRequest… type). */
    private static final java.util.regex.Pattern THROWS_CLIENT_ERROR = java.util.regex.Pattern.compile(
            "throw\\b[^;]*(HttpStatus\\.(BAD_REQUEST|UNAUTHORIZED|FORBIDDEN|NOT_FOUND|CONFLICT|GONE|PRECONDITION_FAILED|"
                    + "UNPROCESSABLE_ENTITY|UNPROCESSABLE_CONTENT|TOO_MANY_REQUESTS|METHOD_NOT_ALLOWED)|BadRequest|NotFound|Conflict|"
                    + "Forbidden|Unauthorized|Invalid\\w*Exception)");

    private static boolean answersClientError(CatchInfo c) {
        return THROWS_CLIENT_ERROR.matcher(c.clause.getBody().printTrimmed()).find();
    }

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
        int max = ctx.policy.levels().boundedLoopMax();
        String mode = ctx.policy.levels().loopMode();
        if (target == null && lc.level() == Level.INFO && lc.inCatch() == null
                && (mode.equals("never") && lc.inLargeLoop(max) || mode.equals("size") && lc.inDataLoop(max))) {
            target = "debug";
            why = lc.loopBound() == dev.logast.model.LogCall.LARGE
                    ? "INFO once per item of query/stream data (database rows, file lines, a Kafka batch): as many lines as there "
                    + "is data. One summary line after the loop at INFO instead"
                    : "INFO once per loop iteration floods the logs; log a summary after the loop at INFO instead";
        }
        int line = Pos.line(lc.call());
        if (target == null || target.equals(lc.level().method()) || ctx.ignored(m.file(), line, Rules.LOG_LEVEL)) {
            return;
        }
        if (target.equals("debug") && (reportsASignal(lc) || reportsAnOutcome(lc))) {
            // #286: a state change (a device going OFFLINE) or a mismatch (a price discrepancy) is what support looks
            // for, even once per item: a person decides, the line keeps its level
            ctx.order(Rules.LOG_LEVEL, m, line, "level" + ordinal)
                    .title("INFO in a loop, kept: it reports a state change, a mismatch or an item's outcome, which support needs to see")
                    .suggestion("Keep it at INFO if each occurrence matters; else log a summary after the loop at INFO and "
                            + "this line at DEBUG.")
                    .context(ctx.context(m, Pos.start(lc.call()), Text.oneLine(lc.call().printTrimmed(), 160), null));
            return;
        }
        J.Identifier name = lc.call().getName();
        ctx.order(Rules.LOG_LEVEL, m, line, "level" + ordinal)
                .title(lc.level() + " → " + target.toUpperCase(Locale.ROOT) + ": " + why)
                .edit(Edit.replace(m.file().relPath(), Pos.start(name), Pos.end(name), target))
                .context(ctx.context(m, Pos.start(lc.call()), Text.oneLine(lc.call().printTrimmed(), 160), null));
    }

    /**
     * #309: a line that states what became of each item ("Completed order {} for table {}", "Archived {} audit logs for
     * tenant {}"): a business outcome per item is what support reads, even in a loop over query data. Kept and reported,
     * like a signal (#286), not lowered.
     */
    private boolean reportsAnOutcome(LogCall lc) {
        String raw = MessageTemplate.text(lc.template());
        if (raw == null) {
            return false;
        }
        String event = ctx.phrasing.eventOf(MessageTemplate.stripPlaceholders(raw));
        return event != null && Set.of("completed", "failed", "created", "deleted", "archived", "paid", "cancelled", "rejected")
                .contains(event)
                || raw.matches("(?i)^(completed|archived|deleted|created|paid|cancelled|rejected)\\b.*");
    }

    /** A message that reports a transition or a mismatch: "status changed", "a -> b", "discrepancy", "went offline". */
    private static final java.util.regex.Pattern SIGNAL = java.util.regex.Pattern.compile(
            "(?i)(->|→|\\bchanged\\b|\\btransition|\\bmismatch|\\bdiscrepanc|\\bdiffers?\\b|\\bunexpected\\b|\\boffline\\b"
                    + "|\\bonline\\b|\\bunreachable\\b|\\bdisconnected\\b|\\breconnected\\b)");

    /** #286: the message text (its template literal) reports a state change or a mismatch. */
    static boolean reportsASignal(LogCall lc) {
        return lc.template() instanceof J.Literal lit && lit.getValue() instanceof String text && SIGNAL.matcher(text).find();
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
            ctx.skip(lc, LogLedger.NOT_LITERAL);   // a constant or a method's result: no wording or format to check
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
        // #278: a team that never attaches the exception: the caught one becomes [errorType=…] (its class)
        Expression detached = null;
        // #329: a line that already names the exception's class, in a catch that answers the caller with a 4xx: an expected
        // rejection, the stack adds nothing
        boolean namesClass = c != null && rawArgs.stream().anyMatch(a -> src(f, a).startsWith(c.param + ".getClass()"));
        if (throwable != null && c != null && throwableSrc.equals(c.param)
                && (!ctx.policy.logging().attachExceptions() || namesClass && answersClientError(c))) {
            detached = throwable;
            throwableSrc = null;
            issues.add(Rules.LOG_FORMAT);
        }
        // #253: a line that logs the exception's class and not its message withholds the message on purpose (a driver's
        // raw text, personal data): attaching the exception would log it after all
        boolean classOnly = c != null && rawArgs.stream().anyMatch(a -> src(f, a).startsWith(c.param + ".getClass()"))
                && rawArgs.stream().noneMatch(a -> src(f, a).matches(java.util.regex.Pattern.quote(c.param) + "\\.get(Localized)?Message\\(\\).*"));
        if (throwable == null && c != null && !c.param.equals("_") && lc.level().compareTo(Level.WARN) >= 0 && !classOnly
                && ctx.policy.logging().attachExceptions()   // #278: a team that never attaches the exception
                && !RuleContext.deliberate(f, c, lc.call().printTrimmed())   // #290: withheld on purpose
                && !expectedSignal(c)   // #326 L4: a business outcome: a stack per such event is noise
                && !ExceptionRules.recordedInLoop(m, c)   // #329: an item recorded and skipped: a stack per item is noise
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
                // #13: an argument without a letter or digit ("()", "-", '(') has no name to derive; an empty key wrote
                // "[={}]", which logs "[=()]" and is not house format, so every apply prepended another "[=]"
                if (key.isEmpty()) {
                    key = styled("value");
                }
            }
            // the same recognition as REPEATED_LOG_VALUE: a vocabulary source, an alias, or the [key={}] name it is logged
            // under; otherwise a value put into MDC there would stay in the message here
            boolean registry = reg.isPresent() || mdcAliases.containsValue(key)
                    || given != null && ctx.policy.mdcRegistry().containsKey(given);
            args.add(new Arg(e, src(f, e), key, registry));
        }

        // G1/G2: two values the vocabulary maps to one key (tokenDeviceId and bodyDeviceId → deviceId) are different
        // values: each keeps its own name, and neither is the MDC value
        Map<String, Long> perKey = new HashMap<>();
        args.forEach(a -> perKey.merge(a.key(), 1L, Long::sum));
        for (int i = 0; i < args.size(); i++) {
            Arg a = args.get(i);
            if (perKey.get(a.key()) > 1) {
                String given = i < named.size() ? named.get(i) : null;
                String own = given != null ? given : DomainValues.derivedKey(a.expr());
                if (own != null && !own.isBlank()) {
                    args.set(i, new Arg(a.expr(), a.src(), styled(own), false));
                }
            }
        }
        // #323 H1: names written in the text can end in the same word ("source item={}", "clone item={}"): still one
        // key twice. Then the values' own names (sourceId, cloneId), else the key numbered
        Map<String, Long> stillTwice = new HashMap<>();
        args.forEach(a -> stillTwice.merge(a.key(), 1L, Long::sum));
        Map<String, Integer> seen = new HashMap<>();
        for (int i = 0; i < args.size(); i++) {
            Arg a = args.get(i);
            if (stillTwice.get(a.key()) <= 1) {
                continue;
            }
            String derived = DomainValues.derivedKey(a.expr());
            String key = derived != null && !derived.isBlank() && args.stream().noneMatch(o -> o != a && styled(derived).equals(o.key()))
                    ? styled(derived) : a.key() + (seen.merge(a.key(), 1, Integer::sum));
            args.set(i, new Arg(a.expr(), a.src(), key, false));
        }

        // "Pledge {} published" with pledgeId in MDC: the placeholder right after its own noun goes, the sentence stays
        List<String> inPlaceDeps = new ArrayList<>();
        int idsOut = 0;   // #315: ids this rewrite takes out of the text (they are in MDC)
        List<String> inPlaceStems = new ArrayList<>();   // nouns left where their id was: a trailing "for tenant" goes later
        if (MessageTemplate.count(raw) == args.size() && !args.isEmpty()) {
            Set<String> inMdcNow = lc.offThread() ? Set.of() : mdcAt(m, lc);   // another thread: MDC is empty there
            java.util.regex.Matcher ph = java.util.regex.Pattern.compile("\\{}").matcher(raw);
            StringBuilder sb = new StringBuilder();
            List<Arg> keptArgs = new ArrayList<>();
            int from = 0;
            int i = 0;
            boolean changed = false;
            while (ph.find()) {
                Arg a = args.get(i++);
                String before = raw.substring(from, ph.start());
                String stem = java.util.regex.Pattern.quote(MessageTemplate.stem(a.key()));
                java.util.regex.Matcher noun = java.util.regex.Pattern
                        .compile("(?i)(\\b" + stem + "s?)((?:\\s+(?:id|no\\.?|number))?\\s*#?\\s*)$").matcher(before);
                if (a.registry() && inMdcNow.contains(a.key()) && noun.find()) {
                    String head = before.substring(0, noun.start(1));
                    String rest = raw.substring(ph.end());
                    if (head.stripTrailing().endsWith("(") && rest.stripLeading().startsWith(")")) {
                        // #310: "not found (order {})": the group only named the value, it goes with it
                        String open = head.stripTrailing();
                        sb.append(open, 0, open.length() - 1);
                        from = ph.end() + rest.indexOf(')') + 1;
                        idsOut++;
                        String wrap = ctx.plannedBy(m, a.key());
                        if (wrap != null) {
                            inPlaceDeps.add(wrap);
                        }
                        changed = true;
                        continue;
                    }
                    sb.append(before, 0, noun.end(1));
                    idsOut++;
                    inPlaceStems.add(MessageTemplate.stem(a.key()));
                    String wrap = ctx.plannedBy(m, a.key());
                    if (wrap != null) {
                        inPlaceDeps.add(wrap);
                    }
                    changed = true;
                } else {
                    sb.append(before).append("{}");
                    keptArgs.add(a);
                }
                from = ph.end();
            }
            if (changed) {
                sb.append(raw.substring(from));
                raw = sb.toString().replaceAll(" {2,}", " ").replaceAll(" ([,.;:!?])", "$1");
                args = keptArgs;
                issues.add(Rules.LOG_FORMAT);
            }
        }

        boolean mismatch = MessageTemplate.count(raw) != args.size();
        List<String> existingKeys = MessageTemplate.compliantKeys(raw);
        boolean compliant = existingKeys != null && existingKeys.size() == args.size() && !mismatch;
        if (compliant && !existingKeys.equals(args.stream().map(a -> logKey(a.key())).toList())) {
            issues.add(Rules.LOG_KEY_STYLE);
        }
        String unnamed = MessageTemplate.removeNamedPlaceholders(raw);
        boolean namedPlaceholderInProse = !compliant
                && named.stream().anyMatch(Objects::nonNull)
                && !MessageTemplate.placeholdersTrailing(raw);
        boolean mechanical = !mismatch && !namedPlaceholderInProse
                && (compliant || MessageTemplate.placeholdersTrailing(unnamed));
        if (!compliant && !namedPlaceholderInProse) {
            raw = unnamed;
        }
        if (!compliant) {
            issues.add(Rules.LOG_FORMAT);
        }

        // drop values already in MDC, PII and e.getMessage() when the exception itself is logged; a line that runs on
        // another thread (no MDC propagation) keeps its values: MDC is empty there
        Set<String> inMdc = lc.offThread() ? Set.of() : mdcAt(m, lc);
        List<String> plannedDeps = new ArrayList<>(inPlaceDeps);
        List<Arg> kept = new ArrayList<>();
        List<String> droppedStems = new ArrayList<>();
        for (Arg a : args) {
            // a vocabulary key, or a key outside the vocabulary that an existing scope sets, logged from the variable of the
            // same name (base class scopes "qaoCode" around the abstract step, the step logs [qaoCode={}] again)
            if (inMdc.contains(a.key()) && (a.registry() || ctx.values().isKey(a.expr(), a.key(), m))) {
                String wrap = ctx.plannedBy(m, a.key());
                if (wrap != null) {
                    plannedDeps.add(wrap);
                }
                idsOut++;
                droppedStems.add(MessageTemplate.stem(a.key()));
                issues.add(Rules.LOG_FORMAT);
            } else if (ctx.domain.isPii(a.expr())) {
                droppedStems.add(MessageTemplate.stem(a.key()));
                issues.add(Rules.PII_IN_LOG);
            } else if (throwableSrc != null && a.src().equals(throwableSrc + ".getMessage()")) {
                issues.add(Rules.LOG_FORMAT);
            } else if (c != null && !ctx.policy.logging().exceptionMessages()
                    && a.src().matches(java.util.regex.Pattern.quote(c.param) + "\\.get(Localized)?Message\\(\\)")) {
                // #278: the raw message may carry personal data or a response body: the class instead, once
                issues.add(Rules.LOG_FORMAT);
                String cls = c.param + ".getClass().getSimpleName()";
                if (args.stream().noneMatch(x -> x.src().equals(cls)) && kept.stream().noneMatch(x -> x.src().equals(cls))) {
                    kept.add(new Arg(a.expr(), cls, ctx.policy.logKey("errorType"), false));
                }
            } else {
                kept.add(a);
            }
        }

        if (detached != null) {
            String cls = c.param + ".getClass().getSimpleName()";
            if (kept.stream().noneMatch(x -> x.src().equals(cls))) {
                kept.add(new Arg(detached, cls, ctx.policy.logKey("errorType"), false));
            }
        }
        // #310: "thing {} order {}" names each value by a noun; with the values gone from the prose (into MDC or the
        // [key=…] suffix) those nouns would stay behind without values
        if (!compliant && !mismatch) {
            List<java.util.Set<String>> labels = new ArrayList<>();
            for (Arg a : args) {
                java.util.Set<String> l = new java.util.HashSet<>();
                l.add(MessageTemplate.stem(a.key()).toLowerCase(java.util.Locale.ROOT));
                String name = a.src().replaceAll(".*\\.", "").replaceAll("\\(\\)$", "").replaceFirst("^get(?=[A-Z])", "");
                l.add(name.toLowerCase(java.util.Locale.ROOT));
                l.add(MessageTemplate.stem(Text.lowerFirst(name)).toLowerCase(java.util.Locale.ROOT));
                labels.add(l);
            }
            raw = MessageTemplate.dropLabels(raw, labels);
        }
        String base = compliant ? MessageTemplate.compliantBase(raw) : MessageTemplate.stripPlaceholders(raw);
        if (!compliant && mechanical) {
            List<String> stems = new ArrayList<>(droppedStems);
            stems.addAll(inPlaceStems);
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
        if (idsOut > 0) {
            b.data("idsOutOfText", idsOut);   // #315: counted in the apply summary
        }
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

    /**
     * The value logged is the key's own: the variable of that name, or an accessor named after it
     * ({@code summary.runCode()}, {@code job.getRunCode()}).
     */
    static boolean sameValue(String src, String key) {
        // #112: a key in another style (run.code, run_code, RunCode) names the Java value runCode
        String name = javaName(key);
        String cap = name.isEmpty() ? name : Character.toUpperCase(name.charAt(0)) + name.substring(1);
        return src.equals(key) || src.equals(name) || src.endsWith("." + name + "()") || src.endsWith(".get" + cap + "()");
    }

    /** {@code run.code} / {@code run_code} / {@code RunCode} → {@code runCode}: the Java name a key stands for. */
    static String javaName(String key) {
        StringBuilder sb = new StringBuilder();
        boolean up = false;
        for (char c : key.toCharArray()) {
            if (c == '.' || c == '_' || c == '-') {
                up = sb.length() > 0;
            } else {
                sb.append(up ? Character.toUpperCase(c) : sb.length() == 0 ? Character.toLowerCase(c) : c);
                up = false;
            }
        }
        return sb.toString();
    }

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
