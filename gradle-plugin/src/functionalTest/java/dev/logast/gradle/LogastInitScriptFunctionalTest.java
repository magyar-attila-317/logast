package dev.logast.gradle;

import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #122: the init script install.sh puts in ~/.gradle/init.d/ makes {@code ./gradlew logastApply} work in a service whose
 * build does not mention logast. The script is the shipped one, unchanged; Maven Local is a repository under build/ that
 * the build published the plugin, cli and core to ({@code -Dmaven.repo.local}, never the real ~/.m2). The plugin's
 * other dependencies come from Maven Central, as on a developer's machine.
 */
class LogastInitScriptFunctionalTest {
    static final Path SCRIPT = Path.of(System.getProperty("logast.initScript"));
    static final Path REPO = Path.of(System.getProperty("logast.testRepo"));

    @TempDir
    Path project;
    @TempDir
    Path emptyRepo;

    /** A service that uses logast (it has logast.yaml) but whose build does not apply the plugin. */
    @BeforeEach
    void sampleBuildWithoutThePlugin() throws IOException {
        List<String> jars = Files.readAllLines(Path.of(System.getProperty("logast.fixture.classpath"))).stream()
                .map(String::strip).filter(s -> !s.isEmpty()).toList();
        Files.writeString(project.resolve("settings.gradle"), "rootProject.name = 'sample'\n");
        Files.writeString(project.resolve("build.gradle"), """
                plugins {
                    id 'java'
                }
                apply from: 'deps.gradle'
                """);
        Files.writeString(project.resolve("deps.gradle"), "dependencies {\n    implementation files(\n"
                + jars.stream().map(j -> "        '" + j.replace("\\", "/") + "'").collect(Collectors.joining(",\n"))
                + "\n    )\n}\n");
        write("src/main/java/demo/OrderController.java", LogastPluginFunctionalTest.ORDER_CONTROLLER);
        write("logast.yaml", LogastPluginFunctionalTest.APPLY_POLICY);
    }

    @Test
    void logastApplyWorksInABuildThatDoesNotMentionLogast() throws IOException {
        BuildResult r = gradle(REPO, "logastApply").build();

        assertThat(r.task(":logastApply").getOutcome()).isEqualTo(TaskOutcome.SUCCESS);
        assertThat(Files.readString(project.resolve("src/main/java/demo/OrderController.java")))
                .contains("MdcScope.of(\"orderId\", orderId)");
        assertThat(gradle(REPO, "compileJava").build().task(":compileJava").getOutcome()).isEqualTo(TaskOutcome.SUCCESS);
    }

    /** logast.yaml opts the repository in: its tasks are there without naming one. */
    @Test
    void aRepositoryWithLogastYamlHasTheTasks() {
        assertThat(gradle(REPO, "tasks").build().getOutput()).contains("logastApply").contains("logastCheck");
    }

    /** No logast.yaml and no logast task: nothing is configured (and nothing resolved). */
    @Test
    void aRepositoryWithoutLogastYamlGetsNothing() throws IOException {
        Files.delete(project.resolve("logast.yaml"));
        BuildResult r = gradle(REPO, "tasks").build();
        assertThat(r.getOutput()).doesNotContain("logastApply").doesNotContain("logast: plugin");
    }

    /** The first ./gradlew logastApply on a fresh repository: the requested task brings the plugin. */
    @Test
    void aLogastTaskBringsThePluginToAFreshRepository() throws IOException {
        Files.delete(project.resolve("logast.yaml"));
        BuildResult r = gradle(REPO, "logastApply", "--preview").build();
        assertThat(r.task(":logastApply").getOutcome()).isEqualTo(TaskOutcome.SUCCESS);
        assertThat(r.getOutput()).contains("DRY RUN");
    }

    /** The plugin is not in Maven Local: the build goes on without it, saying so once; it never fails for that. */
    @Test
    void withoutThePluginInMavenLocalTheBuildGoesOn() {
        String missing = "logast: plugin " + System.getProperty("logast.version") + " not in Maven Local";

        BuildResult help = gradle(emptyRepo, "help").build();
        assertThat(help.getOutput()).contains(missing).contains("BUILD SUCCESSFUL");
        BuildResult compile = gradle(emptyRepo, "compileJava").build();
        assertThat(compile.task(":compileJava").getOutcome()).isEqualTo(TaskOutcome.SUCCESS);
        assertThat(compile.getOutput()).contains(missing);
    }

    @Test
    void theOptOutRemovesTheTasks() {
        assertThat(gradle(REPO, "logastApply", "-Plogast.disabled").buildAndFail().getOutput())
                .contains("'logastApply' not found");

        Map<String, String> env = new HashMap<>(System.getenv());
        env.put("LOGAST_DISABLED", "1");
        assertThat(gradle(REPO, "logastApply").withEnvironment(env).buildAndFail().getOutput())
                .contains("'logastApply' not found");
    }

    /** The build's own plugins { id 'dev.logast' } wins: applied once, its logast { } block still configures the tasks. */
    @Test
    void aBuildThatAppliesThePluginItselfKeepsIt() throws IOException {
        Files.writeString(project.resolve("build.gradle"), """
                plugins {
                    id 'java'
                    id 'dev.logast'
                }
                apply from: 'deps.gradle'
                logast { failOn = 'high' }
                """);
        BuildResult r = gradle(REPO, "logastApply", "--preview").withPluginClasspath().build();

        assertThat(r.task(":logastApply").getOutcome()).isEqualTo(TaskOutcome.SUCCESS);
        assertThat(r.getOutput()).contains("DRY RUN");
    }

    private void write(String path, String content) throws IOException {
        Path file = project.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    /** A build with the shipped init script and {@code mavenLocal} as Maven Local. */
    private GradleRunner gradle(Path mavenLocal, String... args) {
        List<String> all = new ArrayList<>(List.of(args));
        all.addAll(List.of("--init-script", SCRIPT.toString(), "-Dmaven.repo.local=" + mavenLocal,
                "--configuration-cache", "--stacktrace"));
        return GradleRunner.create().withProjectDir(project.toFile()).withArguments(all).forwardOutput();
    }
}
