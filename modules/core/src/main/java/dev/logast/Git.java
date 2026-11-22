package dev.logast;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** git in a folder: the exit code and the output (stderr merged). The one place logast runs git. */
public final class Git {
    /** @param code git's exit code; {@code out} its output, stripped */
    public record Result(int code, String out) {
        public boolean ok() {
            return code == 0;
        }
    }

    private Git() {
    }

    public static Result run(Path in, String... args) {
        List<String> cmd = new ArrayList<>(List.of("git"));
        cmd.addAll(List.of(args));
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd).directory(in.toFile()).redirectErrorStream(true);
            pb.environment().put("GIT_TERMINAL_PROMPT", "0");   // never wait for a password
            Process p = pb.start();
            p.getOutputStream().close();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
            if (!p.waitFor(10, TimeUnit.MINUTES)) {
                p.destroyForcibly();
                return new Result(124, "git " + args[0] + " took longer than 10 minutes");
            }
            return new Result(p.exitValue(), out);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot run git in " + in, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Result(130, "interrupted");
        }
    }

    /** {@link #run}, failing with git's output when it fails. */
    public static String must(Path in, String... args) {
        Result r = run(in, args);
        if (!r.ok()) {
            throw new IllegalStateException("git " + args[0] + " failed: " + r.out());
        }
        return r.out();
    }

    /** git's output, or null when git fails or is not there at all (a question that may have no answer). */
    public static String output(Path in, String... args) {
        try {
            Result r = run(in, args);
            return r.ok() ? r.out() : null;
        } catch (UncheckedIOException e) {
            return null;
        }
    }

    /** The folder is inside a git work tree. */
    public static boolean repo(Path dir) {
        return "true".equals(output(dir, "rev-parse", "--is-inside-work-tree"));
    }
}
