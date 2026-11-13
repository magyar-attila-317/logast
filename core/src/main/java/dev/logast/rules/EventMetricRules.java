package dev.logast.rules;

import dev.logast.graph.CallGraph;
import dev.logast.model.Entrypoint;
import dev.logast.model.LogCall;
import dev.logast.model.MethodInfo;
import dev.logast.orders.Edit;
import dev.logast.parse.Pos;
import dev.logast.parse.SourceFileInfo;
import org.openrewrite.java.tree.J;

import java.util.*;
import java.util.regex.Pattern;

/**
 * BUSINESS_EVENT_UNMEASURED ({@code metrics} pack): a method that logs a business lifecycle event ("Refund started",
 * "Order sync completed", "Payment capture failed") but measures nothing gets an Observation around its body. One
 * {@code ObservedContext} yields a timer and a count (with the error/outcome), and a span, named after the event's
 * subject in vocabulary terms ({@code refund}, {@code order.sync}).
 * <p>Skipped: entrypoints the framework already times (HTTP, @Scheduled, observed Kafka), methods an entrypoint fix
 * already wraps, methods that already create meters or observations, and events logged per loop iteration.
 */
final class EventMetricRules {
    private static final Set<String> EVENTS = Set.of("started", "completed", "failed");
    /** Already observed: by Spring (HTTP, @Scheduled, Kafka), or by the starter's BatchTraceListener (runners). */
    private static final Set<Entrypoint.Kind> TIMED_BY_FRAMEWORK = EnumSet.of(Entrypoint.Kind.HTTP, Entrypoint.Kind.SCHEDULED,
            Entrypoint.Kind.KAFKA, Entrypoint.Kind.BATCH);
    private static final Pattern MEASURED = Pattern.compile(
            "ObservedContext|Observation\\.|MeterRegistry|meterRegistry|\\bCounter\\.|\\bTimer\\.|\\.counter\\(|\\.timer\\(");
    private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*){0,3}");

    private final RuleContext ctx;

    EventMetricRules(RuleContext ctx) {
        this.ctx = ctx;
    }

    void run() {
        if (!ctx.enabled(Rules.BUSINESS_EVENT_UNMEASURED) || !ctx.policy.metrics().events()) {
            return;
        }
        Set<String> timed = new HashSet<>();
        for (Entrypoint ep : ctx.a.entrypoints) {
            if ((ep.observedByFramework() || ep.kind() == Entrypoint.Kind.BATCH) && TIMED_BY_FRAMEWORK.contains(ep.kind())) {
                timed.add(ep.method().key());
            }
        }
        // candidates first: a business event called from another observed method is part of that observation
        Map<String, Event> candidates = new LinkedHashMap<>();
        for (MethodInfo m : ctx.methods()) {
            if (m.decl().getBody() == null || m.decl().isConstructor() || timed.contains(m.key())
                    || ctx.wrappedMethods.contains(m.key()) || measured(m) || ctx.legacyMdc(m)) {
                continue;
            }
            Event e = detect(m);
            if (e != null) {
                candidates.put(m.key(), e);
            }
        }
        for (var entry : candidates.entrySet()) {
            MethodInfo m = ctx.a.index.method(entry.getKey());
            if (!insideObservation(m, candidates, timed, 0, new HashSet<>())) {
                emit(m, entry.getValue());
            }
        }
    }

    /**
     * Called (up to {@link EntrypointRules#OWN_FLOW_DEPTH} levels up) by a method that is observed or will be: its
     * timer already covers this work. E.g. listener (observed) → job.execute() → doExecute() overrides: one timer for
     * the run, not a nested one for whichever implementation happens to log a lifecycle.
     */
    private boolean insideObservation(MethodInfo m, Map<String, Event> candidates, Set<String> timed, int depth, Set<String> seen) {
        if (depth >= EntrypointRules.OWN_FLOW_DEPTH || !seen.add(m.key())) {
            return false;
        }
        for (CallGraph.Edge e : ctx.a.graph.in(m)) {
            MethodInfo caller = e.from();
            if (candidates.containsKey(caller.key()) || timed.contains(caller.key()) || ctx.wrappedMethods.contains(caller.key())
                    || caller.decl().getBody() != null && measured(caller)
                    || insideObservation(caller, candidates, timed, depth + 1, seen)) {
                return true;
            }
        }
        return false;
    }

    /** The business event a method logs: its subject and the first lifecycle log line. */
    private record Event(String subject, LogCall first) {
    }

    private boolean measured(MethodInfo m) {
        String body = m.file().slice(Pos.start(m.decl().getBody()), Pos.end(m.decl().getBody()));
        return MEASURED.matcher(body).find() || m.hasAnnotation("io.micrometer.observation.annotation.Observed")
                || m.hasAnnotation("io.micrometer.core.annotation.Timed") || m.hasAnnotation("io.micrometer.core.annotation.Counted");
    }

    private static final Pattern TRAILING_PREPOSITION = Pattern.compile("(?i).*\\b(to|for|on|after|before|with|from|of|in|at|by|into)$");

    /**
     * A business event: a started/completed message (failure-only logs are mostly technical: "Failed to marshal XML"),
     * whose subject is a short noun phrase, and which is either a full lifecycle (started + completed/failed in the
     * method) or names a vocabulary term ("Order sync", "Refund").
     */
    private Event detect(MethodInfo m) {
        Map<String, Set<String>> eventsBySubject = new LinkedHashMap<>();
        Map<String, LogCall> firstBySubject = new LinkedHashMap<>();
        for (LogCall lc : m.facts().logs) {
            if (lc.inLoop() || !(lc.template() instanceof J.Literal l) || !(l.getValue() instanceof String raw)) {
                continue;
            }
            String text = MessageTemplate.compliantKeys(raw) != null ? MessageTemplate.compliantBase(raw) : MessageTemplate.stripPlaceholders(raw);
            String event = ctx.phrasing.eventOf(text);
            String s = event == null || !EVENTS.contains(event) ? null : ctx.phrasing.subjectOf(text);
            if (s == null || s.isBlank() || s.split("\\s+").length > 3 || !s.matches("[\\p{L}][\\p{L}0-9 \\-]*")
                    || TRAILING_PREPOSITION.matcher(s).matches()) {
                continue;
            }
            String key = s.toLowerCase(Locale.ROOT);
            eventsBySubject.computeIfAbsent(key, k -> new HashSet<>()).add(event);
            firstBySubject.putIfAbsent(key, lc);
        }
        Set<String> terms = new HashSet<>();
        ctx.policy.terms().forEach(t -> terms.add(t.toLowerCase(Locale.ROOT)));
        String subject = null;
        LogCall first = null;
        for (var e : eventsBySubject.entrySet()) {
            Set<String> ev = e.getValue();
            boolean lifecycle = ev.contains("started") && (ev.contains("completed") || ev.contains("failed"));
            boolean business = Arrays.stream(e.getKey().split("[\\s\\-]+")).anyMatch(w -> terms.contains(w)
                    || w.endsWith("s") && terms.contains(w.substring(0, w.length() - 1)));
            if ((ev.contains("started") || ev.contains("completed")) && (lifecycle || business)) {
                first = firstBySubject.get(e.getKey());
                subject = MessageTemplate.stripPlaceholders(e.getKey());
                break;
            }
        }
        return subject == null ? null : new Event(subject, first);
    }

    private void emit(MethodInfo m, Event ev) {
        String subject = ev.subject();
        LogCall first = ev.first();
        String name = MetricRules.canonicalName(ctx.policy, subject.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "."), ctx.area(m));
        if (!NAME.matcher(name).matches()) {
            return;
        }
        SourceFileInfo f = m.file();
        String text = f.text();
        int line = Pos.line(first.call());
        if (ctx.ignored(f, line, Rules.BUSINESS_EVENT_UNMEASURED)) {
            return;
        }
        int[] inner = JavaEdits.blockInner(text, m.decl().getBody());
        OrderBuilder b = ctx.order(Rules.BUSINESS_EVENT_UNMEASURED, m, line, "event")
                .title("'" + subject + "' is logged as a business event but not measured: no count, duration or error rate")
                .context(ctx.context(m, Pos.start(first.call()), Text.oneLine(first.call().printTrimmed(), 160), null));
        if (inner == null) {
            b.suggestion("Wrap the method body in ObservedContext.entrypoint(\"" + name + "\") (timer + count + span).");
            return;
        }
        String unit = Text.indentUnit(text);
        String indent = Text.indentAt(text, Pos.start(m.decl().getBody())) + unit;
        Set<String> taken = ctx.taken(m, inner[0]);
        String body = text.substring(inner[0], inner[1]);
        // quiet: the method already logs the event itself; this adds the count, duration and span
        String replacement = EntrypointRules.observedWrap(m, name, List.of(".quiet()"), Text.reindent(body, unit), indent, unit, taken,
                ctx.supportHasRun());
        b.suggestion("Observation '" + name + "': Micrometer derives the timer " + name + " (count, duration, error tag) "
                + "and a span; rename it if the subject is not the business name.");
        b.edit(Edit.replace(f.relPath(), inner[0], inner[1], replacement));
        b.edits(JavaEdits.imports(f, ctx.support("ObservedContext")));
        ctx.dependOnStarter(b);
    }
}
