package dev.logast.fleet;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * #236 phase 1: the local rollout. A person runs it from a clone of the fleet repo with their own credentials; for each
 * member repo it decides from the repo's own state whether it is due, and if so applies the target policy version on a
 * branch of logast's own, builds, commits with trailers saying what made the commit, and (asked to) pushes and opens a
 * PR. One writer per fact: the repo's {@code logast.yaml} says whether it takes part ({@code rollout:}) and which policy
 * version it runs (its pin); the fleet only reads them.
 * <ul>
 *   <li>The branch is regenerated from the default branch, never rebased: {@code apply} is deterministic.</li>
 *   <li>A branch whose newest commit has no {@code Logast-Rollout:} trailer is someone's: it is left alone.</li>
 *   <li>A push is {@code --force-with-lease} against the commit seen before: a branch moved meanwhile is not overwritten.</li>
 *   <li>A second run on an unchanged repo and policy changes nothing.</li>
 * </ul>
 */
public final class Rollout {
    public static final String BRANCH = "logast/rollout";
    public static final String TRAILER = "Logast-Rollout";
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private static final Pattern HOLD_UNTIL = Pattern.compile("until\\s+(\\d{4}-\\d{2}-\\d{2})");

    /** What runs in the member's work tree: {@code apply} and its build; 0 is success. */
    public interface Steps {
        int apply(Path work, Path log);

        int build(Path work, Path log);
    }

    /**
     * @param only   members to work on (empty: all); naming one also takes a {@code mode: manual} repo
     * @param policy the policy version to roll out (null: the newest tag)
     */
    public record Options(Set<String> only, String policy, boolean build, boolean push, boolean pr, String logastVersion) {
    }

    public enum State { COMMITTED, PUSHED, PR, UNCHANGED, UP_TO_DATE, SKIPPED, BLOCKED, FAILED }

    /** One member's outcome; {@code detail} says why or where. */
    public record Row(String repo, State state, String detail) {
    }

    /** The repo's own say ({@code rollout:} in its logast.yaml at the default branch) and its current pin. */
    record Intent(String mode, String hold, String pin) {
    }

    private Rollout() {
    }

    /** The target policy version: {@code wanted}, else the newest tag; fails when there is none. */
    public static String target(Path fleetDir, String wanted) {
        String t = wanted != null ? wanted : PolicyVersions.latest(fleetDir);
        if (t == null) {
            throw new IllegalStateException("the fleet repo has no policy version yet: `logast fleet . --tag-policy` tags its company.yaml");
        }
        if (!PolicyVersions.tags(fleetDir).contains(t)) {
            throw new IllegalStateException(t + " is no policy version of this fleet (" + PolicyVersions.tags(fleetDir) + ")");
        }
        return t;
    }

    /** Every repo of repos.yaml (or those {@code only} names), synced, then rolled out one after another. */
    public static List<Row> run(Path fleetDir, String fleet, Options o, Steps steps) {
        String target = target(fleetDir, o.policy());
        List<Row> out = new ArrayList<>();
        for (FleetRepos.Repo repo : FleetRepos.list(fleetDir)) {
            if (!o.only().isEmpty() && !o.only().contains(repo.name())) {
                continue;
            }
            Path clone;
            try {
                clone = FleetRepos.sync(fleetDir, repo);
            } catch (RuntimeException e) {
                out.add(new Row(repo.name(), State.FAILED, "fetch: " + e.getMessage()));
                continue;
            }
            out.add(member(fleetDir, fleet, repo.name(), clone, target, o, steps));
        }
        return out;
    }

