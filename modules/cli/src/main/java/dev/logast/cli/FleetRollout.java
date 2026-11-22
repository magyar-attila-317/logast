package dev.logast.cli;

import dev.logast.fleet.FleetHome;
import dev.logast.fleet.FleetRepos;
import dev.logast.fleet.NamedFleet;
import dev.logast.fleet.PolicyVersions;
import dev.logast.fleet.Rollout;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

/** #236: policy versions and the local rollout of a fleet in its own git repo. */
final class FleetRollout {
    private FleetRollout() {
    }

    /** A fleet by name, or by its folder ({@code .} in the fleet repo); null (said) when there is none. */
    static Path fleetDir(Fleet.FleetsDir fleetsDir, String nameOrPath) {
        Path asPath = Path.of(nameOrPath).toAbsolutePath().normalize();
        if (NamedFleet.exists(asPath)) {
            return asPath;
        }
        if (FleetHome.validName(nameOrPath)) {
            FleetHome.Location at = FleetHome.fleetDir(fleetsDir.env(), nameOrPath);
            if (NamedFleet.exists(at.dir())) {
                return at.dir();
            }
        }
        Out.warn("fleet: no fleet " + nameOrPath + " (a name `logast fleet list` shows, or the fleet repo's folder)");
        return null;
    }

    static String fleetName(Path dir) {
        String n = FleetRepos.fleetName(dir);
        return n != null ? n : NamedFleet.serviceName(dir);
    }

    @Command(name = "tag-policy", description = "Tag the fleet repo's company.yaml as the next policy version (policy-N) when it "
            + "changed since the newest one; prints the version HEAD's policy is (#236). Push the tag: members pin it")
    static final class TagPolicy implements Callable<Integer> {
        @Parameters(index = "0", defaultValue = ".", description = "The fleet: its name or its repo's folder (default: .)")
        String fleet;
        @Mixin
        Fleet.FleetsDir fleetsDir;

        @Override
        public Integer call() {
            Path dir = fleetDir(fleetsDir, fleet);
            if (dir == null) {
                return 2;
            }
            String before = PolicyVersions.latest(dir);
            String tag;
            try {
                tag = PolicyVersions.tag(dir);
            } catch (IllegalStateException e) {
                Out.warn("fleet tag-policy: " + e.getMessage());
                return 2;
            }
            if (tag == null) {
                Out.warn("fleet tag-policy: " + dir + " is no git repo: policy versions are tags of the fleet repo (`logast fleet init`)");
                return 2;
            }
            System.out.println(tag.equals(before) ? tag + " (company.yaml unchanged since)" : tag + " (new: `git push origin " + tag + "`)");
            return 0;
        }
    }

    @Command(name = "rollout", description = "Roll the fleet's policy out to the repos of repos.yaml, from this machine with your "
            + "own git and gh logins (#236). Without a stage: the plan (per repo due or not, by its own logast.yaml), nothing changes. "
            + "--apply: pin, `logast apply`, build, a preview per repo to review; --commit: commits exactly that preview on "
            + "logast/rollout; --push / --pr: publish it. A later stage reuses the earlier one's result for the same base, policy "
            + "and logast")
    static final class Run implements Callable<Integer> {
        @Parameters(index = "0", defaultValue = ".", description = "The fleet: its name or its repo's folder (default: .)")
        String fleet;
        @Option(names = "--repos", split = ",", description = "Only these repos (names in repos.yaml); naming one also rolls out a rollout.mode: manual repo")
        List<String> repos = new ArrayList<>();
        @Option(names = "--policy", description = "The policy version to roll out (default: the newest policy-N tag)")
        String policy;
        @Option(names = "--no-build", description = "Commit without running the repo's build after apply")
        boolean noBuild;
        @Option(names = "--apply", description = "Make the preview: a work tree per repo with the pin and what apply (and the build) "
                + "produced, in <fleet>/.cache/rollout/<repo>; nothing is committed")
        boolean apply;
        @Option(names = "--commit", description = "Commit the preview on logast/rollout (made first when there is none); a preview "
                + "whose files changed after apply is discarded, never committed")
        boolean commit;
        @Option(names = "--push", description = "Push logast/rollout (with a lease: a branch someone moved is not overwritten); implies --commit")
        boolean push;
        @Option(names = "--pr", description = "Open or update the pull request (gh); implies --push")
        boolean pr;
        @Option(names = "--fresh", description = "Apply again even when a preview of the same base, policy and logast is there")
        boolean fresh;
        @Mixin
        Fleet.FleetsDir fleetsDir;

