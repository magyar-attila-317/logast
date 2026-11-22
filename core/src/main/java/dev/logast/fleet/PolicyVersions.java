package dev.logast.fleet;

import dev.logast.CentralVocabulary;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

/**
 * #236: the fleet's policy versions. Each change of the fleet repo's company.yaml is tagged {@code policy-N}; a member
 * pins one ({@code extends: [fleet:<name>@policy-N]}) and every machine and CI run applies that version: its company.yaml
 * read from git at the tag, not whatever a local clone holds. The tag is the single source; the copy under
 * {@code .cache/policy/<tag>/} is a cache (a tag's content never changes).
 */
public final class PolicyVersions {
    public static final String PREFIX = "policy-";

    private PolicyVersions() {
    }

    /** The fleet's policy tags, oldest first. Empty when the fleet folder is no git repo or has none. */
    public static List<String> tags(Path fleetDir) {
        if (!Files.isDirectory(fleetDir) || !Git.repo(fleetDir)) {
            return List.of();
        }
        Git.Result r = Git.run(fleetDir, "tag", "--list", PREFIX + "*");
        if (!r.ok() || r.out().isBlank()) {
            return List.of();
        }
        return r.out().lines().map(String::strip).filter(t -> number(t) >= 0)
                .sorted(Comparator.comparingInt(PolicyVersions::number)).toList();
    }

    /** The newest policy tag, or null. */
    public static String latest(Path fleetDir) {
        List<String> t = tags(fleetDir);
        return t.isEmpty() ? null : t.getLast();
    }

    /** {@code policy-14} → 14; -1 for anything else. */
    public static int number(String tag) {
        if (tag == null || !tag.startsWith(PREFIX)) {
            return -1;
        }
        try {
            return Integer.parseInt(tag.substring(PREFIX.length()));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** The company.yaml of {@code tag}: a cached copy of what git holds at the tag (fetched once when the tag is not here). */
    public static Path company(Path fleetDir, String tag) {
        Path cached = fleetDir.resolve(".cache/policy").resolve(tag).resolve(CentralVocabulary.COMPANY);
        if (Files.isRegularFile(cached) || number(tag) < 0 || !Files.isDirectory(fleetDir) || !Git.repo(fleetDir)) {
            return cached;
        }
        Git.Result r = Git.run(fleetDir, "show", tag + ":" + CentralVocabulary.COMPANY);
        if (!r.ok()) {
            Git.run(fleetDir, "fetch", "--tags", "--quiet");
            r = Git.run(fleetDir, "show", tag + ":" + CentralVocabulary.COMPANY);
        }
        if (r.ok()) {
            try {
                Files.createDirectories(cached.getParent());
                Files.writeString(cached, r.out() + "\n");
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return cached;
    }

    /**
     * Tags the fleet repo's HEAD {@code policy-<n+1>} when its company.yaml differs from the newest tag's (or there is
     * none yet); returns the tag HEAD's policy is, or null when the fleet folder is no git repo. Uncommitted changes of
     * company.yaml are not a version: they are refused.
     */
    public static String tag(Path fleetDir) {
        if (!Git.repo(fleetDir)) {
            return null;
        }
        if (!Git.run(fleetDir, "diff", "--quiet", "HEAD", "--", CentralVocabulary.COMPANY).ok()) {
            throw new IllegalStateException(CentralVocabulary.COMPANY + " has uncommitted changes: commit them first, a policy "
                    + "version is a commit");
        }
        String latest = latest(fleetDir);
        if (latest != null && !changedSince(fleetDir, latest)) {
            return latest;
        }
        String next = PREFIX + (latest == null ? 1 : number(latest) + 1);
        Git.must(fleetDir, "tag", "-a", next, "-m", "logast policy " + next);
        return next;
    }

    /** HEAD's company.yaml differs from the one at {@code tag}. */
    public static boolean changedSince(Path fleetDir, String tag) {
        return !Git.run(fleetDir, "diff", "--quiet", tag, "HEAD", "--", CentralVocabulary.COMPANY).ok();
    }
}
