package dev.logast.gradle;

import org.gradle.api.tasks.UntrackedTask;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Classpath;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.Nested;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.TaskAction;
import org.gradle.jvm.toolchain.JavaLauncher;
import org.gradle.workers.WorkQueue;
import org.gradle.workers.WorkerExecutor;

import javax.inject.Inject;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Runs one logast command on the project in a worker process: the plugin's own classpath (the logast CLI and
 * OpenRewrite) in a JVM of its own, so the scan has its own heap and nothing of it stays in the build's daemon. The
 * build's compile classpath reaches logast as a classpath file, as {@code logast scan --classpath-file} reads it.
 * <p>
 * These tasks are untracked (never up to date): a scan also reads {@code logast.yaml} and the decisions in {@code .logast/}.
 */
@UntrackedTask(because = "a scan also reads logast.yaml and the decisions in .logast/")
public abstract class LogastTask extends DefaultTask {
    @Internal
    public abstract DirectoryProperty getProjectRoot();

    /** The jars and class folders the project compiles against: logast resolves library types through them. */
    @Classpath
    public abstract ConfigurableFileCollection getScanClasspath();

    @Internal
    public abstract Property<String> getMaxHeapSize();

    @Nested
    @Optional
    public abstract Property<JavaLauncher> getJavaLauncher();

    @Inject
    protected abstract WorkerExecutor getWorkerExecutor();

    /** The logast command line after the classpath and root options, e.g. {@code scan --format summary}. */
    protected abstract List<String> command();

    /** Configures the worker's parameters beyond the command line (the gate's settings). */
    protected void configure(LogastWork.Params params) {
    }

    @TaskAction
    public void run() {
        if (!getJavaLauncher().isPresent() && Runtime.version().feature() < 21) {
            throw new GradleException("logast needs Java 21 or later, Gradle runs on " + Runtime.version().feature()
                    + ": set logast { javaLauncher = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(21) } }");
        }
        File cpFile = new File(getTemporaryDir(), "classpath.txt");
        try {
            Files.writeString(cpFile.toPath(), getScanClasspath().getFiles().stream()
                    .map(File::getAbsolutePath).collect(Collectors.joining("\n")));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        List<String> args = new ArrayList<>(command());
        args.add(getProjectRoot().get().getAsFile().getAbsolutePath());
        args.add("--classpath-file");
        args.add(cpFile.getAbsolutePath());

        WorkQueue queue = getWorkerExecutor().processIsolation(spec -> {
            spec.getForkOptions().setMaxHeapSize(getMaxHeapSize().get());
            if (getJavaLauncher().isPresent()) {
                spec.getForkOptions().setExecutable(getJavaLauncher().get().getExecutablePath().getAsFile());
            }
            // Java 24+ warns that OpenRewrite's Lombok support uses sun.misc.Unsafe; the flag that silences it does not
            // exist before 24 (the cli's start script does the same)
            int feature = getJavaLauncher().isPresent()
                    ? getJavaLauncher().get().getMetadata().getLanguageVersion().asInt() : Runtime.version().feature();
            if (feature >= 24) {
                spec.getForkOptions().jvmArgs("--sun-misc-unsafe-memory-access=allow");
            }
        });
        queue.submit(LogastWork.class, params -> {
            params.getArgs().set(args);
            configure(params);
        });
        queue.await();
    }
}
