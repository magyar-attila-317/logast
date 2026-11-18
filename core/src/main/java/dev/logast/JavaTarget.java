package dev.logast;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.OptionalInt;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * #5: the Java version a service builds for, read from its Gradle build files: a toolchain
 * ({@code JavaLanguageVersion.of(25)}, {@code jvmToolchain(25)}), {@code options.release} or
 * {@code sourceCompatibility}. logast resolves the service's types with the JDK it runs on: APIs of a newer Java
 * (Stream Gatherers on 24+, say) stay unresolved on an older one, and calls through them drop out of the call graph.
 */
public final class JavaTarget {
    private static final List<Pattern> PATTERNS = List.of(
            Pattern.compile("JavaLanguageVersion\\.of\\(\\s*\"?(\\d+)\"?\\s*\\)"),
            Pattern.compile("jvmToolchain\\(\\s*(\\d+)\\s*\\)"),
            Pattern.compile("release(?:\\.set\\(|\\s*=\\s*)\\s*(\\d+)"),
            Pattern.compile("(?:source|target)Compatibility(?:\\.set\\(|\\s*=\\s*)\\s*(?:JavaVersion\\.VERSION_(?:1_)?(\\d+)|['\"]?(\\d+)['\"]?)"));

    private JavaTarget() {
    }

    /** The highest Java version the build files under {@code root} (3 levels deep) name; empty when none does. */
    public static OptionalInt of(Path root) {
        int max = -1;
        try (Stream<Path> files = Files.walk(root, 3)) {
            for (Path p : files.filter(f -> {
                String n = f.getFileName().toString();
                return (n.equals("build.gradle") || n.equals("build.gradle.kts")) && !f.toString().contains("/build/");
            }).toList()) {
                String text = Files.readString(p);
                for (Pattern pt : PATTERNS) {
                    Matcher m = pt.matcher(text);
                    while (m.find()) {
                        String v = m.group(1) != null ? m.group(1) : m.groupCount() > 1 ? m.group(2) : null;
                        if (v != null) {
                            max = Math.max(max, Integer.parseInt(v));
                        }
                    }
                }
            }
        } catch (IOException | UncheckedIOException e) {
            return OptionalInt.empty();
        }
        return max < 0 ? OptionalInt.empty() : OptionalInt.of(max);
    }

    /** The warning when the service builds for a newer Java than logast runs on, else null. */
    public static String warning(Path root) {
        OptionalInt target = of(root);
        int running = Runtime.version().feature();
        if (target.isEmpty() || target.getAsInt() <= running) {
            return null;
        }
        return "the service builds for Java " + target.getAsInt() + ", logast runs on Java " + running + ": calls into "
                + "APIs newer than Java " + running + " stay unresolved and drop out of the call graph; run logast on Java "
                + target.getAsInt() + " or newer (25 recommended)";
    }
}
