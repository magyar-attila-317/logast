package dev.logast.kb;

import dev.logast.facts.Node;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * #159: what runs when, from the jobs in the facts: {@code schedule} + {@code scheduleFormat} (a mapping's jobs,
 * CronJobs), else {@code cronResolved} / {@code cron} ({@code @Scheduled}, Spring's six fields), else
 * {@code fixedRate} / {@code fixedDelay} (every N milliseconds).
 */
public final class Schedules {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    private Schedules() {
    }

    /** A job's schedule: a cron, an interval, or why there is none usable. */
    /** @param after #219: in a chain, the job it starts after: the cron is when the chain starts, not this job */
    record Schedule(KnowledgeBase.Fact<Node> job, Cron cron, String every, String problem, String trigger, String zoneOfTrigger, String after) {
        Schedule(KnowledgeBase.Fact<Node> job, Cron cron, String every, String problem) {
            this(job, cron, every, problem, null, null, null);
        }

        Schedule(KnowledgeBase.Fact<Node> job, Cron cron, String every, String problem, String trigger) {
            this(job, cron, every, problem, trigger, null, null);
        }

        /** " · <trigger>" when the job has several; in a chain, after which job. */
        String triggerLabel() {
            return (trigger == null ? "" : " · " + trigger) + (after == null ? "" : ", after " + after + " (the chain starts then)");
        }

        /** #219: a time of this schedule as it reads: a later step of a chain runs then at the earliest. */
        String at(String time) {
            return after == null ? time : "≥ " + time;
        }

        ZoneId zone(ZoneId fallback) {
            Object z = zoneOfTrigger != null ? zoneOfTrigger : job.fact().attrs().get("zone");
            try {
                return z == null || z.toString().isBlank() ? fallback : ZoneId.of(z.toString());
            } catch (RuntimeException e) {
                return fallback;
            }
        }
    }

    /** #197: every schedule of a job: one per trigger when its configuration lists several, else its one schedule. */
    static List<Schedule> all(KnowledgeBase.Fact<Node> job, String env) {
        Map<String, Object> a = job.fact().attrs();
        if (a.get("triggers") instanceof List<?> triggers && !triggers.isEmpty()) {
            Cron.Format format = "quartz".equals(a.get("entrypoint")) ? Cron.Format.QUARTZ : Cron.Format.SPRING;
            List<Schedule> out = new ArrayList<>();
            for (Object t : triggers) {
                if (!(t instanceof Map<?, ?> m) || m.get("cron") == null) {
                    continue;
                }
                String expr = String.valueOf(m.get("cron"));
                String label = m.get("name") != null ? String.valueOf(m.get("name")) : expr;
                try {
                    out.add(new Schedule(job, Cron.parse(expr, format), null, null, label, m.get("zone") == null ? null : String.valueOf(m.get("zone")),
                            m.get("runsAfter") == null ? null : String.valueOf(m.get("runsAfter"))));
                } catch (IllegalArgumentException e) {
                    out.add(new Schedule(job, null, null, e.getMessage(), label));
                }
            }
            if (!out.isEmpty()) {
                return out;
            }
        }
        Schedule one = of(job, env);
        return one == null ? List.of() : List.of(one);
    }

    static Schedule of(KnowledgeBase.Fact<Node> job) {
        return of(job, null);
    }

    /** #189: in an environment, a cron its profile's configuration sets wins over the base one. */
    static Schedule of(KnowledgeBase.Fact<Node> job, String env) {
        Map<String, Object> a = job.fact().attrs();
        String expr;
        Cron.Format format;
        if (env != null && !env.isBlank() && a.get("cronByProfile") instanceof Map<?, ?> byProfile && byProfile.get(env) != null) {
            expr = String.valueOf(byProfile.get(env));
            format = "quartz".equals(a.get("entrypoint")) ? Cron.Format.QUARTZ : Cron.Format.SPRING;
        } else if (a.get("schedule") != null) {
            expr = a.get("schedule").toString();
            String sf = String.valueOf(a.get("scheduleFormat"));
            format = "spring".equalsIgnoreCase(sf) ? Cron.Format.SPRING : "quartz".equalsIgnoreCase(sf) ? Cron.Format.QUARTZ : Cron.Format.UNIX;
        } else if (a.get("cronResolved") != null || a.get("cron") != null) {
            expr = String.valueOf(a.get("cronResolved") != null ? a.get("cronResolved") : a.get("cron"));
            format = "quartz".equals(a.get("entrypoint")) ? Cron.Format.QUARTZ : Cron.Format.SPRING;   // #187
        } else if (a.get("fixedRate") != null || a.get("fixedDelay") != null) {
            boolean rate = a.get("fixedRate") != null;
            String key = rate ? "fixedRate" : "fixedDelay";
            Object value = a.get(key + "Resolved") != null ? a.get(key + "Resolved") : a.get(key);
            String every = "every " + interval(String.valueOf(value), a.get("timeUnit") == null ? null : String.valueOf(a.get("timeUnit")));
            return new Schedule(job, null, rate ? every : every + " after the last run ended", null);
        } else {
            return null;
        }
        if (expr.contains("${")) {
            return new Schedule(job, null, null, "'" + expr + "' has a placeholder with no value in the service's config");
        }
        if (expr.equals("-")) {
            return new Schedule(job, null, null, "disabled ('-')");
        }
        try {
            return new Schedule(job, Cron.parse(expr, format), null, null);
        } catch (IllegalArgumentException e) {
            return new Schedule(job, null, null, e.getMessage());
        }
    }

    /**
     * #178: an interval as {@code @Scheduled} reads it, readable: a number in {@code timeUnit} (milliseconds by default,
     * underscores allowed), an ISO duration ({@code PT10M}), or {@code 10s}/{@code 5m}-style; as written otherwise
     * (a placeholder with no value, a SpEL expression).
     */
    static String interval(String value, String timeUnit) {
        String v = value.strip().replace("_", "");
        java.time.Duration d = null;
        try {
            if (v.matches("\\d+")) {
                java.util.concurrent.TimeUnit unit = timeUnit == null ? java.util.concurrent.TimeUnit.MILLISECONDS
                        : java.util.concurrent.TimeUnit.valueOf(timeUnit.toUpperCase(Locale.ROOT));
                d = java.time.Duration.ofMillis(unit.toMillis(Long.parseLong(v)));
            } else if (v.toUpperCase(Locale.ROOT).startsWith("P")) {
                d = java.time.Duration.parse(v.toUpperCase(Locale.ROOT));
            } else if (v.matches("(?i)\\d+(ms|s|m|h|d)")) {
                long n = Long.parseLong(v.replaceAll("\\D", ""));
                d = switch (v.replaceAll("\\d", "").toLowerCase(Locale.ROOT)) {
                    case "ms" -> java.time.Duration.ofMillis(n);
                    case "s" -> java.time.Duration.ofSeconds(n);
                    case "m" -> java.time.Duration.ofMinutes(n);
                    case "h" -> java.time.Duration.ofHours(n);
                    default -> java.time.Duration.ofDays(n);
                };
            }
        } catch (RuntimeException e) {
            d = null;
        }
        if (d == null) {
            return value + (timeUnit == null ? " (as written)" : " " + timeUnit.toLowerCase(Locale.ROOT) + " (as written)");
        }
        long ms = d.toMillis();
        if (ms % 86_400_000 == 0) {
            return ms / 86_400_000 + " d";
        }
        if (ms % 3_600_000 == 0) {
            return ms / 3_600_000 + " h";
        }
        if (ms % 60_000 == 0) {
            return ms / 60_000 + " min";
        }
        if (ms % 1000 == 0) {
            return ms / 1000 + " s";
        }
        return ms + " ms";
    }

    /** A weekday ("monday", "mon") as the next such date from today (today included), or an ISO date. */
    static LocalDate day(String text, LocalDate today) {
        String t = text.strip().toLowerCase(Locale.ROOT);
        for (DayOfWeek d : DayOfWeek.values()) {
            String name = d.name().toLowerCase(Locale.ROOT);
            if (t.equals(name) || t.equals(name.substring(0, 3))) {
                LocalDate x = today;
                while (x.getDayOfWeek() != d) {
                    x = x.plusDays(1);
                }
                return x;
            }
        }
        if (t.equals("today")) {
            return today;
        }
        if (t.equals("tomorrow")) {
            return today.plusDays(1);
        }
        return LocalDate.parse(t);
    }

    private static boolean inEnv(Node n, String env) {
        return env == null || env.isBlank() || n.env() == null || n.env().equals(env);
    }

    /** Every job that runs on the day (in the environment), by first time; the jobs that run after them; the unknowns. */
    public static String runsOn(KnowledgeBase kb, String dayText, String env, String zoneText, LocalDate today) {
        LocalDate day = day(dayText, today);
        ZoneId zone = ZoneId.of(zoneText == null || zoneText.isBlank() ? "UTC" : zoneText);
        record Hit(String line, ZonedDateTime first, String id) {
        }
        List<Hit> hits = new ArrayList<>();
        List<String> always = new ArrayList<>();
        List<String> unknown = new ArrayList<>();
        Set<String> ran = new LinkedHashSet<>();
        for (KnowledgeBase.Fact<Node> f : kb.nodes()) {
            if (!f.fact().kind().equals("job") || !inEnv(f.fact(), env)) {
                continue;
            }
            for (Schedule s : all(f, env)) {
            String label = f.fact().id() + (f.fact().env() == null ? "" : " [" + f.fact().env() + "]") + s.triggerLabel();
            String src = " (" + KnowledgeBase.cite(f.fact().evidence(), f.header()) + ")";
            if (s.problem() != null) {
                unknown.add("- " + label + ": " + s.problem() + src);
            } else if (s.every() != null) {
                always.add("- " + label + ": " + s.every() + src);
                ran.add(f.fact().id());
            } else {
                ZoneId z = s.zone(zone);
                List<ZonedDateTime> times = s.cron().on(day, z);
                if (!times.isEmpty()) {
                    List<String> shown = times.stream().limit(6).map(t -> t.withZoneSameInstant(zone).format(TIME)).toList();
                    hits.add(new Hit("- " + s.at(shown.getFirst()) + "  " + label + "  " + s.cron().expression()
                            + (times.size() > 1 ? "  (" + times.size() + " runs: " + String.join(", ", shown) + (times.size() > 6 ? ", …" : "") + ")" : "")
                            + (z.equals(zone) ? "" : "  [" + z + "]") + src, times.getFirst(), f.fact().id()));
                    ran.add(f.fact().id());
                }
            }
            }
        }
        hits.sort(Comparator.comparing(Hit::first).thenComparing(Hit::line));
        StringBuilder sb = new StringBuilder(day + " (" + day.getDayOfWeek().name().toLowerCase(Locale.ROOT) + "), times in " + zone
                + (env == null || env.isBlank() ? ", every environment" : ", " + env) + ":\n");
        if (hits.isEmpty() && always.isEmpty()) {
            sb.append("no job with a schedule runs that day\n");
        }
        hits.forEach(h -> sb.append(h.line()).append('\n'));
        if (!always.isEmpty()) {
            sb.append("all day, at an interval:\n");
            always.forEach(l -> sb.append(l).append('\n'));
        }
        List<String> after = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>(ran);
        List<String> frontier = new ArrayList<>(ran);
        while (!frontier.isEmpty()) {
            List<String> next = new ArrayList<>();
            for (String id : frontier) {
                for (var e : kb.outgoing(id)) {
                    boolean step = e.fact().kind().equals("after") || e.fact().kind().equals("runs") && e.fact().to().startsWith("job:");   // #212
                    if (step && (env == null || env.isBlank() || e.fact().env() == null
                            || Objects.equals(e.fact().env(), env)) && seen.add(e.fact().to())) {
                        after.add("- " + e.fact().to() + (e.fact().kind().equals("runs") ? ", run by " : " after ") + id
                                + " (" + KnowledgeBase.cite(e.fact().evidence(), e.header()) + ")");
                        next.add(e.fact().to());
                    }
                }
            }
            frontier = next;
        }
        if (!after.isEmpty()) {
            sb.append("then, in a pipeline:\n");
            after.forEach(l -> sb.append(l).append('\n'));
        }
        if (!unknown.isEmpty()) {
            sb.append("schedule not known:\n");
            unknown.forEach(l -> sb.append(l).append('\n'));
        }
        return sb.toString();
    }

    /** #212: the nearest job with a schedule that runs {@code id}, following {@code runs} back (4 steps; not {@code after}: that starts later). */
    static KnowledgeBase.Fact<Node> scheduledRunner(KnowledgeBase kb, String id, String env) {
        Set<String> seen = new LinkedHashSet<>(List.of(id));
        List<String> frontier = List.of(id);
        for (int depth = 0; depth < 4 && !frontier.isEmpty(); depth++) {
            List<String> next = new ArrayList<>();
            for (String x : frontier) {
                for (var e : kb.incoming(x)) {
                    String from = e.fact().from();
                    boolean envOk = env == null || env.isBlank() || e.fact().env() == null || Objects.equals(e.fact().env(), env);
                    if (!e.fact().kind().equals("runs") || !from.startsWith("job:") || !envOk || !seen.add(from)) {
                        continue;
                    }
                    for (KnowledgeBase.Fact<Node> n : kb.nodes()) {
                        boolean triggered = kb.incoming(from).stream().anyMatch(i -> i.fact().kind().equals("runs") && i.fact().from().startsWith("job:"));
                        if (n.fact().id().equals(from) && !triggered && inEnv(n.fact(), env) && !all(n, env).isEmpty()
                                && all(n, env).stream().allMatch(sc -> sc.problem() == null)) {
                            return n;
                        }
                    }
                    next.add(from);
                }
            }
            frontier = next;
        }
        return null;
    }

    /** The next times a job runs (in the environment). */
    public static String nextRuns(KnowledgeBase kb, String id, String env, int count, String zoneText, ZonedDateTime now) {
        ZoneId zone = ZoneId.of(zoneText == null || zoneText.isBlank() ? "UTC" : zoneText);
        StringBuilder sb = new StringBuilder();
        for (KnowledgeBase.Fact<Node> f : kb.nodes()) {
            if (!f.fact().id().equals(id) || !inEnv(f.fact(), env)) {
                continue;
            }
            List<Schedule> ss = all(f, env);
            if (ss.isEmpty()) {
                sb.append(id).append(f.fact().env() == null ? "" : " [" + f.fact().env() + "]").append(": ");
                KnowledgeBase.Fact<Node> by = scheduledRunner(kb, id, f.fact().env() != null ? f.fact().env() : env);
                if (by == null) {
                    sb.append("no schedule in the facts (it may run in a pipeline or when called)\n");
                } else {   // #212: run by a pipeline (through the workload that starts it): the pipeline's times
                    sb.append("run by ").append(by.fact().id()).append(", which runs at:\n");
                    sb.append(nextRuns(kb, by.fact().id(), by.fact().env() != null ? by.fact().env() : env, count, zoneText, now));
                }
            }
            for (Schedule s : ss) {
            sb.append(id).append(f.fact().env() == null ? "" : " [" + f.fact().env() + "]").append(s.triggerLabel()).append(": ");
            if (s.problem() != null) {
                sb.append(s.problem()).append('\n');
            } else if (s.every() != null) {
                sb.append(s.every()).append('\n');
            } else {
                ZoneId z = s.zone(zone);
                sb.append(s.cron().expression()).append(z.equals(zone) ? "" : " [" + z + "]").append('\n');
                s.cron().next(now.withZoneSameInstant(z), count)
                        .forEach(t -> sb.append("- ").append(s.at(t.withZoneSameInstant(zone).format(DateTimeFormatter.ofPattern("EEE yyyy-MM-dd HH:mm:ss", Locale.ROOT)))).append('\n'));
            }
            }
        }
        return sb.isEmpty() ? "No job " + id + (env == null ? "" : " in " + env) + ". " + kb.find(id.substring(id.lastIndexOf(':') + 1), "job", 5) : sb.toString();
    }
}
