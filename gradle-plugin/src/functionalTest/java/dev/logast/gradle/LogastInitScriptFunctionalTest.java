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
 * build does not mention logast. The script is the shipped one, with its {@code mavenLocal()} pointed at a repository
 * under build/ that the build published the plugin, cli and core to (never the real ~/.m2); the plugin's other
 * dependencies come from Maven Central, as on a developer's machine.
 */
class LogastInitScriptFunctionalTest {
    @TempDir
    Path project;
    @TempDir
    Path scripts;

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
        BuildResult r = gradle(initScript(), "logastApply").build();

        assertThat(r.task(":logastApply").getOutcome()).isEqualTo(TaskOutcome.SUCCESS);
        assertThat(Files.readString(project.resolve("src/main/java/demo/OrderController.java")))
                .contains("MdcScope.of(\"orderId\", orderId)");
        assertThat(gradle(initScript(), "compileJava").build().task(":compileJava").getOutcome()).isEqualTo(TaskOutcome.SUCCESS);
    }

    /** A plugin version no repository has: builds without logast tasks still work, since they resolve nothing of it. */
    @Test
    void buildsWithoutLogastTasksResolveNothing() throws IOException {
        Path missing = initScript(Files.readString(initScript()).replace(
                "dev.logast:gradle-plugin:" + System.getProperty("logast.version"), "dev.logast:gradle-plugin:0.0.0-missing"));

        assertThat(gradle(missing, "help").build().getOutput()).contains("BUILD SUCCESSFUL");
        assertThat(gradle(missing, "compileJava").build().task(":compileJava").getOutcome()).isEqualTo(TaskOutcome.SUCCESS);
        // the same script does resolve it for a logast task (so the two passes above are not an accident)
        assertThat(gradle(missing, "logastApply").buildAndFail().getOutput()).contains("0.0.0-missing");
    }

    @Test
    void theOptOutRemovesTheTasks() {
        assertThat(gradle(initScript(), "logastApply", "-Plogast.disabled").buildAndFail().getOutput())
                .contains("'logastApply' not found");

        Map<String, String> env = new HashMap<>(System.getenv());
        env.put("LOGAST_DISABLED", "1");
        assertThat(gradle(initScript(), "logastApply").withEnvironment(env).buildAndFail().getOutput())
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
        BuildResult r = gradle(initScript(), "logastApply", "--preview").withPluginClasspath().build();

        assertThat(r.task(":logastApply").getOutcome()).isEqualTo(TaskOutcome.SUCCESS);
        assertThat(r.getOutput()).contains("DRY RUN");
    }

    /** The shipped script with its mavenLocal() pointed at the test repository. */
    private Path initScript() {
        try {
            String shipped = Files.readString(Path.of(System.getProperty("logast.initScript")));
            assertThat(shipped).contains("mavenLocal()");
            String repo = Path.of(System.getProperty("logast.testRepo")).toUri().toString();
            return initScript(shipped.replace("mavenLocal()", "maven { url '" + repo + "' }"));
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private Path initScript(String text) throws IOException {
        Path file = Files.createTempFile(scripts, "logast", ".gradle");
        Files.writeString(file, text);
        return file;
    }

    private void write(String path, String content) throws IOException {
        Path file = project.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private GradleRunner gradle(Path initScript, String... args) {
        List<String> all = new ArrayList<>(List.of(args));
        all.addAll(List.of("--init-script", initScript.toString(), "--configuration-cache", "--stacktrace"));
        return GradleRunner.create().withProjectDir(project.toFile()).withArguments(all).forwardOutput();
    }
}
