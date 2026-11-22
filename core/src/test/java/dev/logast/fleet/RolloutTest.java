package dev.logast.fleet;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #236 phase 1: the local rollout over two member repos (bare repos standing in for the remotes) from a fleet repo with
 * a policy tag. apply and the build are stand-ins: what is checked is the governance around them.
 */
class RolloutTest {
    @TempDir
    Path tmp;
    Path fleet;
    int applied;

    /** apply writes a file; the build fails in a repo that has a BROKEN file. */
    final Rollout.Steps steps = new Rollout.Steps() {
        @Override
        public int apply(Path work, Path log) {
            applied++;
            try {
                Files.writeString(work.resolve("Applied.java"), "class Applied {}\n");
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            return 0;
        }

        @Override
        public int build(Path work, Path log) {
            return Files.exists(work.resolve("BROKEN")) ? 1 : 0;
        }
    };

    static void git(Path dir, String... args) {
        Git.must(dir, args);
    }

    static void identity(Path dir) {
        git(dir, "config", "user.email", "dev@example.com");
        git(dir, "config", "user.name", "Dev");
        git(dir, "config", "commit.gpgSign", "false");
        git(dir, "config", "tag.gpgSign", "false");
    }

    /** A member: a bare remote with one commit of {@code files} on main. */
    Path member(String name, Map<String, String> files) throws IOException {
        Path remote = tmp.resolve("remotes/" + name + ".git");
        Files.createDirectories(remote);
        git(remote, "init", "--quiet", "--bare", "--initial-branch=main");
        Path seed = Files.createDirectories(tmp.resolve("seed/" + name));
        git(seed, "init", "--quiet", "--initial-branch=main");
        identity(seed);
        for (var f : files.entrySet()) {
            Files.writeString(seed.resolve(f.getKey()), f.getValue());
        }
        Files.writeString(seed.resolve("README.md"), name + "\n");
        git(seed, "add", "-A");
        git(seed, "commit", "--quiet", "-m", "init");
        git(seed, "push", "--quiet", remote.toUri().toString(), "main");
        return remote;
    }

    @BeforeEach
    void fleetRepo() throws IOException {
        fleet = Files.createDirectories(tmp.resolve("fleet"));
        git(fleet, "init", "--quiet", "--initial-branch=main");
        identity(fleet);
        Files.writeString(fleet.resolve("company.yaml"), "logging:\n  keyStyle: camel\n");
        git(fleet, "add", "-A");
        git(fleet, "commit", "--quiet", "-m", "policy");
        assertThat(PolicyVersions.tag(fleet)).isEqualTo("policy-1");
    }

    void repos(Path... remotes) throws IOException {
        StringBuilder sb = new StringBuilder("repos:\n");
        for (Path r : remotes) {
            sb.append("  - url: ").append(r.toUri()).append('\n');
        }
        Files.writeString(fleet.resolve("repos.yaml"), sb.toString());
    }

    Map<String, Rollout.Row> run(Rollout.Stage stage) {
        List<Rollout.Row> rows = Rollout.run(fleet, "shop", new Rollout.Options(Set.of(), null, true, stage, false, "test"), steps);
        return rows.stream().collect(Collectors.toMap(Rollout.Row::repo, r -> r));
    }

    Path preview(String name) {
        return fleet.resolve(".cache/rollout").resolve(name);
    }

    Path clone(String name) {
        return fleet.resolve(FleetRepos.CACHE).resolve(name);
    }

    @Test
    void dueReposGetTheirBranchWithThePinAndASecondRunChangesNothing() throws IOException {
        repos(member("checkout", Map.of()), member("payments", Map.of("logast.yaml", "packs: [logging]\n")));
        Map<String, Rollout.Row> first = run(Rollout.Stage.COMMIT);
        assertThat(first.get("checkout").state()).isEqualTo(Rollout.State.COMMITTED);
        assertThat(first.get("payments").state()).isEqualTo(Rollout.State.COMMITTED);
        String yaml = Git.must(clone("payments"), "show", Rollout.BRANCH + ":logast.yaml");
        assertThat(yaml).as("joined and pinned; its own settings stay").contains("fleet: shop").contains("- fleet:shop@policy-1")
                .contains("packs: [logging]");
        assertThat(Git.must(clone("checkout"), "show", Rollout.BRANCH + ":logast.yaml")).contains("- fleet:shop@policy-1");
        assertThat(Git.must(clone("checkout"), "log", "-1", "--format=%B", Rollout.BRANCH))
                .contains(Rollout.TRAILER + ": policy-1").contains("Logast-Base: ");

        int before = applied;
        Map<String, Rollout.Row> second = run(Rollout.Stage.COMMIT);
        assertThat(second.values()).as("same policy, same base: nothing to do").allMatch(r -> r.state() == Rollout.State.UNCHANGED);
        assertThat(applied).isEqualTo(before);
    }

    @Test
    void theReposOwnLogastYamlDecides() throws IOException {
        repos(member("held", Map.of("logast.yaml", "rollout:\n  hold: \"until 2999-01-01: freeze\"\n")),
                member("out", Map.of("logast.yaml", "rollout:\n  mode: off\n")),
                member("manual", Map.of("logast.yaml", "rollout:\n  mode: manual\n")),
                member("current", Map.of("logast.yaml", "fleet: shop\nextends:\n  - fleet:shop@policy-1\n")),
                member("broken", Map.of("BROKEN", "")));
        Map<String, Rollout.Row> rows = run(Rollout.Stage.COMMIT);
        assertThat(rows.get("held").state()).isEqualTo(Rollout.State.SKIPPED);
        assertThat(rows.get("held").detail()).contains("on hold");
        assertThat(rows.get("out").state()).isEqualTo(Rollout.State.SKIPPED);
        assertThat(rows.get("manual").state()).isEqualTo(Rollout.State.SKIPPED);
        assertThat(rows.get("current").state()).isEqualTo(Rollout.State.UP_TO_DATE);
        assertThat(rows.get("broken").state()).as("the build fails: nothing committed").isEqualTo(Rollout.State.BLOCKED);
        assertThat(Git.run(clone("broken"), "rev-parse", "--verify", "--quiet", "refs/heads/" + Rollout.BRANCH).ok()).isFalse();

        List<Rollout.Row> named = Rollout.run(fleet, "shop", new Rollout.Options(Set.of("manual"), null, true, Rollout.Stage.COMMIT, false, "test"), steps);
        assertThat(named).singleElement().satisfies(r -> assertThat(r.state()).as("named: a manual repo is rolled out")
                .isEqualTo(Rollout.State.COMMITTED));
    }

    @Test
    void aHoldEndsAfterItsDate() {
        assertThat(Rollout.onHold("until 2026-12-15: freeze", LocalDate.of(2026, 12, 15))).isTrue();
        assertThat(Rollout.onHold("until 2026-12-15: freeze", LocalDate.of(2026, 12, 16))).isFalse();
        assertThat(Rollout.onHold("waiting for the platform team", LocalDate.of(2030, 1, 1))).as("no date: until removed").isTrue();
        assertThat(Rollout.onHold(null, LocalDate.of(2030, 1, 1))).isFalse();
    }

    @Test
    void aPushedBranchSomeoneTookOverIsLeftAlone() throws IOException {
        Path remote = member("checkout", Map.of());
        repos(remote);
        assertThat(run(Rollout.Stage.PUSH).get("checkout").state()).isEqualTo(Rollout.State.PUSHED);
        assertThat(Git.must(remote, "rev-parse", Rollout.BRANCH)).isNotBlank();

        // a person adds a commit on top of the rollout branch
        Path mine = tmp.resolve("mine");
        git(tmp, "clone", "--quiet", "--branch", Rollout.BRANCH, remote.toUri().toString(), mine.toString());
        identity(mine);
        Files.writeString(mine.resolve("Tweak.java"), "class Tweak {}\n");
        git(mine, "add", "-A");
        git(mine, "commit", "--quiet", "-m", "my wording");
        git(mine, "push", "--quiet", "origin", Rollout.BRANCH);
        String theirs = Git.must(remote, "rev-parse", Rollout.BRANCH);

        // a new policy version: the repo is due again, but the branch is theirs now
        Files.writeString(fleet.resolve("company.yaml"), "logging:\n  keyStyle: snake\n");
        git(fleet, "commit", "--quiet", "-am", "policy 2");
        assertThat(PolicyVersions.tag(fleet)).isEqualTo("policy-2");
        Rollout.Row row = run(Rollout.Stage.PUSH).get("checkout");
        assertThat(row.state()).isEqualTo(Rollout.State.SKIPPED);
        assertThat(row.detail()).contains("someone's");
        assertThat(Git.must(remote, "rev-parse", Rollout.BRANCH)).as("not overwritten").isEqualTo(theirs);
    }

    /** The plan changes nothing; --apply leaves a preview; --commit commits exactly it, without applying again; --push reuses the commit. */
    @Test
    void eachStageReusesTheOneBefore() throws IOException {
        Path remote = member("checkout", Map.of());
        repos(remote);
        assertThat(run(Rollout.Stage.PLAN).get("checkout").state()).isEqualTo(Rollout.State.DUE);
        assertThat(applied).as("the plan runs nothing").isZero();
        assertThat(preview("checkout")).doesNotExist();

        assertThat(run(Rollout.Stage.APPLY).get("checkout").state()).isEqualTo(Rollout.State.PREVIEW);
        assertThat(preview("checkout").resolve("Applied.java")).exists();
        assertThat(Git.run(clone("checkout"), "rev-parse", "--verify", "--quiet", "refs/heads/" + Rollout.BRANCH).ok()).as("nothing committed").isFalse();
        assertThat(run(Rollout.Stage.PLAN).get("checkout").state()).as("the plan sees the preview").isEqualTo(Rollout.State.PREVIEW);

        assertThat(run(Rollout.Stage.COMMIT).get("checkout").state()).isEqualTo(Rollout.State.COMMITTED);
        assertThat(applied).as("the preview is committed, not made again").isEqualTo(1);
        assertThat(Git.must(clone("checkout"), "show", Rollout.BRANCH + ":Applied.java")).contains("class Applied");

        assertThat(run(Rollout.Stage.PUSH).get("checkout").state()).isEqualTo(Rollout.State.PUSHED);
        assertThat(applied).as("the commit is pushed as it is").isEqualTo(1);
        assertThat(Git.must(remote, "rev-parse", Rollout.BRANCH)).isEqualTo(Git.must(clone("checkout"), "rev-parse", Rollout.BRANCH));
        assertThat(run(Rollout.Stage.PUSH).get("checkout").state()).isEqualTo(Rollout.State.UNCHANGED);
    }

    /** A preview whose files changed after apply is discarded, never committed. */
    @Test
    void aPreviewChangedAfterApplyIsNeverCommitted() throws IOException {
        repos(member("checkout", Map.of()));
        run(Rollout.Stage.APPLY);
        Files.writeString(preview("checkout").resolve("Applied.java"), "class Applied { /* changed by hand */ }\n");
        Rollout.Row row = run(Rollout.Stage.COMMIT).get("checkout");
        assertThat(row.state()).isEqualTo(Rollout.State.BLOCKED);
        assertThat(row.detail()).contains("changed after apply");
        assertThat(Git.run(clone("checkout"), "rev-parse", "--verify", "--quiet", "refs/heads/" + Rollout.BRANCH).ok()).isFalse();
        assertThat(preview("checkout")).as("discarded").doesNotExist();

        assertThat(run(Rollout.Stage.COMMIT).get("checkout").state()).as("made again from scratch, then committed")
                .isEqualTo(Rollout.State.COMMITTED);
        assertThat(Git.must(clone("checkout"), "show", Rollout.BRANCH + ":Applied.java")).doesNotContain("by hand");
    }
}
