package dev.logast.export;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * #181: what a {@code classpath:} Flyway location holds on the service's classpath, as Flyway sees it at runtime:
 * class folders directly, jars (a library that ships migrations) by their entries under that path, extracted to a
 * temporary folder for the run. Names an extracted file as {@code <jar>!/<entry>} for evidence.
 */
final class ClasspathMigrations implements AutoCloseable {
    static final ClasspathMigrations NONE = new ClasspathMigrations(List.of());

    private final List<Path> classpath;
    private Path tmp;
    /** extracted folder → the jar's file name. */
    private final Map<Path, String> jars = new LinkedHashMap<>();

    ClasspathMigrations(List<Path> classpath) {
        this.classpath = classpath == null ? List.of() : classpath;
    }

    /** The folders on the classpath (other than the service's own resources) that hold {@code sub}. */
    List<Path> folders(String sub, Path ownResources) {
        String path = sub.replaceAll("^/+|/+$", "");
        List<Path> out = new ArrayList<>();
        for (Path e : classpath) {
            if (Files.isDirectory(e)) {
                Path d = e.resolve(path).normalize();
                if (Files.isDirectory(d) && (ownResources == null || !d.startsWith(ownResources))) {
                    out.add(d);
                }
            } else if (e.getFileName() != null && e.getFileName().toString().endsWith(".jar") && Files.isRegularFile(e)) {
                Path d = extract(e, path);
                if (d != null) {
                    out.add(d);
                }
            }
        }
        return out;
    }

    private Path extract(Path jar, String path) {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            Path into = null;
            for (Enumeration<? extends ZipEntry> en = zip.entries(); en.hasMoreElements(); ) {
                ZipEntry z = en.nextElement();
                if (z.isDirectory() || !z.getName().startsWith(path + "/") || !z.getName().toLowerCase().endsWith(".sql")) {
                    continue;
                }
                if (into == null) {
                    if (tmp == null) {
                        tmp = Files.createTempDirectory("logast-flyway-jars");
                    }
                    into = tmp.resolve(jars.size() + "-" + jar.getFileName());
                    jars.put(into, jar.getFileName().toString());
                }
                Path target = into.resolve(z.getName()).normalize();
                if (!target.startsWith(into)) {
                    continue;   // a path that would leave the folder
                }
                Files.createDirectories(target.getParent());
                try (InputStream in = zip.getInputStream(z)) {
                    Files.copy(in, target);
                }
            }
            return into == null ? null : into.resolve(path);
        } catch (IOException e) {
            return null;   // an unreadable jar holds no migration Flyway would run either
        }
    }

    /** {@code <jar>!/<entry>} for a file extracted from a jar; null for any other. */
    String name(Path file) {
        Path f = file.toAbsolutePath().normalize();
        for (var e : jars.entrySet()) {
            if (f.startsWith(e.getKey())) {
                return e.getValue() + "!/" + e.getKey().relativize(f).toString().replace('\\', '/');
            }
        }
        return null;
    }

    @Override
    public void close() {
        if (tmp == null) {
            return;
        }
        try (var s = Files.walk(tmp)) {
            for (Path p : s.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
