package dev.logast.orders;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Lines added or changed since a git ref, per file relative to the scanned root. */
public final class GitDiff {
    private static final Pattern FILE = Pattern.compile("^\\+\\+\\+ b/(.+)$");
    private static final Pattern HUNK = Pattern.compile("^@@ -\\d+(?:,\\d+)? \\+(\\d+)(?:,(\\d+))? @@");

    private final Map<String, BitSet> changed;

    private GitDiff(Map<String, BitSet> changed) {
        this.changed = changed;
    }

    public static GitDiff since(Path root, String ref) {
        String top = run(root, "git", "rev-parse", "--show-toplevel").strip();
        String diff = run(root, "git", "diff", "--unified=0", "--no-color", ref, "--", ".");
        String prefix = Path.of(top).relativize(root.toAbsolutePath().normalize()).toString().replace('\\', '/');
        return parse(diff, prefix.isEmpty() ? "" : prefix + "/");
    }

    /** @param prefix path of the scanned root inside the repository, with trailing slash ("" when equal) */
    static GitDiff parse(String unifiedDiff, String prefix) {
        Map<String, BitSet> changed = new HashMap<>();
        BitSet current = null;
        for (String line : unifiedDiff.split("\n")) {
            Matcher f = FILE.matcher(line);
            if (f.find()) {
                String path = f.group(1);
                current = path.startsWith(prefix) ? changed.computeIfAbsent(path.substring(prefix.length()), k -> new BitSet()) : null;
                continue;
            }
            Matcher h = HUNK.matcher(line);
            if (h.find() && current != null) {
                int start = Integer.parseInt(h.group(1));
                int count = h.group(2) == null ? 1 : Integer.parseInt(h.group(2));
                current.set(start, start + Math.max(count, 1));
            }
        }
        return new GitDiff(changed);
    }

    public boolean touches(String file, int line) {
        BitSet b = changed.get(file);
        return b != null && (line <= 1 ? !b.isEmpty() : b.get(line));
    }

    /** Orders anchored on, or with a location on, a changed line. */
    public List<WorkOrder> filter(List<WorkOrder> orders) {
        return orders.stream().filter(o -> touches(o.file(), o.line())
                || o.locations().stream().anyMatch(l -> touches(l.file(), l.line()))).toList();
    }

    private static String run(Path dir, String... cmd) {
        try {
            Process p = new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (p.waitFor() != 0) {
                throw new IllegalStateException(String.join(" ", cmd) + " failed: " + out.strip());
            }
            return out;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