    /** One member repo (its clone at the newest commit of its default branch). */
    static Row member(Path fleetDir, String fleet, String name, Path clone, String target, Options o, Steps steps) {
        String base = Git.must(clone, "rev-parse", "HEAD");
        Intent intent = intent(clone, fleet);
        if ("off".equals(intent.mode())) {
            return new Row(name, State.SKIPPED, "opted out (rollout.mode: off" + (intent.hold() == null ? "" : ", " + intent.hold()) + ")");
        }
        if (onHold(intent.hold(), LocalDate.now())) {
            return new Row(name, State.SKIPPED, "on hold: " + intent.hold());
        }
        if ("manual".equals(intent.mode()) && !o.only().contains(name)) {
            return new Row(name, State.SKIPPED, "rollout.mode: manual (name it with --repos to roll it out)");
        }
        if (intent.pin() != null && PolicyVersions.number(intent.pin()) >= PolicyVersions.number(target)) {
            return new Row(name, State.UP_TO_DATE, "runs " + intent.pin());
        }
        // the branch: ours (newest commit says logast made it) or someone's; the same policy on the same base: nothing to do
        String remote = o.push() ? remoteBranch(clone) : null;
        if (o.push() && remote != null) {
            Git.run(clone, "fetch", "--quiet", "--depth", "1", "origin", "+refs/heads/" + BRANCH + ":refs/remotes/origin/" + BRANCH);
        }
        String ref = remote != null ? "refs/remotes/origin/" + BRANCH : localBranch(clone) ? "refs/heads/" + BRANCH : null;
        if (ref != null) {
            String message = Git.run(clone, "log", "-1", "--format=%B", ref).out();
            if (!message.contains(TRAILER + ":")) {
                return new Row(name, State.SKIPPED, BRANCH + " has someone's commits on top: it is theirs now (`logast apply .` there updates it)");
            }
            if (message.contains(TRAILER + ": " + target) && message.contains("Logast-Base: " + base)) {
                return new Row(name, State.UNCHANGED, BRANCH + " already applies " + target + " to " + base.substring(0, 8));
            }
        }
        Path logs = fleetDir.resolve(".cache/rollout");
        Path work = logs.resolve(name);
        try {
            Files.createDirectories(logs);
            Git.run(clone, "worktree", "remove", "--force", work.toString());
            Git.run(clone, "worktree", "prune");
            Git.must(clone, "worktree", "add", "--quiet", "--force", "--detach", work.toString(), base);
            pin(work.resolve("logast.yaml"), fleet, target);
            Path log = logs.resolve(name + ".log");
            Files.deleteIfExists(log);
            if (steps.apply(work, log) != 0) {
                return new Row(name, State.BLOCKED, "apply failed: " + log);
            }
            if (o.build() && steps.build(work, log) != 0) {
                return new Row(name, State.BLOCKED, "the build failed after apply (nothing committed): " + log);
            }
            Git.must(work, "add", "-A");
            Git.must(work, "commit", "--quiet", "--no-verify", "-m", message(fleet, target, base, o.logastVersion()));
            Git.must(clone, "branch", "--force", BRANCH, Git.must(work, "rev-parse", "HEAD"));
            if (!o.push()) {
                return new Row(name, State.COMMITTED, BRANCH + " in " + clone);
            }
            Git.Result push = Git.run(work, "push", "--quiet", "--force-with-lease=refs/heads/" + BRANCH + ":" + (remote == null ? "" : remote),
                    "origin", "HEAD:refs/heads/" + BRANCH);
            if (!push.ok()) {
                return new Row(name, State.SKIPPED, "push refused (the branch moved since it was read: someone's now): " + push.out());
            }
            if (!o.pr()) {
                return new Row(name, State.PUSHED, BRANCH);
            }
            String pr = PullRequests.openOrUpdate(work, BRANCH, title(target), body(fleet, target, base));
            return new Row(name, State.PR, pr);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (RuntimeException e) {
            return new Row(name, State.FAILED, e.getMessage());
        } finally {
            Git.run(clone, "worktree", "remove", "--force", work.toString());
        }
    }

    /** {@code fleet: <name>} (joining when the file has none) and {@code extends: [fleet:<name>@<tag>]}. */
    static void pin(Path file, String fleet, String tag) throws IOException {
        List<String> lines = Files.exists(file) ? new ArrayList<>(Files.readAllLines(file)) : new ArrayList<>();
        if (lines.stream().noneMatch(l -> l.matches("fleet\\s*:.*"))) {
            lines.addFirst("fleet: " + fleet);
        }
        Files.writeString(file, String.join("\n", FleetExtends.pinTo(lines, fleet, tag)) + "\n");
    }

    /** The repo's rollout intent and pin, from its logast.yaml as committed (none: takes part, unpinned). */
    static Intent intent(Path clone, String fleet) {
        Path file = clone.resolve("logast.yaml");
        if (!Files.isRegularFile(file)) {
            return new Intent(null, null, null);
        }
        try {
            JsonNode n = YAML.readTree(Files.readString(file));
            if (n == null || !n.isObject()) {
                return new Intent(null, null, null);
            }
            JsonNode r = n.path("rollout");
            String pin = null;
            JsonNode ext = n.path("extends");
            List<String> entries = new ArrayList<>();
            if (ext.isTextual()) {
                entries.add(ext.asText());
            } else {
                ext.forEach(e -> entries.add(e.asText()));
            }
            for (String e : entries) {
                if (fleet.equals(FleetExtends.name(e)) && FleetExtends.pin(e) != null) {
                    pin = FleetExtends.pin(e);
                }
            }
            // YAML 1.1 reads an unquoted `mode: off` as false
            String mode = !r.hasNonNull("mode") ? null : r.get("mode").isBoolean() && !r.get("mode").asBoolean() ? "off"
                    : r.get("mode").asText().strip();
            return new Intent(mode,
                    r.hasNonNull("hold") ? r.get("hold").asText().strip() : null, pin);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + file + ": " + e.getMessage(), e);
        }
    }

