package dev.logast.kb;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * #159: a cron expression and the times it fires. Two dialects: {@link Format#UNIX} (5 fields: minute hour
 * day-of-month month day-of-week; Kubernetes CronJobs; when day-of-month and day-of-week are both restricted, a day
 * matching either fires) and {@link Format#SPRING} (6 fields, seconds first; {@code @Scheduled}; both must match).
 * Lists, ranges, steps, month and weekday names, {@code ?} and the macros ({@code @daily} …) are understood; Quartz's
 * {@code L}, {@code W} and {@code #} are not, and say so.
 */
public final class Cron {
    public enum Format { UNIX, SPRING }

    private static final Map<String, String> MACROS = Map.of("@yearly", "0 0 1 1 *", "@annually", "0 0 1 1 *",
            "@monthly", "0 0 1 * *", "@weekly", "0 0 * * 0", "@daily", "0 0 * * *", "@midnight", "0 0 * * *",
            "@hourly", "0 * * * *");
    private static final List<String> MONTHS = List.of("JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC");
    private static final List<String> DAYS = List.of("SUN", "MON", "TUE", "WED", "THU", "FRI", "SAT");

    private final String expression;
    private final BitSet seconds;
    private final BitSet minutes;
    private final BitSet hours;
    private final BitSet daysOfMonth;
    private final BitSet months;
    private final BitSet daysOfWeek;   // 0 = Sunday
    private final boolean domRestricted;
    private final boolean dowRestricted;
    private final Format format;

    private Cron(String expression, Format format, BitSet seconds, BitSet minutes, BitSet hours, BitSet dom, BitSet months,
                 BitSet dow, boolean domRestricted, boolean dowRestricted) {
        this.expression = expression;
        this.format = format;
        this.seconds = seconds;
        this.minutes = minutes;
        this.hours = hours;
        this.daysOfMonth = dom;
        this.months = months;
        this.daysOfWeek = dow;
        this.domRestricted = domRestricted;
        this.dowRestricted = dowRestricted;
    }

    /** Parses an expression; throws {@link IllegalArgumentException} saying what is not understood. */
    public static Cron parse(String expression, Format format) {
        String e = expression.strip();
        String macro = MACROS.get(e.toLowerCase(Locale.ROOT));
        if (macro != null) {
            e = format == Format.SPRING ? "0 " + macro : macro;
        }
        String[] f = e.split("\\s+");
        int expected = format == Format.SPRING ? 6 : 5;
        if (f.length != expected) {
            throw new IllegalArgumentException("'" + expression + "' has " + f.length + " fields, a " + format.name().toLowerCase(Locale.ROOT)
                    + " cron has " + expected);
        }
        int o = format == Format.SPRING ? 1 : 0;
        BitSet sec = format == Format.SPRING ? field(f[0], 0, 59, null, "second") : bits(0);
        BitSet min = field(f[o], 0, 59, null, "minute");
        BitSet hour = field(f[o + 1], 0, 23, null, "hour");
        BitSet dom = field(f[o + 2], 1, 31, null, "day of month");
        BitSet mon = field(f[o + 3], 1, 12, MONTHS, "month");
        BitSet dow = field(f[o + 4], 0, 7, DAYS, "day of week");
        if (dow.get(7)) {
            dow.set(0);
            dow.clear(7);
        }
        return new Cron(expression, format, sec, min, hour, dom, mon, dow, restricted(f[o + 2]), restricted(f[o + 4]));
    }

    private static boolean restricted(String field) {
        return !field.equals("*") && !field.equals("?");
    }

    private static BitSet bits(int... set) {
        BitSet b = new BitSet();
        for (int i : set) {
            b.set(i);
        }
        return b;
    }

    private static BitSet field(String text, int min, int max, List<String> names, String what) {
        BitSet out = new BitSet();
        for (String part : text.split(",")) {
            if (part.matches(".*[LW#].*") && !part.toUpperCase(Locale.ROOT).matches("[A-Z]{3}(-[A-Z]{3})?")) {
                throw new IllegalArgumentException("'" + part + "' in the " + what + ": L, W and # (Quartz) are not supported");
            }
            String range = part;
            int step = 1;
            int slash = part.indexOf('/');
            if (slash >= 0) {
                range = part.substring(0, slash);
                step = Integer.parseInt(part.substring(slash + 1));
                if (step <= 0) {
                    throw new IllegalArgumentException("step " + step + " in the " + what);
                }
            }
            int from;
            int to;
            if (range.equals("*") || range.equals("?")) {
                from = min;
                to = max;
            } else if (range.contains("-")) {
                String[] ft = range.split("-", 2);
                from = value(ft[0], names, min, what);
                to = value(ft[1], names, min, what);
            } else {
                from = value(range, names, min, what);
                to = slash >= 0 ? max : from;
            }
            if (from < min || to > max || from > to) {
                throw new IllegalArgumentException("'" + part + "' is outside the " + what + " (" + min + "-" + max + ")");
            }
            for (int i = from; i <= to; i += step) {
                out.set(i);
            }
        }
        return out;
    }

    private static int value(String s, List<String> names, int min, String what) {
        if (names != null) {
            int i = names.indexOf(s.toUpperCase(Locale.ROOT));
            if (i >= 0) {
                return names == MONTHS ? i + 1 : i;
            }
        }
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("'" + s + "' is not a value of the " + what);
        }
    }

    private boolean dayMatches(LocalDate d) {
        boolean dom = daysOfMonth.get(d.getDayOfMonth());
        boolean dow = daysOfWeek.get(d.getDayOfWeek().getValue() % 7);
        if (format == Format.UNIX && domRestricted && dowRestricted) {
            return dom || dow;   // Vixie cron: either restricted day field matching fires
        }
        return dom && dow;
    }

    /** The times it fires on a day, in the zone (a time skipped by a DST change does not fire, a repeated one once). */
    public List<ZonedDateTime> on(LocalDate day, ZoneId zone) {
        List<ZonedDateTime> out = new ArrayList<>();
        if (!months.get(day.getMonthValue()) || !dayMatches(day)) {
            return out;
        }
        for (int h = hours.nextSetBit(0); h >= 0; h = hours.nextSetBit(h + 1)) {
            for (int m = minutes.nextSetBit(0); m >= 0; m = minutes.nextSetBit(m + 1)) {
                for (int s = seconds.nextSetBit(0); s >= 0; s = seconds.nextSetBit(s + 1)) {
                    LocalDateTime local = day.atTime(h, m, s);
                    if (zone.getRules().getValidOffsets(local).isEmpty()) {
                        continue;   // in a DST gap: that local time does not exist
                    }
                    out.add(local.atZone(zone));
                }
            }
        }
        return out;
    }

    /** The next {@code count} times after {@code from}, looking at most two years ahead. */
    public List<ZonedDateTime> next(ZonedDateTime from, int count) {
        List<ZonedDateTime> out = new ArrayList<>();
        LocalDate day = from.toLocalDate();
        LocalDate end = day.plusYears(2);
        while (out.size() < count && !day.isAfter(end)) {
            for (ZonedDateTime t : on(day, from.getZone())) {
                if (t.isAfter(from) && out.size() < count) {
                    out.add(t);
                }
            }
            day = day.plusDays(1);
        }
        return out;
    }

    public String expression() {
        return expression;
    }

    public Format format() {
        return format;
    }
}
