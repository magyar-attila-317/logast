package dev.logast.cli;

import dev.logast.orders.Ansi;

import java.util.List;

/** Consistent human output for the CLI: titles, ✓ steps with a short "why", warnings and next steps. */
final class Out {
    private Out() {
    }

    /** Colour for an interactive terminal; NO_COLOR and LOGAST_COLOR=always|never are honoured. */
    static void detectColor() {
        String force = System.getenv("LOGAST_COLOR");
        if ("always".equalsIgnoreCase(force) || "never".equalsIgnoreCase(force)) {
            Ansi.enable("always".equalsIgnoreCase(force));
            return;
        }
        if (System.getenv("NO_COLOR") != null || "dumb".equals(System.getenv("TERM"))) {
            return;
        }
        Ansi.enable(terminal());
    }

    /** Output goes to an interactive terminal (not a pipe, a file or a tool that captures it). */
    static boolean terminal() {
        java.io.Console console = System.console();
        boolean terminal = console != null;
        try {   // Java 22+: console() exists even when redirected; isTerminal() tells
            terminal = terminal && (boolean) java.io.Console.class.getMethod("isTerminal").invoke(console);
        } catch (ReflectiveOperationException e) {
            // Java 21: a non-null console means a terminal
        }
        return terminal;
    }

    /** The terminal's width (COLUMNS, else 120). */
    static int width() {
        try {
            return Math.max(60, Integer.parseInt(System.getenv().getOrDefault("COLUMNS", "120").strip()));
        } catch (NumberFormatException e) {
            return 120;
        }
    }

    static void title(String s) {
        System.out.println(Ansi.bold(s));
    }

    static void step(String label, String detail) {
        System.out.println("  " + Ansi.ok() + " " + pad(label) + Ansi.dim(detail));
    }

    static void kept(String label, String detail) {
        System.out.println("  " + Ansi.dim("=") + " " + pad(label) + Ansi.dim(detail));
    }

    static void skipped(String label, String detail) {
        System.out.println("  " + Ansi.dim("·") + " " + Ansi.dim(pad(label) + detail));
    }

    static void failed(String label, String detail) {
        System.out.println("  " + Ansi.red(Ansi.enabled() ? "✗" : "x") + " " + pad(label) + Ansi.red(detail));
    }

    static void warn(String s) {
        System.err.println(Ansi.warn(s));
    }

    static void progress(String s) {
        System.err.println(Ansi.dim(s));
    }

    /** {command, what it does} pairs as a numbered list. */
    static void next(List<String[]> steps) {
        System.out.println();
        title("Next steps");
        int w = steps.stream().mapToInt(s -> s[0].length()).max().orElse(10) + 3;
        for (int i = 0; i < steps.size(); i++) {
            String[] s = steps.get(i);
            System.out.println("  " + (i + 1) + ". " + Ansi.cyan(String.format("%-" + w + "s", s[0])) + Ansi.dim(s[1]));
        }
    }

    /**
     * #207: stderr that keeps what a thread prints while it {@link #capture captures}: a fleet's parallel exports print
     * into their own buffers (shown condensed under their repo's line), the rest goes on to the terminal.
     */
    static final class Routing extends java.io.PrintStream {
        private static final ThreadLocal<java.io.ByteArrayOutputStream> BUFFER = new ThreadLocal<>();
        private final java.io.PrintStream to;

        Routing(java.io.PrintStream to) {
            super(to, true);
            this.to = to;
        }

        @Override
        public void write(int b) {
            var buf = BUFFER.get();
            if (buf != null) {
                buf.write(b);
            } else {
                to.write(b);
            }
        }

        @Override
        public void write(byte[] b, int off, int len) {
            var buf = BUFFER.get();
            if (buf != null) {
                buf.write(b, off, len);
            } else {
                to.write(b, off, len);
                to.flush();
            }
        }

        /** Runs {@code work}; what this thread prints to stderr meanwhile goes into {@code into} (its lines, without escapes). */
        static <T> T capture(List<String> into, java.util.concurrent.Callable<T> work) throws Exception {
            var buf = new java.io.ByteArrayOutputStream();
            var before = BUFFER.get();
            BUFFER.set(buf);
            try {
                return work.call();
            } finally {
                BUFFER.set(before);
                buf.toString(java.nio.charset.StandardCharsets.UTF_8).replaceAll("\u001b\\[[0-9;]*[A-Za-z]|\r", "").lines()
                        .map(String::stripTrailing).filter(l -> !l.isBlank()).forEach(into::add);
            }
        }
    }

