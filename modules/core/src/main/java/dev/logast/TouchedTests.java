package dev.logast;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * #289: the tests to run after an apply, and the changed classes whose log lines no test checks. A test "uses" a changed
 * class when its source names it; it "checks log lines" when it also captures logs (a Logback ListAppender, LogCaptor,
 * Spring Boot's OutputCaptureExtension / CapturedOutput, logast's test capture). Read from the sources, nothing is run:
 * the command to run them is given (one Gradle test task per module, so a module without a match does not fail).
 *
 * @param tests     test classes (fully qualified) per Gradle test task ({@code test}, {@code :orders:test})
 * @param unchecked changed source files no log-capturing test names
 */
public record TouchedTests(Map<String, List<String>> tests, List<String> unchecked) {
    public static final String REPORT = LogastDir.DIR + "/reports/tests.txt";

    private static final Pattern CAPTURES_LOGS = Pattern.compile(
            "\\b(ListAppender|LogCaptor|OutputCaptureExtension|CapturedOutput|OutputCaptureRule|LogCapture|CapturedLogs|ILoggingEvent)\\b");
    private static final Pattern PACKAGE = Pattern.compile("(?m)^package\\s+([\\w.]+)\\s*;");

    /**
     * @param changed  files the apply changed, relative to {@code root}
     * @param skipPath a path fragment of files to leave out (the support classes logast copies in), or null
     */
    public static TouchedTests of(Path root, Collection<String> changed, String skipPath) {
        Map<String, String> classes = new LinkedHashMap<>();   // simple name -> changed file
        for (String f : changed) {
            String n = f.replace('\\', '/');
            if (n.endsWith(".java") && n.contains("src/main/java/") && (skipPath == null || !n.contains(skipPath))) {
                classes.put(n.substring(n.lastIndexOf('/') + 1, n.length() - ".java".length()), n);
            }
        }
        Map<String, List<String>> tests = new TreeMap<>();
        TreeSet<String> checked = new TreeSet<>();
        if (classes.isEmpty()) {
            return new TouchedTests(tests, List.of());
        }
        Pattern mentions = Pattern.compile("\\b(" + String.join("|", classes.keySet()) + ")\\b");
        List<Path> sources = new ArrayList<>();
        try (var files = Files.walk(root)) {
            files.filter(p -> {
                String s = p.toString().replace('\\', '/');
                return s.endsWith(".java") && s.contains("/src/test/java/") && !s.contains("/build/") && !s.contains("/.logast/");
            }).sorted().forEach(sources::add);
        } catch (IOException e) {
            return new TouchedTests(tests, List.copyOf(classes.values()));
        }
        for (Path p : sources) {
            String text;
            try {
                text = Files.readString(p);
            } catch (IOException | RuntimeException e) {
                continue;
            }
            var m = mentions.matcher(text);
            List<String> named = new ArrayList<>();
            while (m.find()) {
                named.add(m.group(1));
            }
            if (named.isEmpty()) {
                continue;
            }
            String rel = root.relativize(p).toString().replace('\\', '/');
            String module = rel.substring(0, rel.indexOf("src/test/java/"));
            String task = module.isEmpty() ? "test" : ":" + module.substring(0, module.length() - 1).replace('/', ':') + ":test";
            var pkg = PACKAGE.matcher(text);
            String simple = p.getFileName().toString().replaceFirst("\\.java$", "");
            tests.computeIfAbsent(task, k -> new ArrayList<>()).add(pkg.find() ? pkg.group(1) + "." + simple : simple);
            if (CAPTURES_LOGS.matcher(text).find()) {
                named.forEach(n -> checked.add(classes.get(n)));
            }
        }
        List<String> unchecked = classes.values().stream().filter(f -> !checked.contains(f)).sorted().toList();
        return new TouchedTests(tests, unchecked);
    }

    public int count() {
        return tests.values().stream().mapToInt(List::size).sum();
    }

    /** The command that runs them: {@code ./gradlew test --tests a.BTest :orders:test --tests c.DTest}. */
    public String command() {
        StringBuilder sb = new StringBuilder("./gradlew");
        tests.forEach((task, names) -> {
            sb.append(' ').append(task);
            names.forEach(n -> sb.append(" --tests ").append(n));
        });
        return sb.toString();
    }

    public String report() {
        StringBuilder sb = new StringBuilder("# the tests that use a class this apply changed (#289): run them\n");
        sb.append(tests.isEmpty() ? "# none\n" : command() + "\n");
        sb.append("\n# changed classes whose log lines no test checks (no test that names them captures logs)\n");
        unchecked.forEach(f -> sb.append(f).append('\n'));
        return sb.toString();
    }
}
