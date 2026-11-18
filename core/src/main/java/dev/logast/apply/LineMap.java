package dev.logast.apply;

import org.openrewrite.jgit.diff.Edit;
import org.openrewrite.jgit.diff.EditList;
import org.openrewrite.jgit.diff.HistogramDiff;
import org.openrewrite.jgit.diff.RawText;
import org.openrewrite.jgit.diff.RawTextComparator;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Follows lines through an edit of a file: where line n of the old text is in the new one. Indentation is ignored, so
 * code a fix wraps (a try block around a body) keeps its lines; a line inside a rewritten hunk goes to the same
 * place in the new hunk, and a deleted line is gone. Used to say which log calls an apply fixed (#72).
 */
final class LineMap {
    private LineMap() {
    }

    /** Moves the entries of {@code file} in {@code lines} from {@code before} to {@code after} (null: file gone). */
    static void follow(Map<String, TreeMap<Integer, Set<String>>> lines, String file, String before, String after) {
        TreeMap<Integer, Set<String>> old = lines.get(file);
        if (old == null || old.isEmpty() || before == null || before.equals(after)) {
            return;
        }
        if (after == null) {
            lines.remove(file);
            return;
        }
        EditList edits = new HistogramDiff().diff(RawTextComparator.WS_IGNORE_ALL,
                new RawText(before.getBytes(StandardCharsets.UTF_8)), new RawText(after.getBytes(StandardCharsets.UTF_8)));
        TreeMap<Integer, Set<String>> moved = new TreeMap<>();
        old.forEach((line, ids) -> {
            int to = map(edits, line);
            if (to > 0) {
                moved.computeIfAbsent(to, k -> new java.util.LinkedHashSet<>()).addAll(ids);
            }
        });
        lines.put(file, moved);
    }

    /** 1-based line {@code line} of the old text in the new one, or -1 when it was deleted. */
    static int map(EditList edits, int line) {
        int l = line - 1;
        int delta = 0;
        for (Edit e : edits) {
            if (l < e.getBeginA()) {
                break;
            }
            if (l < e.getEndA()) {
                int lengthB = e.getEndB() - e.getBeginB();
                return lengthB == 0 ? -1 : e.getBeginB() + Math.min(l - e.getBeginA(), lengthB - 1) + 1;
            }
            delta = e.getEndB() - e.getEndA();
        }
        return l + delta + 1;
    }
}
