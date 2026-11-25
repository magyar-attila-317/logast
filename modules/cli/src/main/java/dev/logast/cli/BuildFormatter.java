package dev.logast.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * #237: a build that checks its formatting (spring-javaformat's checkFormat, Spotless's spotlessCheck) fails on code in
 * logast's own layout. When the build applies a formatter plugin logast knows, apply runs its fix task once, after
 * writing: io.spring.javaformat → {@code format}, com.diffplug.spotless → {@code spotlessApply}. Without such a
 * plugin nothing runs. Checkstyle rules that are not formatting stay findings of the build.
 *
 * @param plugin the plugin id found
 * @param task   the Gradle task that formats
 */
record BuildFormatter(String plugin, String task) {
    private static final List<BuildFormatter> KNOWN = List.of(
            new BuildFormatter("io.spring.javaformat", "format"),
            new BuildFormatter("com.diffplug.spotless", "spotlessApply"));

    /** The formatter the build applies (in a build file at the root or one level below), or null. */
    static BuildFormatter of(Path root) {
        StringBuilder text = new StringBuilder();
        try (var files = Files.walk(root, 2)) {
            for (Path p : files.filter(f -> {
                String n = f.getFileName().toString();
                return n.equals("build.gradle") || n.equals("build.gradle.kts") || n.equals("settings.gradle")
                        || n.equals("settings.gradle.kts") || n.equals("libs.versions.toml");
            }).toList()) {
                text.append(dev.logast.ServiceSetup.uncommented(p.getFileName().toString(), Files.readString(p))).append('\n');
            }
        } catch (IOException | RuntimeException e) {
            return null;
        }
        String all = text.toString();
        for (BuildFormatter f : KNOWN) {
            // the plugin id, or the plugin's artifact on a buildscript classpath (spring-javaformat-gradle-plugin)
            if (all.contains(f.plugin()) || f.plugin().equals("io.spring.javaformat") && all.contains("spring-javaformat-gradle-plugin")) {
                return f;
            }
        }
        return null;
    }

    /** Runs the task with the project's wrapper (else gradle on the PATH); null when it worked, else why not. */
    String run(Path root) {
        Path wrapper = root.resolve(System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win") ? "gradlew.bat" : "gradlew");
        List<String> cmd = new ArrayList<>();
        cmd.add(Files.isRegularFile(wrapper) ? wrapper.toAbsolutePath().toString() : "gradle");
        cmd.addAll(List.of("-q", "--console=plain", task));
        try {
            Process p = new ProcessBuilder(cmd).directory(root.toFile()).redirectErrorStream(true).start();
            p.getOutputStream().close();
            byte[] out = p.getInputStream().readAllBytes();
            if (!p.waitFor(10, TimeUnit.MINUTES)) {
                p.destroyForcibly();
                return task + " did not finish in 10 minutes";
            }
            if (p.exitValue() != 0) {
                String text = new String(out, java.nio.charset.StandardCharsets.UTF_8).strip();
                return task + " failed (exit " + p.exitValue() + ")" + (text.isEmpty() ? "" : ": " + dev.logast.rules.Text.oneLine(text, 200));
            }
            return null;
        } catch (IOException e) {
            return task + " could not run: " + e.getMessage();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return task + " was interrupted";
        }
    }
}