        Rollout.Stage stage() {
            return pr ? Rollout.Stage.PR : push ? Rollout.Stage.PUSH : commit ? Rollout.Stage.COMMIT : apply ? Rollout.Stage.APPLY
                    : Rollout.Stage.PLAN;
        }

        @Override
        public Integer call() {
            Path dir = fleetDir(fleetsDir, fleet);
            if (dir == null) {
                return 2;
            }
            String name = fleetName(dir);
            String latest = PolicyVersions.latest(dir);
            if (policy == null && latest != null && PolicyVersions.changedSince(dir, latest)) {
                Out.warn("fleet rollout: company.yaml changed since " + latest + ": rolling out " + latest
                        + " (`logast fleet tag-policy` makes the change a version)");
            }
            List<Rollout.Row> rows;
            try {
                rows = Rollout.run(dir, name, new Rollout.Options(new LinkedHashSet<>(repos), policy, !noBuild, stage(), fresh,
                        dev.logast.orders.Baseline.currentVersion()), new Subprocess());
            } catch (IllegalStateException e) {
                Out.warn("fleet rollout: " + e.getMessage());
                return 2;
            }
            if (rows.isEmpty()) {
                Out.warn("fleet rollout: no repo to roll out (" + FleetRepos.FILE + " lists none" + (repos.isEmpty() ? "" : " of " + repos) + ")");
                return 2;
            }
            int width = rows.stream().mapToInt(r -> r.repo().length()).max().orElse(4);
            for (Rollout.Row r : rows) {
                System.out.printf("%-" + width + "s  %-10s  %s%n", r.repo(), r.state().name().toLowerCase().replace('_', ' '), r.detail());
            }
            boolean bad = rows.stream().anyMatch(r -> r.state() == Rollout.State.BLOCKED || r.state() == Rollout.State.FAILED);
            return bad ? 1 : 0;
        }
    }

    /** {@code logast apply} as its own process (this JVM's logast), and the repo's Gradle build; output into the log. */
    static final class Subprocess implements Rollout.Steps {
        @Override
        public int apply(Path work, Path log) {
            String java = ProcessHandle.current().info().command().orElse("java");
            return run(work, log, List.of(java, "-cp", System.getProperty("java.class.path"), Main.class.getName(), "apply", work.toString()));
        }

        @Override
        public int build(Path work, Path log) {
            Path gradlew = work.resolve(File.separatorChar == '\\' ? "gradlew.bat" : "gradlew");
            if (!Files.isRegularFile(gradlew)) {
                append(log, "no Gradle wrapper: not built\n");
                return 0;
            }
            return run(work, log, List.of(gradlew.toString(), "build", "--quiet"));
        }

        private static int run(Path work, Path log, List<String> cmd) {
            try {
                Process p = new ProcessBuilder(cmd).directory(work.toFile()).redirectErrorStream(true)
                        .redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile())).start();
                p.getOutputStream().close();
                if (!p.waitFor(60, TimeUnit.MINUTES)) {
                    p.destroyForcibly();
                    append(log, "\ntook longer than 60 minutes\n");
                    return 124;
                }
                return p.exitValue();
            } catch (IOException e) {
                append(log, "\n" + e + "\n");
                return 127;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return 130;
            }
        }

        private static void append(Path log, String text) {
            try {
                Files.writeString(log, text, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
            } catch (IOException e) {
                // the log is a convenience
            }
        }
    }
}