    /** A hold applies until the date it names ({@code until 2026-12-15: freeze}); one without a date until it is removed. */
    static boolean onHold(String hold, LocalDate today) {
        if (hold == null || hold.isBlank()) {
            return false;
        }
        Matcher m = HOLD_UNTIL.matcher(hold);
        return !m.find() || !today.isAfter(LocalDate.parse(m.group(1)));
    }

    private static String remoteBranch(Path clone) {
        Git.Result r = Git.run(clone, "ls-remote", "--heads", "origin", BRANCH);
        if (!r.ok() || r.out().isBlank()) {
            return null;
        }
        return r.out().lines().findFirst().map(l -> l.split("\\s+")[0]).orElse(null);
    }

    private static boolean localBranch(Path clone) {
        return Git.run(clone, "rev-parse", "--verify", "--quiet", "refs/heads/" + BRANCH).ok();
    }

    static String title(String target) {
        return "logast: apply fleet policy " + target;
    }

    static String message(String fleet, String target, String base, String version) {
        return title(target) + "\n\n"
                + "Generated by `logast fleet rollout`: what logast applies with policy " + target + " of the fleet\n"
                + fleet + ", and that pin in logast.yaml. Review it as any change; to take the branch\n"
                + "over, push a commit of your own: the rollout leaves it alone from then on.\n\n"
                + TRAILER + ": " + target + "\n"
                + "Logast-Base: " + base + "\n"
                + "Logast-Version: " + (version == null ? "unknown" : version) + "\n";
    }

    static String body(String fleet, String target, String base) {
        return String.join("\n", Arrays.asList(
                "The fleet **" + fleet + "** rolls out its logging policy **" + target + "** (a tag of the fleet repo).",
                "",
                "- what changed: `git diff` per kind in `.logast/reports/steps/` after `logast apply .`",
                "- built from `" + base.substring(0, 8) + "` of the default branch; regenerated when the policy or the branch moves",
                "- push a commit of your own to take the branch over: the rollout leaves it alone from then on",
                "- opt out or pause in `logast.yaml`: `rollout: { mode: off }` / `rollout: { hold: \"until 2026-12-15: freeze\" }`"));
    }
}
