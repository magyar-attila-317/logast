package dev.logast.gradle;

import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.jvm.toolchain.JavaLauncher;

/**
 * {@code logast { ... }} in a service's build script. Every property has a default, so applying the plugin is enough.
 */
public abstract class LogastExtension {
    /** {@code logastCheck} fails on a new finding of this severity or higher: low | medium | high (default medium). */
    public abstract Property<String> getFailOn();

    /** The recorded findings {@code logastCheck} compares with (default {@code .logast/baseline.json}, commit it). */
    public abstract RegularFileProperty getBaseline();

    /** What {@code logastScan} prints: summary (default) | human | compact | json | sarif. */
    public abstract Property<String> getFormat();

    /** Heap of the scan's worker process (default 2g; large services may need more). */
    public abstract Property<String> getMaxHeapSize();

    /**
     * The JVM the scan runs on (logast needs Java 21+). Default: the JVM running Gradle; set a toolchain launcher when
     * that one is older, e.g. {@code javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(21) }}.
     */
    public abstract Property<JavaLauncher> getJavaLauncher();
}
