package dev.logast.rules;

import dev.logast.SpringProperties;
import dev.logast.model.MdcOp;
import dev.logast.model.MethodInfo;
import dev.logast.orders.Edit;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MDC_KEY_NOT_IN_LOG_OUTPUT (#296): a JSON encoder in logback-spring.xml / logback.xml that lists the MDC keys it
 * writes ({@code <includeMdcKeyName>}, logstash-logback-encoder) drops every other key. An id logast moves out of a
 * message into MDC would then vanish from the JSON logs: the keys the code puts into MDC are added to each such list.
 * A key the file excludes on purpose ({@code <excludeMdcKeyName>}) is reported, not added.
 */
final class LogOutputRules {
    private static final Pattern INCLUDE = Pattern.compile("(?m)^([ \\t]*)<includeMdcKeyName>\\s*([^<\\s]+)\\s*</includeMdcKeyName>");
    private static final Pattern EXCLUDE = Pattern.compile("<excludeMdcKeyName>\\s*([^<\\s]+)\\s*</excludeMdcKeyName>");

    private static final Set<String> STANDARD_FIELDS = Set.of("timestamp", "@timestamp", "@version", "message", "level",
            "level_value", "logger", "logger_name", "thread", "thread_name", "stack_trace");

    private final RuleContext ctx;

    LogOutputRules(RuleContext ctx) {
        this.ctx = ctx;
    }

    void run() {
        if (!ctx.enabled(Rules.MDC_KEY_NOT_IN_LOG_OUTPUT)) {
            return;
        }
        Path resources = SpringProperties.appResources(ctx.a.project.root());
        if (resources == null) {
            return;
        }
        Set<String> keys = new TreeSet<>();
        for (MethodInfo m : ctx.methods()) {
            for (MdcOp op : m.facts().mdc) {
                if ((op.kind() == MdcOp.Kind.PUT || op.kind() == MdcOp.Kind.PUT_CLOSEABLE) && op.key() != null && !op.key().isBlank()) {
                    keys.add(op.key());
                }
            }
        }
        // fields the encoder writes itself: a key of that name would sit next to "@timestamp", "level", … (#296)
        keys.removeAll(STANDARD_FIELDS);
        if (keys.isEmpty()) {
            return;
        }
        for (String name : List.of("logback-spring.xml", "logback.xml")) {
            Path xml = resources.resolve(name);
            if (Files.isRegularFile(xml)) {
                file(xml, keys);
            }
        }
    }

    private void file(Path xml, Set<String> keys) {
        String text;
        try {
            text = dev.logast.parse.SourceText.read(xml);
        } catch (IOException e) {
            return;
        }
        Set<String> excluded = new TreeSet<>();
        Matcher ex = EXCLUDE.matcher(text);
        while (ex.find()) {
            excluded.add(ex.group(1));
        }
        // each run of consecutive <includeMdcKeyName> lines is one encoder's list
        List<int[]> runs = new ArrayList<>();      // {end offset of the run, start of its first line}
        List<String> indents = new ArrayList<>();
        List<Set<String>> listed = new ArrayList<>();
        Matcher in = INCLUDE.matcher(text);
        int lastEnd = -1;
        while (in.find()) {
            if (lastEnd >= 0 && text.substring(lastEnd, in.start()).isBlank()) {
                runs.getLast()[0] = in.end();
                listed.getLast().add(in.group(2));
            } else {
                runs.add(new int[]{in.end(), in.start()});
                indents.add(in.group(1));
                listed.add(new TreeSet<>(Set.of(in.group(2))));
            }
            lastEnd = in.end();
        }
        if (runs.isEmpty()) {
            return;   // no allowlist: every MDC key reaches the output
        }
        String rel = ctx.a.project.root().relativize(xml).toString().replace('\\', '/');
        Set<String> missingAll = new TreeSet<>();
        List<Edit> edits = new ArrayList<>();
        for (int i = 0; i < runs.size(); i++) {
            Set<String> missing = new TreeSet<>(keys);
            missing.removeAll(listed.get(i));
            missing.removeAll(excluded);
            if (missing.isEmpty()) {
                continue;
            }
            missingAll.addAll(missing);
            StringBuilder add = new StringBuilder();
            for (String k : missing) {
                add.append('\n').append(indents.get(i)).append("<includeMdcKeyName>").append(k).append("</includeMdcKeyName>");
            }
            edits.add(Edit.insert(rel, runs.get(i)[0], add.toString()));
        }
        Set<String> dropped = new TreeSet<>(keys);
        dropped.retainAll(excluded);
        if (missingAll.isEmpty() && dropped.isEmpty()) {
            return;
        }
        int line = (int) text.substring(0, runs.getFirst()[1]).chars().filter(c -> c == '\n').count() + 1;
        // the keys in the id: when other fixes of the same round put more keys into MDC, the next round's order is a new
        // one, not this one "back" without having seen its own fix (#296)
        OrderBuilder b = ctx.order(Rules.MDC_KEY_NOT_IN_LOG_OUTPUT, "mdc-output|" + rel + "|" + missingAll + "|" + dropped).at(rel, line);
        if (!missingAll.isEmpty()) {
            b.title("MDC keys the code writes never reach the JSON logs: " + rel + " lists only " + listed.getFirst()
                            + " (includeMdcKeyName); missing " + missingAll)
                    .suggestion("Adds " + missingAll + " to each includeMdcKeyName list, so the ids logast moves out of messages "
                            + "stay in the logs." + (dropped.isEmpty() ? "" : " Not added, excluded on purpose: " + dropped + "."));
            b.edits(edits);
        } else {
            b.title("MDC keys the code writes are excluded from the JSON logs (excludeMdcKeyName): " + dropped)
                    .suggestion("Their values appear in no log line. Remove them from excludeMdcKeyName, or keep them out of MDC.");
        }
    }
}
