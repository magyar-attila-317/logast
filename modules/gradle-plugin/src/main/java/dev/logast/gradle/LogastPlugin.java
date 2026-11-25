package dev.logast.gradle;

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.plugins.JavaPlugin;

/**
 * {@code plugins { id("dev.logast") }}: logastScan, logastBaseline, logastCheck, logastSarif and logastApply on the
 * project's main sources, with the build's own compile classpath. Tasks are added once the java plugin is applied (a Spring Boot service has it).
 */
public class LogastPlugin implements Plugin<Project> {
    public static final String GROUP = "logast";

    @Override
    public void apply(Project project) {
        LogastExtension ext = project.getExtensions().create("logast", LogastExtension.class);
        ext.getFailOn().convention("medium");
        ext.getBaseline().convention(project.getLayout().getProjectDirectory().file(".logast/baseline.json"));
        ext.getFormat().convention("summary");
        ext.getMaxHeapSize().convention("2g");

        project.getPlugins().withType(JavaPlugin.class, java -> {
            project.getTasks().register("logastScan", LogastScan.class, t -> {
                t.setDescription("Scans the project with logast: findings in .logast/reports/orders.json, a summary on the console");
                t.getFormat().convention(ext.getFormat());
            });
            project.getTasks().register("logastBaseline", LogastBaseline.class, t -> {
                t.setDescription("Records today's findings as the baseline logastCheck compares with (commit the file)");
                t.getBaseline().convention(ext.getBaseline());
            });
            project.getTasks().register("logastCheck", LogastCheck.class, t -> {
                t.setDescription("CI gate: fails on findings not in the baseline at or above logast.failOn");
                t.getBaseline().convention(ext.getBaseline());
                t.getFailOn().convention(ext.getFailOn());
            });
            project.getTasks().register("logastSarif", LogastSarif.class, t -> {
                t.setDescription("Writes the findings as SARIF (build/reports/logast/logast.sarif) for GitHub code scanning");
                t.getSarifFile().convention(project.getLayout().getBuildDirectory().file("reports/logast/logast.sarif"));
            });
            project.getTasks().register("logastApply", LogastApply.class, t -> {
                t.setDescription("Applies every fix that needs no message text to the sources (review with git diff); "
                        + "--preview or -PlogastDryRun previews");
                t.getDryRun().convention(project.getProviders().gradleProperty("logastDryRun")
                        .map(v -> !v.equalsIgnoreCase("false")).orElse(false));
            });
            // #237: a build that formats its sources (spring-javaformat, Spotless) formats what logastApply wrote
            for (String[] f : new String[][]{{"io.spring.javaformat", "format"}, {"com.diffplug.spotless", "spotlessApply"}}) {
                project.getPluginManager().withPlugin(f[0], p -> project.getTasks().named("logastApply")
                        .configure(t -> t.finalizedBy(f[1])));
            }
            project.getTasks().withType(LogastTask.class).configureEach(t -> {
                t.setGroup(GROUP);
                t.getProjectRoot().convention(project.getLayout().getProjectDirectory());
                t.getScanClasspath().from(project.getConfigurations().named(JavaPlugin.COMPILE_CLASSPATH_CONFIGURATION_NAME));
                t.getMaxHeapSize().convention(ext.getMaxHeapSize());
                t.getJavaLauncher().convention(ext.getJavaLauncher());
            });
        });
    }
}
