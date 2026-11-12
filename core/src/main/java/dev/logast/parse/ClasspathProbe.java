package dev.logast.parse;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipFile;

/** Answers "is this class on the target project's classpath?" by looking into jars and class dirs. */
public final class ClasspathProbe {
    private final List<Path> classpath;
    private final Map<String, Boolean> cache = new HashMap<>();

    public ClasspathProbe(List<Path> classpath) {
        this.classpath = classpath;
    }

    public boolean hasClass(String fqn) {
        return cache.computeIfAbsent(fqn, this::lookup);
    }

    private boolean lookup(String fqn) {
        String entry = fqn.replace('.', '/') + ".class";
        for (Path p : classpath) {
            if (Files.isDirectory(p)) {
                if (Files.exists(p.resolve(entry))) {
                    return true;
                }
            } else if (p.toString().endsWith(".jar") && Files.exists(p)) {
                try (ZipFile zip = new ZipFile(p.toFile())) {
                    if (zip.getEntry(entry) != null) {
                        return true;
                    }
                } catch (IOException ignored) {
                    // unreadable jar: treat as absent
                }
            }
        }
        return false;
    }
}
