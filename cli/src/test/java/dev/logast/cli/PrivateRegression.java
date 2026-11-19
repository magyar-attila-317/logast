package dev.logast.cli;

import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.stream.Stream;

/**
 * #10: the owner's real services as regression cases, outside git: {@code <dir>/<service>/before/} (the service as
 * it was), {@code expected/} (the migrated result after a reviewed run) and an optional {@code classpath.txt}. Each
 * check migrates a copy of {@code before} ({@code apply --auto --write}), applies once more, and compares every file
 * with {@code expected}; {@code update} records {@code expected} instead. Nothing here is committed or printed in CI:
 * the folder exists only on the owner's machine.
 */
final class PrivateRegression {
    /** Machine-specific or written fresh on every run: not compared. */
    private static final Set<String> SKIP_PREFIXES = Set.of("build/", ".gradle/", "out/", ".logast/reports/", ".logast/classpath");

    private PrivateRegression() {
    }

    /** {@code -Dlogast.private.dir}, else {@code ~/.logast-private}. */
    static Path root() {
        String dir = System.getProperty("logast.private.dir", "");
        return dir.isBlank() ? Path.of(System.getProperty("user.home"), ".logast-private") : Path.of(dir);
    }

    /** The service folders under {@code root} that have a {@code before/}. */
    static List<Path> services(Path root) throws IOException {
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> s = Files.list(root)) {
            return s.filter(p -> Files.isDirectory(p.resolve("before"))).sorted().toList();
        }
    }

    /**
     * Migrates the service and compares (or records) the result.
     *
     * @return the problems: files missing, new or different (by name and first differing line), the second apply not
     * settling; empty when the result equals {@code expected}
     */
    static List<String> check(Path service, Path work, boolean update) throws IOException {
        Path app = work.resolve(service.getFileName().toString());
        copy(service.resolve("before"), app);
        List<String> args = new ArrayList<>(List.of("apply", app.toString(), "--auto", "--write"));
        Path cp = service.resolve("classpath.txt");
        if (Files.exists(cp)) {
            args.addAll(List.of("--classpath-file", cp.toString()));
        }
        String first = run(args);
        String second = run(args);
        List<String> problems = new ArrayList<>();
        if (!second.contains("nothing to change")) {
            problems.add("a second apply still changes something");
        }
        if ((first + second).contains("CHECK FAILED")) {
            problems.add("CHECK FAILED in an apply");
        }
        Map<String, String> actual = files(app);
        Path expectedDir = service.resolve("expected");
        if (update) {
            deleteTree(expectedDir);
            for (var e : actual.entrySet()) {
                Path p = expectedDir.resolve(e.getKey());
                Files.createDirectories(p.getParent());
                Files.write(p, e.getValue().getBytes(StandardCharsets.ISO_8859_1));   // the bytes as read (files())
            }
            return problems;
        }
        if (!Files.isDirectory(expectedDir)) {
            problems.add("no expected/ yet: record it with -Pprivate.update after reviewing the result");
            return problems;
        }
        Map<String, String> expected = files(expectedDir);
        new TreeSet<>(expected.keySet()).stream().filter(k -> !actual.containsKey(k)).forEach(k -> problems.add("missing: " + k));
        new TreeSet<>(actual.keySet()).stream().filter(k -> !expected.containsKey(k)).forEach(k -> problems.add("new file: " + k));
        for (String k : new TreeSet<>(actual.keySet())) {
            if (expected.containsKey(k) && !expected.get(k).equals(actual.get(k))) {
                problems.add("differs: " + k + " (first at line " + firstDifference(expected.get(k), actual.get(k)) + ")");
            }
        }
        return problems;
    }

    private static int firstDifference(String a, String b) {
        String[] x = a.split("\n", -1);
        String[] y = b.split("\n", -1);
        int i = 0;
        while (i < x.length && i < y.length && x[i].equals(y[i])) {
            i++;
        }
        return i + 1;
    }

    private static String run(List<String> args) {
        PrintStream out = System.out;
        PrintStream err = System.err;
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(buf, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
            new CommandLine(new Main()).execute(args.toArray(String[]::new));
        } finally {
            System.setOut(out);
            System.setErr(err);
        }
        return buf.toString(StandardCharsets.UTF_8);
    }

    static Map<String, String> files(Path root) throws IOException {
        Map<String, String> out = new TreeMap<>();
        try (Stream<Path> s = Files.walk(root)) {
            for (Path p : s.filter(Files::isRegularFile).toList()) {
                String rel = root.relativize(p).toString().replace('\\', '/');
                if (SKIP_PREFIXES.stream().noneMatch(rel::startsWith)) {
                    out.put(rel, new String(Files.readAllBytes(p), StandardCharsets.ISO_8859_1));   // byte for byte
                }
            }
        }
        return out;
    }

    private static void copy(Path from, Path to) throws IOException {
        try (Stream<Path> s = Files.walk(from)) {
            for (Path p : s.toList()) {
                Path t = to.resolve(from.relativize(p).toString());
                if (Files.isDirectory(p)) {
                    Files.createDirectories(t);
                } else {
                    Files.copy(p, t, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> s = Files.walk(dir)) {
            s.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        }
    }
}
