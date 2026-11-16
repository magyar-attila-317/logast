package dev.logast.apply;

import dev.logast.Analysis;
import dev.logast.model.LogCall;
import dev.logast.model.MethodInfo;
import dev.logast.orders.WorkOrder;
import dev.logast.parse.Pos;
import dev.logast.parse.SourceFileInfo;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Keeps a short source marker beside findings that need an entry in fills.json or metrics.json. */
public final class FindingComments {
    private static final String PREFIX = "logast: ";
    private static final Pattern MARKER = Pattern.compile("([A-Z_]+) ([A-Z]+-[a-fA-F0-9]+)");

    private FindingComments() {
    }

    /** Reconciles markers for current review-file entries. Comments are kept on the statement line. */
    public static void update(Path root, Analysis analysis, List<WorkOrder> orders) {
        if (analysis == null) {
            return;
        }
        boolean enabled = analysis.policy.reporting().sourceComments();
        Map<String, Set<String>> desiredByStatement = new LinkedHashMap<>();
        Map<String, String> fileText = new LinkedHashMap<>();
        for (WorkOrder order : orders) {
            if (!enabled || !reviewFileOrder(order)) {
                continue;
            }
            LogCall call = findCall(analysis, order);
            if (call == null) {
                continue;
            }
            SourceFileInfo file = findFile(analysis, order.file());
            if (file == null || !file.editable()) {
                continue;
            }
            String text = file.text();
            if (order.rule().equals(dev.logast.rules.Rules.MESSAGE_SUGGESTED)
                    && file.lineText(order.line()) != null && file.lineText(order.line()).contains("// logast:wording")) {
                continue; // this generated text already has a source marker that the fill removes when resolved
            }
            int end = statementEnd(text, Pos.end(call.call()));
            if (end < 0) {
                continue;
            }
            String key = order.file() + "|" + end;
            desiredByStatement.computeIfAbsent(key, k -> new LinkedHashSet<>()).add(order.rule() + " " + order.id());
            fileText.put(order.file(), text);
        }
        analysis.project.files().stream().filter(file -> file.text().contains("// " + PREFIX))
                .forEach(file -> fileText.putIfAbsent(file.relPath(), file.text()));

        Set<String> activeIds = new HashSet<>();
        desiredByStatement.values().forEach(markers -> markers.forEach(marker -> activeIds.add(id(marker))));
        for (var entry : fileText.entrySet()) {
            String rel = entry.getKey();
            String original = entry.getValue();
            List<Insertion> insertions = new ArrayList<>();
            desiredByStatement.forEach((key, markers) -> {
                if (!key.startsWith(rel + "|")) {
                    return;
                }
                int end = Integer.parseInt(key.substring(rel.length() + 1));
                if (!hasAllMarkers(original, end, markers)) {
                    insertions.add(new Insertion(end, markers));
                }
            });
            insertions.sort(Comparator.comparingInt(Insertion::at).reversed());
            StringBuilder updated = new StringBuilder(original);
            for (Insertion insertion : insertions) {
                int lineEnd = updated.indexOf("\n", insertion.at());
                if (lineEnd < 0) {
                    lineEnd = updated.length();
                }
                String comment = String.join("; ", insertion.markers());
                int existingComment = updated.indexOf("//", insertion.at());
                if (existingComment >= 0 && existingComment < lineEnd) {
                    String body = updated.substring(existingComment + 2, lineEnd).stripLeading();
                    if (body.startsWith(PREFIX)) {
                        Set<String> merged = new LinkedHashSet<>(markers(body));
                        merged.addAll(insertion.markers());
                        updated.replace(existingComment + 2, lineEnd, " " + PREFIX + String.join("; ", merged));
                    } else {
                        updated.replace(existingComment + 2, lineEnd,
                                " " + body + "; " + PREFIX + comment);
                    }
                } else {
                    updated.insert(insertion.at(), " // " + PREFIX + comment);
                }
            }
            String reconciled = removeStaleMarkers(updated.toString(), activeIds);
            if (!reconciled.equals(original)) {
                try {
                    Files.writeString(root.resolve(rel), reconciled);
                } catch (IOException ex) {
                    throw new UncheckedIOException(ex);
                }
            }
        }
    }

