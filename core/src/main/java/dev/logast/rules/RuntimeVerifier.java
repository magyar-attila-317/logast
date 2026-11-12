package dev.logast.rules;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.logast.Analysis;
import dev.logast.model.LogCall;
import dev.logast.model.MethodInfo;
import dev.logast.orders.WorkOrder;
import dev.logast.parse.Pos;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Checks what existing tests actually logged (captured by logast's test-capture module) against the static model:
 * <ul>
 *   <li>RUNTIME_TRACE_LOST / RUNTIME_MDC_LOST: within one test, context (traceId, a vocabulary MDC key) was present
 *       on another thread's events but missing here: the work moved threads without its context;</li>
 *   <li>RUNTIME_NULL_VALUE: a placeholder or vocabulary MDC key rendered as {@code null} or blank.</li>
 * </ul>
 * Unit tests that never set context produce no findings: only context that existed and then got lost counts.
 */
public final class RuntimeVerifier {
    private static final ObjectMapper JSON = new ObjectMapper();

    public record Event(String test, String thread, String pattern, String message, String callerClass, int callerLine,
                        Map<String, String> mdc) {
    }

    public record Result(List<WorkOrder> orders, int events, int logStatements, int exercised, int unmapped,
                         List<String> unmappedSample) {
    }

    private record Site(MethodInfo method, LogCall call, int from, int to) {
    }

    private RuntimeVerifier() {
    }

    public static List<Event> read(List<Path> files) {
        List<Event> out = new ArrayList<>();
        for (Path f : files) {
            try {
                for (String line : Files.readAllLines(f)) {
                    if (line.isBlank()) {
                        continue;
                    }
                    JsonNode n = JSON.readTree(line);
                    Map<String, String> mdc = new LinkedHashMap<>();
                    n.path("mdc").fields().forEachRemaining(e -> mdc.put(e.getKey(), e.getValue().isNull() ? null : e.getValue().asText()));
                    out.add(new Event(n.path("test").isNull() ? null : n.path("test").asText(null), n.path("thread").asText(),
                            n.path("pattern").asText(""), n.path("message").asText(""), n.path("callerClass").asText(null),
                            n.path("callerLine").asInt(-1), mdc));
                }
            } catch (IOException e) {
                throw new UncheckedIOException("cannot read " + f, e);
            }
        }
        return out;
    }

    public static Result verify(Analysis a, List<Event> events) {
        RuleContext ctx = new RuleContext(a);
        Map<String, List<Site>> sitesByClass = new HashMap<>();
        int statements = 0;
        for (MethodInfo m : ctx.methods()) {
            for (LogCall lc : m.facts().logs) {
                int from = Pos.line(lc.call());
                int to = from + (int) m.file().slice(Pos.start(lc.call()), Pos.end(lc.call())).chars().filter(c -> c == '\n').count();
                sitesByClass.computeIfAbsent(m.owner().fqn(), k -> new ArrayList<>()).add(new Site(m, lc, from, to));
                statements++;
            }
        }
        Set<String> registry = a.policy.mdcRegistry().keySet();
        String traceKey = a.policy.mdc().ambientKeys().isEmpty() ? "traceId" : a.policy.mdc().ambientKeys().getFirst();

        Map<String, OrderBuilder> orders = new LinkedHashMap<>();
        Map<String, Integer> counts = new HashMap<>();
        Set<Site> exercised = Collections.newSetFromMap(new IdentityHashMap<>());
        int unmapped = 0;
        List<String> unmappedSample = new ArrayList<>();
        // per test: last thread on which each context key was seen
        Map<String, Map<String, String>> lastThreadWithKey = new HashMap<>();

        for (Event e : events) {
            Site site = find(sitesByClass, e);
            Map<String, String> seen = lastThreadWithKey.computeIfAbsent(String.valueOf(e.test()), k -> new HashMap<>());
            if (site == null) {
                if (e.callerClass() != null && sitesByClass.containsKey(outer(e.callerClass()))) {
                    unmapped++;
                    if (unmappedSample.size() < 10) {
                        unmappedSample.add(e.callerClass() + ":" + e.callerLine() + " " + e.pattern());
                    }
                }
                remember(e, seen, registry, traceKey);
                continue;
            }
            exercised.add(site);
            if (e.test() != null) {
                String traceThread = seen.get(traceKey);
                if (blank(e.mdc().get(traceKey)) && traceThread != null && !traceThread.equals(e.thread())) {
                    add(ctx, orders, counts, site, Rules.RUNTIME_TRACE_LOST, traceKey,
                            "No " + traceKey + " here, although the same test had one on thread '" + traceThread
                                    + "': the work moved to thread '" + e.thread() + "' without its trace", e);
                }
                for (String key : registry) {
                    String keyThread = seen.get(key);
                    if (!e.mdc().containsKey(key) && keyThread != null && !keyThread.equals(e.thread())) {
                        add(ctx, orders, counts, site, Rules.RUNTIME_MDC_LOST, key,
                                "MDC '" + key + "' missing here, although the same test had it on thread '" + keyThread
                                        + "': context did not follow the work to '" + e.thread() + "'", e);
                    }
                }
            }
            for (String key : registry) {
                if (e.mdc().containsKey(key) && blank(e.mdc().get(key))) {
                    add(ctx, orders, counts, site, Rules.RUNTIME_NULL_VALUE, "mdc:" + key, "MDC '" + key + "' is set but null/blank", e);
                }
            }
            for (String value : renderedArgs(e.pattern(), e.message())) {
                if (value.equals("null") || value.isBlank()) {
                    add(ctx, orders, counts, site, Rules.RUNTIME_NULL_VALUE, "arg", "A logged value rendered as '" + value + "'", e);
                    break;
                }
            }
            remember(e, seen, registry, traceKey);
        }
        List<WorkOrder> built = new ArrayList<>();
        orders.forEach((k, b) -> {
            b.title(b.title + " (" + counts.get(k) + "x in tests)");
            built.add(b.build());
        });
        return new Result(built, events.size(), statements, exercised.size(), unmapped, unmappedSample);
    }

    private static void remember(Event e, Map<String, String> seen, Set<String> registry, String traceKey) {
        if (!blank(e.mdc().get(traceKey))) {
            seen.put(traceKey, e.thread());
        }
        for (String key : registry) {
            if (!blank(e.mdc().get(key))) {
                seen.put(key, e.thread());
            }
        }
    }

    private static void add(RuleContext ctx, Map<String, OrderBuilder> orders, Map<String, Integer> counts, Site site,
                            String rule, String detail, String title, Event e) {
        if (!ctx.enabled(rule)) {
            return;
        }
        int line = Pos.line(site.call().call());
        String k = rule + "|" + site.method().file().relPath() + ":" + line + "|" + detail;
        counts.merge(k, 1, Integer::sum);
        orders.computeIfAbsent(k, x -> ctx.order(rule, site.method(), line, "runtime|" + detail + "|" + line)
                .title(title)
                .suggestion("Seen in test " + shortTest(e.test()) + ": \"" + Text.oneLine(e.message(), 120) + "\"")
                .context(ctx.context(site.method(), Pos.start(site.call().call()), Text.oneLine(site.call().call().printTrimmed(), 160), null)));
    }

    private static Site find(Map<String, List<Site>> sites, Event e) {
        if (e.callerClass() == null) {
            return null;
        }
        for (Site s : sites.getOrDefault(outer(e.callerClass()), List.of())) {
            if (s.from() <= e.callerLine() && e.callerLine() <= s.to()) {
                return s;
            }
        }
        return null;
    }

    /** {@code Foo$1} / {@code Foo$Inner} → {@code Foo}; nested classes are indexed with '$' too, so try both. */
    private static String outer(String cls) {
        int i = cls.indexOf('$');
        return i < 0 ? cls : cls.substring(0, i);
    }

    /** Values substituted for {} in the rendered message. */
    static List<String> renderedArgs(String pattern, String message) {
        if (!pattern.contains("{}")) {
            return List.of();
        }
        String[] parts = pattern.split("\\{}", -1);
        StringBuilder re = new StringBuilder("^");
        for (int i = 0; i < parts.length; i++) {
            re.append(Pattern.quote(parts[i]));
            if (i < parts.length - 1) {
                re.append("(.*?)");
            }
        }
        Matcher m = Pattern.compile(re.append("$").toString(), Pattern.DOTALL).matcher(message);
        if (!m.matches()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (int i = 1; i <= m.groupCount(); i++) {
            out.add(m.group(i));
        }
        return out;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank() || s.equals("null");
    }

    private static String shortTest(String uniqueId) {
        if (uniqueId == null) {
            return "?";
        }
        Matcher m = Pattern.compile("\\[(?:class|method):([^\\]]+)]").matcher(uniqueId);
        List<String> parts = new ArrayList<>();
        while (m.find()) {
            String p = m.group(1);
            parts.add(p.contains(".") && !p.contains("(") ? p.substring(p.lastIndexOf('.') + 1) : p);
        }
        return parts.isEmpty() ? uniqueId : String.join(".", parts);
    }
}
