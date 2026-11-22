package dev.logast.export;

import dev.logast.graph.CodeIndex;
import dev.logast.model.ClassInfo;
import dev.logast.model.MethodInfo;
import dev.logast.trace.Tracer;
import dev.logast.trace.Types;
import dev.logast.trace.Value;
import org.openrewrite.java.tree.J;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * #217: Quartz schedules found by following the code with the {@link Tracer}: the configurations' {@code @Bean} and
 * {@code @PostConstruct} methods run on the bound configuration; each trigger they build ({@code CronTriggerFactoryBean},
 * {@code TriggerBuilder…build()}) gives its cron, zone, name and job (through the {@code JobDetail} and the job class
 * it is given, an enum constant's argument included). A chain: a global {@code JobListener} of the project is run as
 * Quartz runs it after each job, with the trigger's {@code JobDataMap}; the job it triggers next
 * ({@code triggerJob}, or {@code scheduleJob} of a trigger {@code forJob} it), with the data it passes on, is the next
 * step, until it triggers none.
 */
final class QuartzTrace {
    private static final int MAX_STEPS = 60;

    /** A step of a run: the job class, the bean method of its JobDetail, the values its data carries. */
    record Step(String job, String detail, Map<String, String> values) {
    }

    /** What one trigger runs: its cron (and the key it came from), zone, name, and the jobs one after another. */
    record Run(String name, String group, String cron, String cronFrom, String zone, List<Step> steps) {
    }

    private QuartzTrace() {
    }

    static List<Run> runs(CodeIndex index, Map<String, String> config) {
        List<Run> out = new ArrayList<>();
        if (index.classes().stream().noneMatch(c -> c.file().text().contains("org.quartz") || c.file().text().contains("scheduling.quartz"))) {
            return out;
        }
        Tracer t = new Tracer(new Types(index), config);
        boolean diag = dev.logast.trace.Diagnostics.enabled();
        dev.logast.trace.Diagnostics.reset();
        List<String> ran = new ArrayList<>();
        for (ClassInfo c : index.classes()) {
            if (!Types.annotated(c, "Configuration")) {
                continue;
            }
            for (MethodInfo m : index.methodsOf(c.fqn())) {
                Value v = null;
                if (Tracer.annotated(m, "Bean")) {
                    v = t.bean(m);
                } else if (Tracer.annotated(m, "PostConstruct") && m.decl().getParameters().stream().noneMatch(p -> p instanceof J.VariableDeclarations)) {
                    v = t.run(m, t.instance(c), List.of());
                }
                if (v != null && diag) {
                    ran.add(c.simpleName() + "." + m.name() + " → " + (v instanceof Value.Unknown u ? "not known: " + u.why() : v));
                }
            }
        }
        List<Value.Obj> listeners = new ArrayList<>();
        for (Value.Obj o : new ArrayList<>(t.created)) {
            ClassInfo c = index.clazz(o.type);
            if (c != null && Types.parents(c).contains("JobListener") && listeners.stream().noneMatch(x -> x.type.equals(o.type))) {
                listeners.add(o);
            }
        }
        for (Value.Obj trig : new ArrayList<>(t.created)) {
            if (!(trig.type.equals("CronTriggerFactoryBean") || trig.type.equals("Trigger") && trig.fields.containsKey("withSchedule"))) {
                continue;
            }
            Value cron = trig.fields.get("cronExpression");
            if (cron == null && trig.fields.get("withSchedule") instanceof Value.Obj sb) {
                cron = sb.fields.get("cronSchedule");
            }
            if (!(cron instanceof Value.Lit c) || c.text() == null) {
                continue;
            }
            Value.Obj detail = detail(trig.fields.containsKey("jobDetail") ? trig.fields.get("jobDetail") : trig.fields.get("forJob"));
            String job = jobClass(detail);
            if (job == null) {
                continue;
            }
            Value.MapV data = trig.fields.get("jobDataMap") instanceof Value.MapV m ? m
                    : trig.fields.get("usingJobData") instanceof Value.MapV m2 ? m2 : new Value.MapV();
            List<Step> steps = new ArrayList<>();
            steps.add(new Step(job, detail.bean, values(data)));
            chain(t, index, listeners, detail, data, steps);
            String zone = trig.fields.get("timeZone") instanceof Value.Lit z ? z.text()
                    : trig.fields.get("inTimeZone") instanceof Value.Lit z2 ? z2.text() : null;
            String name = trig.fields.get("name") instanceof Value.Lit n ? n.text() : null;
            String group = trig.fields.get("group") instanceof Value.Lit g ? g.text() : null;
            out.add(new Run(name, group, c.text(), c.from(), zone, steps));
        }
        if (diag) {
            report(t, ran, listeners, out);
        }
        return out;
    }

    private static String text(Value v) {
        return v instanceof Value.Lit l ? l.text() + (l.from() == null ? "" : " (" + l.from() + ")") : String.valueOf(v);
    }

    /** #218: what the tracer did, to stderr: where a run on a real service stopped. */
    private static void report(Tracer t, List<String> ran, List<Value.Obj> listeners, List<Run> runs) {
        java.io.PrintStream err = System.err;
        err.println("trace: ran " + ran.size() + " @Bean/@PostConstruct method(s)" + (t.outOfBudget ? ", the step budget ran out" : "")
                + " (" + t.steps() + " steps)");
        ran.forEach(r -> err.println("trace:   " + r));
        List<Value.Obj> triggers = t.created.stream().filter(o -> o.type.equals("CronTriggerFactoryBean")
                || o.type.equals("Trigger") && o.fields.containsKey("withSchedule")).toList();
        err.println("trace: triggers built: " + triggers.size());
        for (Value.Obj o : triggers) {
            Value.Obj d = detail(o.fields.containsKey("jobDetail") ? o.fields.get("jobDetail") : o.fields.get("forJob"));
            err.println("trace:   " + text(o.fields.get("name")) + " cron " + text(o.fields.get("cronExpression")) + " job "
                    + (d == null ? "not known (" + (o.fields.containsKey("jobDetail") ? o.fields.get("jobDetail") : "no setJobDetail") + ")"
                    : jobClass(d) != null ? jobClass(d) : "detail without a job class: " + d.fields.keySet()));
        }
        err.println("trace: listeners: " + listeners.stream().map(Value.Obj::simpleType).toList());
        runs.forEach(r -> err.println("trace:   run " + r.name() + ": " + r.steps().stream()
                .map(s -> s.job().substring(s.job().lastIndexOf('.') + 1)).toList()));
        err.println("trace: not known, most often:");
        dev.logast.trace.Diagnostics.unknowns().entrySet().stream().limit(20)
                .forEach(e -> err.println("trace:   " + e.getValue() + "× " + e.getKey()));
    }

    /** The next jobs: the listeners run after each, with the data the last step passed on. */
    private static void chain(Tracer t, CodeIndex index, List<Value.Obj> listeners, Value.Obj detail, Value.MapV data, List<Step> steps) {
        for (int i = 0; i < MAX_STEPS && !listeners.isEmpty(); i++) {
            t.events.clear();
            Value.Obj ctx = new Value.Obj("JobExecutionContext");
            Value.Obj trigger = new Value.Obj("Trigger");
            trigger.fields.put("jobDataMap", data);
            ctx.fields.put("trigger", trigger);
            ctx.fields.put("jobDetail", detail);
            ctx.fields.put("mergedJobDataMap", data);
            ctx.fields.put("scheduler", new Value.Obj("Scheduler"));
            for (Value.Obj l : listeners) {
                MethodInfo m = t.find(index.clazz(l.type), "jobWasExecuted", 2);
                if (m != null) {
                    t.run(m, l, List.of(ctx, Value.Lit.NULL));
                }
            }
            Value.Obj next = null;
            Value.MapV nextData = null;
            for (Tracer.Event e : t.events) {
                if (e.method().equals("triggerJob") && !e.args().isEmpty()) {
                    next = detail(e.args().getFirst());
                    nextData = e.args().size() > 1 && e.args().get(1) instanceof Value.MapV m ? m : new Value.MapV();
                } else if (e.method().equals("scheduleJob") && !e.args().isEmpty() && e.args().getLast() instanceof Value.Obj tr) {
                    next = detail(e.args().size() == 2 ? e.args().getFirst() : tr.fields.get("forJob"));
                    nextData = tr.fields.get("usingJobData") instanceof Value.MapV m ? m : new Value.MapV();
                }
                if (next != null) {
                    break;   // both branches of a condition not known: the first
                }
            }
            String job = jobClass(next);
            if (job == null) {
                return;
            }
            steps.add(new Step(job, next.bean, values(nextData)));
            detail = next;
            data = nextData;
        }
    }

    /** A JobDetail: itself, or the one a key ({@code getKey()}) is of. */
    private static Value.Obj detail(Value v) {
        if (v instanceof Value.Obj o && o.type.equals("Key") && o.fields.get("of") instanceof Value.Obj of) {
            return of;
        }
        return v instanceof Value.Obj o ? o : null;
    }

    private static String jobClass(Value.Obj detail) {
        if (detail == null) {
            return null;
        }
        Value c = detail.fields.containsKey("jobClass") ? detail.fields.get("jobClass") : detail.fields.get("newJob");
        return c instanceof Value.ClassRef r ? r.fqn() : null;
    }

    /** What a step's data carries: the scalars of its objects, a level deep (a job's config: its country, its delay). */
    private static Map<String, String> values(Value.MapV data) {
        Map<String, String> out = new TreeMap<>();
        for (var e : data.entries) {
            if (e.getValue() instanceof Value.Obj o) {
                for (var f : o.fields.entrySet()) {
                    if (f.getValue() instanceof Value.Obj inner) {
                        inner.fields.forEach((k, v) -> put(out, k, v));
                    } else {
                        put(out, f.getKey(), f.getValue());
                    }
                }
            } else if (e.getKey() instanceof Value.Lit k && k.text() != null) {
                put(out, k.text(), e.getValue());
            }
        }
        return out;
    }

    private static void put(Map<String, String> out, String k, Value v) {
        String s = v instanceof Value.Lit l ? l.text() : v instanceof Value.EnumConst e ? e.simple() : null;
        if (s != null && s.length() <= 120) {
            out.putIfAbsent(k, s);
        }
    }

    /**
     * The runs as each job's triggers, in the shape the export writes them (#197, #209): {@code cron}, {@code from} (the
     * configuration key of the cron), {@code name}, {@code zone}, and in a chain {@code chain}, {@code position},
     * {@code runsAfter}; with what the step's data carries.
     */
    static Map<String, List<Map<String, String>>> triggersByJob(List<Run> runs) {
        Map<String, List<Map<String, String>>> out = new LinkedHashMap<>();
        for (Run r : runs) {
            int n = r.steps().size();
            for (int k = 0; k < n; k++) {
                Step s = r.steps().get(k);
                Map<String, String> m = new TreeMap<>();
                m.put("cron", r.cron());
                m.put("from", r.cronFrom() != null ? r.cronFrom() : "code");
                if (r.name() != null) {
                    m.put("name", r.name());
                }
                if (r.zone() != null) {
                    m.put("zone", r.zone());
                }
                if (r.group() != null) {
                    m.put("group", r.group());
                }
                if (n > 1) {
                    m.put("chain", r.name() != null ? r.name() : r.cronFrom() != null ? r.cronFrom() : r.cron());
                    m.put("position", (k + 1) + " of " + n);
                    if (k > 0) {
                        String prev = r.steps().get(k - 1).job();
                        m.put("runsAfter", prev.substring(Math.max(prev.lastIndexOf('.'), prev.lastIndexOf('$')) + 1));
                    }
                }
                if (s.detail() != null) {
                    m.put("@detail", s.detail());
                }
                s.values().forEach(m::putIfAbsent);
                List<Map<String, String>> list = out.computeIfAbsent(s.job(), x -> new ArrayList<>());
                if (!list.contains(m)) {
                    list.add(m);
                }
            }
        }
        return out;
    }
}
