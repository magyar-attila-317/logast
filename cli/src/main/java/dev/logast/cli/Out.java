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
        java.io.Console console = System.console();
        boolean terminal = console != null;
        try {   // Java 22+: console() exists even when redirected; isTerminal() tells
            terminal = terminal && (boolean) java.io.Console.class.getMethod("isTerminal").invoke(console);
        } catch (ReflectiveOperationException e) {
            // Java 21: a non-null console means a terminal
        }
        Ansi.enable(terminal);
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
     * A progress line for long work. On a terminal: one line, rewritten in place, with a spinner and the elapsed time
     * (cleared when done). Elsewhere (CI, pipes): each new step printed once.
     */
    static final class Progress implements AutoCloseable {
        private static final String[] FRAMES = {"⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏"};
        private final long start = System.nanoTime();
        private final boolean live = Ansi.enabled();
        private volatile String step = "";
        private final Thread ticker;

        Progress(String first) {
            update(first);
            ticker = live ? Thread.ofPlatform().daemon().start(this::tick) : null;
        }

        /** "~detail" lines only show on the live line; the others also get a line of their own when not live. */
        void update(String s) {
            boolean detail = s.startsWith("~");
            String text = detail ? s.substring(1) : s;
            if (text.equals(step)) {
                return;
            }
            step = text;
            if (!live && !detail) {
                System.err.println(Ansi.dim("… " + text));
            }
        }

        private void tick() {
            int i = 0;
            while (!Thread.currentThread().isInterrupted()) {
                System.err.print("\r\u001b[2K" + Ansi.cyan(FRAMES[i++ % FRAMES.length]) + " " + step + Ansi.dim("  " + seconds(start)));
                System.err.flush();
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }

        @Override
        public void close() {
            if (ticker != null) {
                ticker.interrupt();
                try {
                    ticker.join(500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                System.err.print("\r\u001b[2K");
                System.err.flush();
            }
        }
    }

    static String seconds(long startNanos) {
        return String.format("%.1fs", (System.nanoTime() - startNanos) / 1e9);
    }

    private static String pad(String label) {
        return String.format("%-24s", label);
    }
}
