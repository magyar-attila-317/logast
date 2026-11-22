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
import java.util.Optional;
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

    /** How far a run goes; each stage includes the ones before it when there is nothing to reuse. */
    public enum Stage { PLAN, APPLY, COMMIT, PUSH, PR }

    /**
     * @param only   members to work on (empty: all); naming one also takes a {@code mode: manual} repo
     * @param policy the policy version to roll out (null: the newest tag)
     * @param fresh  apply again even when a preview of the same base, policy and logast is there
     */
    public record Options(Set<String> only, String policy, boolean build, Stage stage, boolean fresh, String logastVersion) {
    }

    public enum State { DUE, PREVIEW, COMMITTED, PUSHED, PR, UNCHANGED, UP_TO_DATE, SKIPPED, BLOCKED, FAILED }

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

    /** One repo of a run: its clone, what is rolled out onto which base, and where its preview, stamp and log are. */
    record Member(String fleet, String name, Path repo, String base, String target, Path work, Path stamp, Path log) {
        static Member of(Path fleetDir, String fleet, String name, Path clone, String target) {
            Path dir = fleetDir.resolve(".cache/rollout");
            return new Member(fleet, name, clone, Git.must(clone, "rev-parse", "HEAD"), target, dir.resolve(name),
                    dir.resolve(name + ".stamp"), dir.resolve(name + ".log"));
        }

        /** What a preview's result depends on: the same key, the same result (apply is deterministic). */
        String key(String logastVersion) {
            return base + " " + target + " " + logastVersion;
        }

        String shortBase() {
            return base.substring(0, 8);
        }
    }

    /** A reason not to roll a repo out now, decided by its own logast.yaml. */
    private interface Guard {
        Optional<Row> check(Member m, Intent intent, Options o);
    }

    private static final List<Guard> GUARDS = List.of(
            (m, i, o) -> "off".equals(i.mode()) ? skip(m, "opted out (rollout.mode: off" + (i.hold() == null ? "" : ", " + i.hold()) + ")")
                    : Optional.empty(),
            (m, i, o) -> onHold(i.hold(), LocalDate.now()) ? skip(m, "on hold: " + i.hold()) : Optional.empty(),
            (m, i, o) -> "manual".equals(i.mode()) && !o.only().contains(m.name())
                    ? skip(m, "rollout.mode: manual (name it with --repos to roll it out)") : Optional.empty(),
            (m, i, o) -> i.pin() != null && PolicyVersions.number(i.pin()) >= PolicyVersions.number(m.target())
                    ? Optional.of(new Row(m.name(), State.UP_TO_DATE, "runs " + i.pin())) : Optional.empty());

    private static Optional<Row> skip(Member m, String why) {
        return Optional.of(new Row(m.name(), State.SKIPPED, why));
    }

    /**
     * Where logast/rollout stands.
     *
     * @param remote  its commit on the remote; null when it is not pushed
     * @param theirs  its newest commit lacks the trailer: someone's, left alone
     * @param current the commit (remote's, else the local branch's) that already holds the target on the base; null when none
     */
    record Branch(String remote, boolean theirs, String current) {
        boolean pushed() {
            return current != null && current.equals(remote);
        }
    }

    /** The outcome of making (or reusing) a preview. */
    sealed interface Preview {
        record Ready(String tree) implements Preview {
        }

        record Failed(Path log) implements Preview {
        }

        /** Its files changed after apply: discarded, never committed. */
        record Changed() implements Preview {
        }
    }

    /** One member repo (its clone at the newest commit of its default branch), up to the stage asked for. */
    static Row member(Path fleetDir, String fleet, String name, Path clone, String target, Options o, Steps steps) {
        Member m = Member.of(fleetDir, fleet, name, clone, target);
        Intent intent = intent(clone, fleet);
        Optional<Row> skipped = GUARDS.stream().map(g -> g.check(m, intent, o)).flatMap(Optional::stream).findFirst();
        if (skipped.isPresent()) {
            return skipped.get();
        }
        Branch branch = branch(m);
        if (branch.theirs()) {
            return skip(m, BRANCH + " has someone's commits on top: it is theirs now (`logast apply .` there updates it)").orElseThrow();
        }
        if (o.stage() == Stage.PLAN) {
            return plan(m, branch, o);
        }
        try {
            return stages(m, branch, o, steps);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (RuntimeException e) {
            return new Row(name, State.FAILED, e.getMessage());
        }
    }

    /** The remote branch decides whose it is when there is one; a branch holding this target on this base is reused. */
    static Branch branch(Member m) {
        String remote = remoteBranch(m.repo());
        if (remote != null) {
            Git.run(m.repo(), "fetch", "--quiet", "--depth", "1", "origin", "+refs/heads/" + BRANCH + ":refs/remotes/origin/" + BRANCH);
        }
        String local = localBranch(m.repo()) ? Git.must(m.repo(), "rev-parse", "refs/heads/" + BRANCH) : null;
        String top = remote != null ? remote : local;
        if (top != null && !Git.run(m.repo(), "log", "-1", "--format=%B", top).out().contains(TRAILER + ":")) {
            return new Branch(remote, true, null);
        }
        String current = top != null && holds(m, top) ? top : local != null && holds(m, local) ? local : null;
        return new Branch(remote, false, current);
    }

    /** The commit's trailers say it applies the member's target to its base. */
    private static boolean holds(Member m, String commit) {
        String message = Git.run(m.repo(), "log", "-1", "--format=%B", commit).out();
        return message.contains(TRAILER + ": " + m.target()) && message.contains("Logast-Base: " + m.base());
    }

    /** What a run would do, changing nothing. */
    private static Row plan(Member m, Branch b, Options o) {
        if (b.current() != null) {
            return new Row(m.name(), b.pushed() ? State.UNCHANGED : State.COMMITTED,
                    BRANCH + " holds " + m.target() + " on " + m.shortBase() + (b.pushed() ? " (pushed)" : " (not pushed)"));
        }
        String stamped = readStamp(m.stamp());
        return stamped != null && stamped.startsWith(m.key(o.logastVersion()) + " ")
                ? new Row(m.name(), State.PREVIEW, "preview of " + m.target() + " ready: " + m.work() + " (--commit commits it)")
                : new Row(m.name(), State.DUE, "would apply " + m.target() + " to " + m.shortBase() + " (--apply to see it)");
    }

    /** preview → commit → push → PR, each reusing what is there, stopping at the stage asked for. */
    private static Row stages(Member m, Branch b, Options o, Steps steps) throws IOException {
        String commit = b.current();
        boolean committing = o.stage().compareTo(Stage.COMMIT) >= 0;
        if (commit != null && committing) {
            if (o.stage() == Stage.COMMIT) {
                return new Row(m.name(), State.UNCHANGED, BRANCH + " already applies " + m.target() + " to " + m.shortBase());
            }
        } else {
            switch (preview(m, o, steps)) {
                case Preview.Failed f -> {
                    return new Row(m.name(), State.BLOCKED, "failed: " + f.log());
                }
                case Preview.Changed c -> {
                    return new Row(m.name(), State.BLOCKED, "the preview changed after apply: discarded (logast commits only "
                            + "what apply produced); --apply makes it again");
                }
                case Preview.Ready r -> {
                    if (!committing) {
                        return new Row(m.name(), State.PREVIEW, "review it: git -C " + m.work() + " diff --cached --stat (log: " + m.log() + ")");
                    }
                    commit = commitTree(m.repo(), r.tree(), m.base(), message(m.fleet(), m.target(), m.base(), o.logastVersion()));
                    Git.must(m.repo(), "branch", "--force", BRANCH, commit);
                    discard(m);
                    if (o.stage() == Stage.COMMIT) {
                        return new Row(m.name(), State.COMMITTED, BRANCH + " in " + m.repo());
                    }
                }
            }
        }
        return publish(m, b, commit, o);
    }

    /** Pushes {@code commit} with a lease against what the remote had (unless it is there already), then the PR. */
    private static Row publish(Member m, Branch b, String commit, Options o) {
        if (!commit.equals(b.remote())) {
            Git.Result push = Git.run(m.repo(), "push", "--quiet", "--force-with-lease=refs/heads/" + BRANCH + ":"
                    + (b.remote() == null ? "" : b.remote()), "origin", commit + ":refs/heads/" + BRANCH);
            if (!push.ok()) {
                return skip(m, "push refused (the branch moved since it was read: someone's now): " + push.out()).orElseThrow();
            }
            if (o.stage() == Stage.PUSH) {
                return new Row(m.name(), State.PUSHED, BRANCH);
            }
        } else if (o.stage() == Stage.PUSH) {
            return new Row(m.name(), State.UNCHANGED, BRANCH + " is pushed");
        }
        return new Row(m.name(), State.PR, PullRequests.openOrUpdate(m.repo(), BRANCH, title(m.target()), body(m.fleet(), m.target(), m.base())));
    }

    /**
     * The applied tree for the member's key (base, policy, logast): the preview's, when one of the same key is there and
     * its files are still exactly what apply produced; else made again (pin, apply, build) and stamped. A preview whose
     * files changed after apply is discarded, never committed.
     */
    private static Preview preview(Member m, Options o, Steps steps) throws IOException {
        String key = m.key(o.logastVersion());
        String stamped = readStamp(m.stamp());
        if (!o.fresh() && stamped != null && stamped.startsWith(key + " ") && Files.isDirectory(m.work())) {
            String tree = stamped.substring(key.length() + 1).strip();
            Git.must(m.work(), "add", "-A");
            if (tree.equals(Git.must(m.work(), "write-tree"))) {
                return new Preview.Ready(tree);
            }
            discard(m);
            return new Preview.Changed();
        }
        discard(m);
        Files.createDirectories(m.work().getParent());
        Git.must(m.repo(), "worktree", "add", "--quiet", "--force", "--detach", m.work().toString(), m.base());
        pin(m.work().resolve("logast.yaml"), m.fleet(), m.target());
        Files.writeString(m.log(), "");
        if (steps.apply(m.work(), m.log()) != 0) {
            return new Preview.Failed(m.log());
        }
        if (o.build() && steps.build(m.work(), m.log()) != 0) {
            Files.writeString(m.log(), "\nthe build failed after apply: nothing committed\n", java.nio.file.StandardOpenOption.APPEND);
            return new Preview.Failed(m.log());
        }
        Git.must(m.work(), "add", "-A");
        String tree = Git.must(m.work(), "write-tree");
        Files.writeString(m.stamp(), key + " " + tree + "\n");
        return new Preview.Ready(tree);
    }

    /** A commit of exactly {@code tree} on {@code base}: the person's identity, else logast's (a machine without one). */
    private static String commitTree(Path clone, String tree, String base, String message) {
        boolean identity = !Git.run(clone, "config", "user.email").out().isBlank();
        List<String> args = new ArrayList<>(identity ? List.of() : List.of("-c", "user.name=logast", "-c",
                "user.email=logast@users.noreply.github.com"));
        args.addAll(List.of("commit-tree", tree, "-p", base, "-m", message));
        return Git.must(clone, args.toArray(String[]::new));
    }

    private static void discard(Member m) throws IOException {
        Git.run(m.repo(), "worktree", "remove", "--force", m.work().toString());
        Git.run(m.repo(), "worktree", "prune");
        Files.deleteIfExists(m.stamp());
    }

    private static String readStamp(Path stampFile) {
        try {
            return Files.isRegularFile(stampFile) ? Files.readString(stampFile).strip() : null;
        } catch (IOException e) {
            return null;
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
