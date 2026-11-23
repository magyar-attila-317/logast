package dev.logast;

import java.io.OutputStream;
import java.io.PrintStream;
import java.util.concurrent.Callable;

/**
 * #245: runs a library call whose debug prints go to stdout (hcl4j prints "Evaluating Tuple: …" for every {@code for}
 * expression) with this thread's stdout dropped. Other threads keep printing: the stream installed once on
 * {@code System.out} drops only what a thread inside {@link #call} writes.
 */
public final class QuietStdout extends PrintStream {
    private static final ThreadLocal<Boolean> MUTED = ThreadLocal.withInitial(() -> false);

    private QuietStdout(PrintStream to) {
        super(new OutputStream() {
            @Override
            public void write(int b) {
                if (!MUTED.get()) {
                    to.write(b);
                }
            }

            @Override
            public void write(byte[] b, int off, int len) {
                if (!MUTED.get()) {
                    to.write(b, off, len);
                }
            }

            @Override
            public void flush() {
                to.flush();
            }
        }, true);
    }

    public static <T> T call(Callable<T> work) throws Exception {
        install();
        boolean before = MUTED.get();
        MUTED.set(true);
        try {
            return work.call();
        } finally {
            MUTED.set(before);
        }
    }

    private static synchronized void install() {
        if (!(System.out instanceof QuietStdout)) {
            System.setOut(new QuietStdout(System.out));
        }
    }
}
