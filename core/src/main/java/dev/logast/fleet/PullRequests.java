package dev.logast.fleet;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** #236: the rollout's pull request through the GitHub CLI ({@code gh}), with the person's own login. */
final class PullRequests {
    private PullRequests() {
    }

    /** The PR of {@code branch}: opened when there is none, its title and body updated when there is one; its URL. */
    static String openOrUpdate(Path work, String branch, String title, String body) {
        String existing = gh(work, "pr", "list", "--head", branch, "--state", "open", "--json", "url", "--jq", ".[0].url");
        if (!existing.isBlank()) {
            gh(work, "pr", "edit", existing, "--title", title, "--body", body);
            return existing;
        }
        return gh(work, "pr", "create", "--head", branch, "--title", title, "--body", body);
    }

    private static String gh(Path in, String... args) {
        List<String> cmd = new ArrayList<>(List.of("gh"));
        cmd.addAll(List.of(args));
        try {
            Process p = new ProcessBuilder(cmd).directory(in.toFile()).redirectErrorStream(true).start();
            p.getOutputStream().close();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
            if (!p.waitFor(5, TimeUnit.MINUTES) || p.exitValue() != 0) {
                throw new IllegalStateException("gh " + args[0] + " " + args[1] + ": " + out);
            }
            return out;
        } catch (IOException e) {
            throw new UncheckedIOException("cannot run gh (the GitHub CLI) for --pr", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted");
        }
    }
}
