package dev.logast.gradle;

import org.gradle.api.Project;
import org.gradle.testfixtures.ProjectBuilder;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** #115: what applying {@code dev.logast} adds, without running a build (the TestKit tests run logast itself). */
class LogastPluginTest {
    @Test
    void tasksAndDefaultsOnAJavaProject() {
        Project p = ProjectBuilder.builder().build();
        p.getPluginManager().apply("java");
        p.getPluginManager().apply("dev.logast");

        LogastCheck check = (LogastCheck) p.getTasks().getByName("logastCheck");
        assertThat(check.getFailOn().get()).isEqualTo("medium");
        assertThat(check.getBaseline().get().getAsFile()).isEqualTo(p.file(".logast/baseline.json"));
        assertThat(check.getGroup()).isEqualTo("logast");
        assertThat(((LogastScan) p.getTasks().getByName("logastScan")).getFormat().get()).isEqualTo("summary");
        assertThat(((LogastBaseline) p.getTasks().getByName("logastBaseline")).getBaseline().get().getAsFile())
                .isEqualTo(p.file(".logast/baseline.json"));
        assertThat(check.getMaxHeapSize().get()).isEqualTo("2g");
    }

    @Test
    void theExtensionReachesTheTasks() {
        Project p = ProjectBuilder.builder().build();
        p.getPluginManager().apply("dev.logast");
        p.getPluginManager().apply("java");   // either order
        LogastExtension ext = p.getExtensions().getByType(LogastExtension.class);
        ext.getFailOn().set("high");
        ext.getBaseline().set(p.file("ci/baseline.json"));
        ext.getFormat().set("human");

        assertThat(((LogastCheck) p.getTasks().getByName("logastCheck")).getFailOn().get()).isEqualTo("high");
        assertThat(((LogastCheck) p.getTasks().getByName("logastCheck")).getBaseline().get().getAsFile())
                .isEqualTo(p.file("ci/baseline.json"));
        assertThat(((LogastScan) p.getTasks().getByName("logastScan")).getFormat().get()).isEqualTo("human");
    }

    @Test
    void applyWritesUnlessADryRunIsAsked() {   // #122
        Project p = ProjectBuilder.builder().build();
        p.getPluginManager().apply("java");
        p.getPluginManager().apply("dev.logast");

        LogastApply apply = (LogastApply) p.getTasks().getByName("logastApply");
        assertThat(apply.getGroup()).isEqualTo("logast");
        assertThat(apply.getDryRun().get()).isFalse();
        assertThat(apply.command()).containsExactly("apply", "--auto", "--write");
        assertThat(apply.getScanClasspath().getFrom()).isNotEmpty();   // compileClasspath, as for the scan tasks

        apply.getDryRun().set(true);
        assertThat(apply.command()).containsExactly("apply", "--auto");
    }

    @Test
    void applyFailsOnErrorExitsAndOnACheckFailure() {
        assertThat(LogastWork.applyFailure(0, "applied (3): a b c")).isNull();
        assertThat(LogastWork.applyFailure(1, "REJECTED X-1: does not compile")).contains("rejected");
        assertThat(LogastWork.applyFailure(2, "nothing selected")).contains("exit 2");
        assertThat(LogastWork.applyFailure(0, "CHECK FAILED (1): the result breaks a rule")).contains("CHECK FAILED");
    }

    @Test
    void noTasksWithoutJava() {
        Project p = ProjectBuilder.builder().build();
        p.getPluginManager().apply("dev.logast");
        assertThat(p.getTasks().findByName("logastCheck")).isNull();
    }
}
