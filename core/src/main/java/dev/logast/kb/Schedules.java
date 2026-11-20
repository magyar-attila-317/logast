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
    record Schedule(KnowledgeBase.Fact<Node> job, Cron cron, String every, String problem) {
        ZoneId zone(ZoneId fallback) {
            Object z = job.fact().attrs().get("zone");
            try {
                return z == null || z.toString().isBlank() ? fallback : ZoneId.of(z.toString());
            } catch (RuntimeException e) {
                return fallback;
            }
        }
    }

    static Schedule of(KnowledgeBase.Fact<Node> job) {
        Map<String, Object> a = job.fact().attrs();
        String expr;
        Cron.Format format;
        if (a.get("schedule") != null) {
            expr = a.get("schedule").toString();
            format = "spring".equalsIgnoreCase(String.valueOf(a.get("scheduleFormat"))) ? Cron.Format.SPRING : Cron.Format.UNIX;
        } else if (a.get("cronResolved") != null || a.get("cron") != null) {
            expr = String.valueOf(a.get("cronResolved") != null ? a.get("cronResolved") : a.get("cron"));
            format = Cron.Format.SPRING;
        } else if (a.get("fixedRate") != null || a.get("fixedDelay") != null) {
            String rate = a.get("fixedRate") != null ? "every " + a.get("fixedRate") + " ms" : "every " + a.get("fixedDelay") + " ms after the last run ended";
            return new Schedule(job, null, rate, null);
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
            Schedule s = of(f);
            if (s == null) {
                continue;
            }
            String label = f.fact().id() + (f.fact().env() == null ? "" : " [" + f.fact().env() + "]");
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
                    hits.add(new Hit("- " + shown.getFirst() + "  " + label + "  " + s.cron().expression()
                            + (times.size() > 1 ? "  (" + times.size() + " runs: " + String.join(", ", shown) + (times.size() > 6 ? ", …" : "") + ")" : "")
                            + (z.equals(zone) ? "" : "  [" + z + "]") + src, times.getFirst(), f.fact().id()));
                    ran.add(f.fact().id());
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
                    if (e.fact().kind().equals("after") && (env == null || env.isBlank() || e.fact().env() == null
                            || Objects.equals(e.fact().env(), env)) && seen.add(e.fact().to())) {
                        after.add("- " + e.fact().to() + " after " + id + " (" + KnowledgeBase.cite(e.fact().evidence(), e.header()) + ")");
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

    /** The next times a job runs (in the environment). */
    public static String nextRuns(KnowledgeBase kb, String id, String env, int count, String zoneText, ZonedDateTime now) {
        ZoneId zone = ZoneId.of(zoneText == null || zoneText.isBlank() ? "UTC" : zoneText);
        StringBuilder sb = new StringBuilder();
        for (KnowledgeBase.Fact<Node> f : kb.nodes()) {
            if (!f.fact().id().equals(id) || !inEnv(f.fact(), env)) {
                continue;
            }
            Schedule s = of(f);
            sb.append(id).append(f.fact().env() == null ? "" : " [" + f.fact().env() + "]").append(": ");
            if (s == null) {
                sb.append("no schedule in the facts (it may run in a pipeline or when called)\n");
            } else if (s.problem() != null) {
                sb.append(s.problem()).append('\n');
            } else if (s.every() != null) {
                sb.append(s.every()).append('\n');
            } else {
                ZoneId z = s.zone(zone);
                sb.append(s.cron().expression()).append(z.equals(zone) ? "" : " [" + z + "]").append('\n');
                s.cron().next(now.withZoneSameInstant(z), count)
                        .forEach(t -> sb.append("- ").append(t.withZoneSameInstant(zone).format(DateTimeFormatter.ofPattern("EEE yyyy-MM-dd HH:mm:ss", Locale.ROOT))).append('\n'));
            }
        }
        return sb.isEmpty() ? "No job " + id + (env == null ? "" : " in " + env) + ". " + kb.find(id.substring(id.lastIndexOf(':') + 1), "job", 5) : sb.toString();
    }
}