    /**
     * Progress for long work. On a terminal, each step stays visible above a live spinner with elapsed time. Elsewhere
     * (CI, pipes), each new step is printed once.
     */
    static final class Progress implements AutoCloseable {
        private static final String[] FRAMES = {"⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏"};
        /** #193: while a fleet run shows its own lines, other progress only shows on the live line. */
        static volatile boolean quiet;
        private static volatile Progress active;
        private final long start = System.nanoTime();
        private final boolean live = Ansi.enabled();
        private final java.io.PrintStream err = System.err;
        private volatile String step = "";
        private volatile String label = "";   // a fixed first part of the live line (a fleet member's [3/32] name)
        private final Thread ticker;
        private final Progress outer;   // #193: progress inside another one is a detail of the outer line
        private final String outerStep;

        Progress(String first) {
            Progress o = active;
            outer = o != null && o.ticker != null && o.ticker.isAlive() ? o : null;
            outerStep = outer == null ? null : outer.step;
            if (outer != null) {
                ticker = null;
                update(first);
                return;
            }
            update(first);
            ticker = live ? Thread.ofPlatform().daemon().start(this::tick) : null;
            if (ticker != null) {
                active = this;
                System.setErr(new ClearingStream(err));   // #196: other output first clears the live line
            }
        }

        /** #196: stderr while a live line shows: a line someone else prints starts by clearing it (the next tick redraws it). */
        static final class ClearingStream extends java.io.PrintStream {
            private final java.io.PrintStream to;
            private boolean lineStart = true;

            ClearingStream(java.io.PrintStream to) {
                super(to, true);
                this.to = to;
            }

            @Override
            public void write(int b) {
                synchronized (to) {
                    if (lineStart) {
                        to.print("\r\u001b[2K");
                    }
                    to.write(b);
                    lineStart = b == '\n';
                }
            }

            @Override
            public void write(byte[] buf, int off, int len) {
                if (len <= 0) {
                    return;
                }
                synchronized (to) {
                    if (lineStart) {
                        to.print("\r\u001b[2K");
                    }
                    to.write(buf, off, len);
                    lineStart = buf[off + len - 1] == '\n';
                    to.flush();
                }
            }
        }

        /** The live line's fixed first part. */
        Progress label(String l) {
            label = l;
            return this;
        }

        /** "~detail" lines only show on the live line; the others also get a line of their own when not live. */
        void update(String s) {
            if (outer != null) {
                outer.update("~" + (s.startsWith("~") ? s.substring(1) : s));
                return;
            }
            boolean detail = s.startsWith("~") || quiet && live;   // the spinner shows it; logs (CI) keep their lines
            String text = s.startsWith("~") ? s.substring(1) : s;
            if (text.equals(step)) {
                return;
            }
            step = text;
            if (!detail) {
                synchronized (err) {
                    if (live) {
                        err.print("\r\u001b[2K");
                    }
                    err.println(Ansi.dim("… " + text));
                    err.flush();
                }
            }
        }

        private void tick() {
            int i = 0;
            while (!Thread.currentThread().isInterrupted()) {
                synchronized (err) {
                    String line = (label.isEmpty() ? "" : label + "  ") + step;
                    int max = width() - 12;
                    if (line.length() > max && max > 20) {
                        line = line.substring(0, max - 1) + "…";
                    }
                    err.print("\r\u001b[2K" + Ansi.cyan(FRAMES[i++ % FRAMES.length]) + " " + line + Ansi.dim("  " + seconds(start)));
                    err.flush();
                }
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }

        @Override
        public void close() {
            if (outer != null) {
                outer.update("~" + outerStep);
                return;
            }
            if (active == this) {
                active = null;
                if (System.err instanceof ClearingStream) {
                    System.setErr(err);
                }
            }
            if (ticker != null) {
                ticker.interrupt();
                try {
                    ticker.join(500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                synchronized (err) {
                    err.print("\r\u001b[2K");
                    err.flush();
                }
            }
        }
    }

    static String seconds(long startNanos) {
        return String.format("%.1fs", (System.nanoTime() - startNanos) / 1e9);
    }

    /** #193: a duration as people read it: 0.8s, 46s, 2m 05s, 1h 02m. */
    static String duration(long nanos) {
        double s = nanos / 1e9;
        if (s < 10) {
            return String.format("%.1fs", s);
        }
        long t = Math.round(s);
        if (t < 60) {
            return t + "s";
        }
        if (t < 3600) {
            return String.format("%dm %02ds", t / 60, t % 60);
        }
        return String.format("%dh %02dm", t / 3600, (t % 3600) / 60);
    }

    private static String pad(String label) {
        return label.length() < 24 ? String.format("%-24s", label) : label + " ";   // a long label still keeps its space
    }
}