    private static boolean reviewFileOrder(WorkOrder order) {
        return !order.slots().isEmpty() || order.rule().equals(MetricsFile.RULE)
                && !Boolean.TRUE.equals(order.data().get("auto")) && order.reportOnly();
    }

    private static String id(String marker) {
        Matcher matcher = MARKER.matcher(marker);
        return matcher.matches() ? matcher.group(2) : "";
    }

    private static boolean hasAllMarkers(String text, int statementEnd, Set<String> wanted) {
        int lineEnd = text.indexOf('\n', statementEnd);
        if (lineEnd < 0) {
            lineEnd = text.length();
        }
        int comment = text.indexOf("//", statementEnd);
        if (comment < 0 || comment >= lineEnd) {
            return false;
        }
        return markers(text.substring(comment + 2, lineEnd)).containsAll(wanted);
    }

    private static Set<String> markers(String comment) {
        Set<String> out = new LinkedHashSet<>();
        for (String part : comment.split(";\\s*")) {
            String candidate = part.strip();
            if (candidate.startsWith(PREFIX)) {
                candidate = candidate.substring(PREFIX.length()).strip();
            }
            Matcher marker = MARKER.matcher(candidate);
            if (marker.matches()) {
                out.add(candidate);
            }
        }
        return out;
    }

    private static String removeStaleMarkers(String source, Set<String> activeIds) {
        String[] lines = source.split("(?<=\\n)", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            int comment = line.indexOf("//");
            if (comment < 0) {
                continue;
            }
            String body = line.substring(comment + 2).stripLeading();
            if (!body.contains(PREFIX)) {
                continue;
            }
            String eol = line.endsWith("\r\n") ? "\r\n" : line.endsWith("\n") ? "\n" : "";
            String content = line.substring(0, line.length() - eol.length());
            String[] pieces = body.split(";\\s*");
            List<String> kept = new ArrayList<>();
            for (String piece : pieces) {
                String value = piece.strip();
                String candidate = value.startsWith(PREFIX) ? value.substring(PREFIX.length()).strip() : value;
                Matcher marker = MARKER.matcher(candidate);
                if (marker.matches()) {
                    if (activeIds.contains(marker.group(2))) {
                        kept.add(PREFIX + candidate);
                    }
                } else {
                    kept.add(value);
                }
            }
            if (kept.isEmpty()) {
                String prefix = content.substring(0, comment).stripTrailing();
                lines[i] = prefix + eol;
            } else {
                lines[i] = content.substring(0, comment + 2) + " " + String.join("; ", kept) + eol;
            }
        }
        return String.join("", lines);
    }

    private static LogCall findCall(Analysis analysis, WorkOrder order) {
        for (MethodInfo method : analysis.index.methods()) {
            if (!method.file().relPath().equals(order.file()) || order.method() != null && !order.method().equals(method.label())) {
                continue;
            }
            for (LogCall call : method.facts().logs) {
                if (Pos.line(call.call()) == order.line()) {
                    return call;
                }
            }
        }
        return null;
    }

    private static SourceFileInfo findFile(Analysis analysis, String rel) {
        return analysis.project.files().stream().filter(f -> f.relPath().equals(rel)).findFirst().orElse(null);
    }

    private static int statementEnd(String source, int callEnd) {
        if (callEnd < 0 || callEnd > source.length()) {
            return -1;
        }
        int i = callEnd;
        while (i < source.length()) {
            while (i < source.length() && Character.isWhitespace(source.charAt(i))) {
                i++;
            }
            if (source.startsWith("/*", i)) {
                int end = source.indexOf("*/", i + 2);
                if (end < 0) {
                    return -1;
                }
                i = end + 2;
                continue;
            }
            return i < source.length() && source.charAt(i) == ';' ? i + 1 : -1;
        }
        return -1;
    }

    private record Insertion(int at, Set<String> markers) {
    }
}
