package dev.logast.cli;

import dev.logast.orders.Ansi;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * #193: what a fleet run shows while it works: what it is going to do and why, a numbered header per phase, one line
 * per repo ({@code [3/32] ✓ name  46s  what happened}) with the time left, a live line on a terminal, and a summary.
 * Lines go to stderr (stdout keeps the overview).
 */
final class FleetLog {
    enum Status {
        OK("✓", "ok"), SAME("=", "unchanged"), PROBLEM("!", "with problems"), FAILED("✗", "failed"), SKIPPED("·", "skipped");

        final String icon;
        final String word;

        Status(String icon, String word) {
            this.icon = icon;
            this.word = word;
        }

        String colored() {
            String i = Ansi.enabled() ? icon : switch (this) {
                case OK -> "+";
                case FAILED -> "x";
                default -> icon.equals("·") ? "-" : icon;
            };
            return switch (this) {
                case OK -> Ansi.green(i);
                case SAME, SKIPPED -> Ansi.dim(i);
                case PROBLEM -> Ansi.yellow(i);
                case FAILED -> Ansi.red(i);
            };
        }
    }

    private final long start = System.nanoTime();
    private final java.io.PrintStream err = System.err;
    private final List<Phase> phases = new ArrayList<>();
    private int nameWidth = 20;

    /** The run's plan: a title, then {phase, what it does} lines. */
    void intro(String title, List<String[]> plan, int nameWidth) {
        this.nameWidth = Math.min(40, Math.max(12, nameWidth));
        err.println();
        err.println(Ansi.bold(title));
        for (int i = 0; i < plan.size(); i++) {
            err.println("  " + Ansi.cyan((i + 1) + " " + String.format("%-15s", plan.get(i)[0])) + Ansi.dim(plan.get(i)[1]));
        }
        Out.Progress.quiet = true;
    }

    Phase phase(String title, String explain, int count) {
        Phase p = new Phase(phases.size() + 1, title, count);
        phases.add(p);
        String head = "━━ " + p.number + " " + title + (count > 0 ? " · " + count + " repo" + (count == 1 ? "" : "s") : "") + " ";
        err.println();
        err.println(Ansi.bold(head + "━".repeat(Math.max(3, Math.min(Out.width(), 100) - head.length()))));
        if (explain != null && !explain.isBlank()) {
            err.println("   " + Ansi.dim(explain));
        }
        return p;
    }

    /** The closing summary: per phase what happened and how long it took, the slowest repos, what to do next. */
    void summary(List<String[]> next) {
        Out.Progress.quiet = false;
        err.println();
        String head = "━━ done in " + Out.duration(System.nanoTime() - start) + " ";
        err.println(Ansi.bold(head + "━".repeat(Math.max(3, Math.min(Out.width(), 100) - head.length()))));
        for (Phase p : phases) {
            if (p.results.isEmpty()) {
                continue;
            }
            StringBuilder counts = new StringBuilder();
            for (Status s : Status.values()) {
                long n = p.results.stream().filter(r -> r.status == s).count();
                if (n > 0) {
                    counts.append(counts.isEmpty() ? "" : ", ").append(n).append(' ').append(s.word);
                }
            }
            err.println("  " + String.format("%-15s", p.title) + String.format("%-42s", counts) + Ansi.dim(Out.duration(p.elapsed())));
            List<Result> slow = p.results.stream().filter(r -> r.nanos > 5_000_000_000L).sorted(Comparator.comparingLong((Result r) -> -r.nanos))
                    .limit(3).toList();
            if (slow.size() > 1) {
                err.println("  " + " ".repeat(15) + Ansi.dim("slowest: " + String.join(", ",
                        slow.stream().map(r -> r.name + " " + Out.duration(r.nanos)).toList())));
            }
        }
        if (!next.isEmpty()) {
            err.println();
            for (String[] n : next) {
                err.println("  " + Ansi.cyan(n[0]) + "  " + Ansi.dim(n[1]));
            }
        }
        err.println();
    }

    /** #206: the time-left column's width ("~12m 30s left"). */
    private static final int LEFT = 14;

    private static String paint(Status status, String text) {
        return status == Status.FAILED ? Ansi.red(text) : status == Status.PROBLEM ? Ansi.yellow(text) : text;
    }

