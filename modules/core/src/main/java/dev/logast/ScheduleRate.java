package dev.logast;

import dev.logast.facts.Cron;
import dev.logast.graph.CodeIndex;
import dev.logast.graph.Constants;
import dev.logast.model.Annotations;
import dev.logast.model.ClassInfo;
import dev.logast.model.Entrypoint;
import org.openrewrite.java.tree.Expression;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.Statement;

import java.time.Duration;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * #235: how often a scheduled entrypoint runs, from its schedule in the code: {@code @Scheduled(cron)} counted over a
 * year, {@code fixedRate} / {@code fixedDelay} (and their {@code String} forms, ISO durations too) as one run per
 * interval. Constants and {@code ${…}} from the service's base configuration are resolved; a schedule that cannot be
 * read gives none. A fixed delay starts after the previous run ends: one run per interval is an upper bound.
 */
public final class ScheduleRate {
    private static final String SCHEDULED = "org.springframework.scheduling.annotation.Scheduled";
    /** A year without a leap day, so a yearly schedule counts once. */
    private static final int YEAR = 2027;

    /** Runs per day and the schedule it was computed from. */
    public record Rate(double perDay, String from) {
    }

    private ScheduleRate() {
    }

    /** label → rate, for every scheduled entrypoint whose schedule can be read. */
    public static Map<String, Rate> of(Iterable<Entrypoint> entrypoints, CodeIndex index, Map<String, String> config) {
        Map<String, Rate> out = new LinkedHashMap<>();
        for (Entrypoint ep : entrypoints) {
            if (ep.kind() != Entrypoint.Kind.SCHEDULED) {
                continue;
            }
            for (J.Annotation a : ep.method().decl().getLeadingAnnotations()) {
                if (Annotations.is(a, SCHEDULED)) {
                    Rate r = scheduled(a, index, config);
                    if (r != null) {
                        out.put(ep.label(), r);
                    }
                    break;   // several @Scheduled on one method: not summed, left to traffic.yaml
                }
            }
        }
        return out;
    }

    static Rate scheduled(J.Annotation a, CodeIndex index, Map<String, String> config) {
        Optional<Expression> cron = Annotations.attribute(a, "cron");
        if (cron.isPresent()) {
            String c = resolve(Constants.concatenated(index, cron.get()), config);
            Double perDay = c == null ? null : cronPerDay(c, Cron.Format.SPRING);
            return perDay == null ? null : new Rate(perDay, "@Scheduled(cron = \"" + c + "\")");
        }
        TimeUnit unit = Annotations.rawAttribute(a, "timeUnit").map(v -> v.substring(v.lastIndexOf('.') + 1))
                .map(v -> {
                    try {
                        return TimeUnit.valueOf(v);
                    } catch (IllegalArgumentException e) {
                        return null;
                    }
                }).orElse(TimeUnit.MILLISECONDS);
        if (unit == null) {
            return null;
        }
        for (String name : new String[]{"fixedRate", "fixedDelay"}) {
            Long millis = Annotations.attribute(a, name).map(e -> number(index, e, 0)).map(unit::toMillis).orElse(null);
            if (millis == null) {
                String text = Annotations.attribute(a, name + "String").map(e -> resolve(Constants.concatenated(index, e), config)).orElse(null);
                millis = text == null ? null : duration(text.strip(), unit);
            }
            if (millis != null && millis > 0) {
                return new Rate(86_400_000d / millis, "@Scheduled(" + name + " = " + millis + " ms)");
            }
        }
        return null;
    }

    /** Runs per day of a cron, over a year; null when it does not parse or never runs ("-"). */
    public static Double cronPerDay(String expression, Cron.Format format) {
        if (expression.strip().equals("-")) {
            return null;
        }
        Cron c;
        try {
            c = Cron.parse(expression, format);
        } catch (IllegalArgumentException e) {
            return null;
        }
        long n = 0;
        LocalDate day = LocalDate.of(YEAR, 1, 1);
        while (day.getYear() == YEAR) {
            n += c.countOn(day);
            day = day.plusDays(1);
        }
        return n / 365d;
    }

    /** {@code ${key:default}} from the configuration, else its default; null when one has neither. */
    static String resolve(String text, Map<String, String> config) {
        return dev.logast.Placeholders.strict(text, config::get);
    }

    /** A number of {@code unit}s, or an ISO-8601 duration ({@code PT5M}), as Spring reads the String attributes. */
    private static Long duration(String text, TimeUnit unit) {
        try {
            if (text.startsWith("P") || text.startsWith("p")) {
                return Duration.parse(text).toMillis();
            }
            return unit.toMillis(Long.parseLong(text));
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** A long from literals, {@code + - * /}, parentheses and numeric constants of the sources. */
    private static Long number(CodeIndex index, Expression e, int depth) {
        if (depth > 8) {
            return null;
        }
        if (e instanceof J.Literal l) {
            return l.getValue() instanceof Number n ? n.longValue() : null;
        }
        if (e instanceof J.Parentheses<?> p && p.getTree() instanceof Expression inner) {
            return number(index, inner, depth + 1);
        }
        if (e instanceof J.Binary b) {
            Long l = number(index, b.getLeft(), depth + 1);
            Long r = l == null ? null : number(index, b.getRight(), depth + 1);
            if (r == null) {
                return null;
            }
            return switch (b.getOperator()) {
                case Addition -> l + r;
                case Subtraction -> l - r;
                case Multiplication -> l * r;
                case Division -> r == 0 ? null : l / r;
                default -> null;
            };
        }
        JavaType.Variable field = e instanceof J.Identifier id ? id.getFieldType()
                : e instanceof J.FieldAccess fa ? fa.getName().getFieldType() : null;
        if (field == null || !(field.getOwner() instanceof JavaType.FullyQualified owner)) {
            return null;
        }
        ClassInfo c = index.clazz(owner.getFullyQualifiedName());
        if (c == null) {
            return null;
        }
        for (Statement s : c.decl().getBody().getStatements()) {
            if (s instanceof J.VariableDeclarations vd) {
                for (J.VariableDeclarations.NamedVariable v : vd.getVariables()) {
                    if (v.getSimpleName().equals(field.getName()) && v.getInitializer() != null && v.getInitializer() != e) {
                        return number(index, v.getInitializer(), depth + 1);
                    }
                }
            }
        }
        return null;
    }
}