    /** {@code text} in lines of at most {@code width}, broken at spaces (a longer word on its own line). */
    static List<String> wrap(String text, int width) {
        List<String> out = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String w : text.strip().split(" +")) {
            if (!line.isEmpty() && line.length() + 1 + w.length() > width) {
                out.add(line.toString());
                line.setLength(0);
            }
            line.append(line.isEmpty() ? "" : " ").append(w);
        }
        out.add(line.toString());
        return out;
    }

    /** {@code text} in exactly {@code width} characters: padded, or shortened with an ellipsis. */
    static String fit(String text, int width) {
        String t = text == null ? "" : text;
        return t.length() <= width ? String.format("%-" + width + "s", t) : t.substring(0, Math.max(0, width - 1)) + "…";
    }

    /** A message on one line: git's "fatal:" line when there is one, else the first line, without the exception's class. */
    static String oneLine(String message) {
        if (message == null) {
            return "";
        }
        String m = message.replaceAll("(?m)^(java\\.[\\w.]+(Exception|Error): )+", "").strip();
        List<String> lines = m.lines().map(String::strip).filter(l -> !l.isEmpty()).toList();
        String fatal = lines.stream().filter(l -> l.contains("fatal:")).findFirst().orElse(null);
        String one = fatal != null ? fatal : lines.isEmpty() ? "" : lines.getFirst();
        return one.length() > 240 ? one.substring(0, 239) + "…" : one;
    }

    private record Result(String name, Status status, long nanos) {
    }

    /** One phase: its repos' lines, numbered as they finish, with the time left. */
    final class Phase {
        final int number;
        final String title;
        final int count;
        private final long started = System.nanoTime();
        private long ended;
        private final List<Result> results = new ArrayList<>();

        Phase(int number, String title, int count) {
            this.number = number;
            this.title = title;
            this.count = count;
        }

        long elapsed() {
            return (ended == 0 ? System.nanoTime() : ended) - started;
        }

        /** The live line while a repo is worked on (a terminal only; quiet elsewhere). */
        Out.Progress working(String name, String what) {
            return new Out.Progress("~" + what).label(Ansi.dim(counter(results.size() + 1)) + " " + name);
        }

        /**
         * A repo's line: its status, time and what happened, then notes (problems) below it. #206: every column has a fixed
         * width (a long name is shortened, the time left has its own column), and the line fits the terminal.
         */
        synchronized void done(String name, Status status, long nanos, String detail, List<String> notes) {
            results.add(new Result(name, status, nanos));
            int k = results.size();
            String left = "";
            if (count > 2 && k >= 2 && k < count) {
                long rest = elapsed() / k * (count - k);
                if (rest >= 10_000_000_000L) {   // worth saying from 10s on
                    left = "~" + Out.duration(rest) + " left";
                }
            }
            String head = "  " + counter(k) + " " + status.icon + " " + fit(name, nameWidth) + " " + String.format("%7s", Out.duration(nanos))
                    + (count > 2 ? "  " + String.format("%-" + LEFT + "s", left) : "") + "  ";
            List<String> d = wrap(detail == null ? "" : detail, Math.max(30, Math.min(Out.width(), 160) - head.length() - 1));
            synchronized (err) {
                err.println("  " + Ansi.dim(counter(k)) + " " + status.colored() + " " + fit(name, nameWidth)
                        + " " + Ansi.dim(String.format("%7s", Out.duration(nanos))) + (count > 2 ? "  " + Ansi.dim(String.format("%-" + LEFT + "s", left)) : "")
                        + "  " + paint(status, d.getFirst()));
                for (String more : d.subList(1, d.size())) {   // a long detail goes on under its own column
                    err.println(" ".repeat(head.length()) + paint(status, more));
                }
                for (String n : notes) {   // #206: a note on one line, under the repo (a parser's message spans several)
                    err.println("  " + " ".repeat(counter(k).length() + 3) + Ansi.dim("└ " + oneLine(n.replace('\n', ' ').replaceAll(" {2,}", " "))));
                }
            }
        }

        private String counter(int k) {
            int w = String.valueOf(Math.max(count, k)).length();
            return "[" + String.format("%" + w + "d", k) + "/" + (count > 0 ? count : "?") + "]";
        }

        void end() {
            ended = System.nanoTime();
        }
    }
}
