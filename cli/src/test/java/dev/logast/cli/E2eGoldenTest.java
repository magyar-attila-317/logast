package dev.logast.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End to end, the way a person migrates a service: {@code logast apply . --auto --write} on e2e/before (shapes real
 * services showed), then every file and the printed output must equal e2e/expected. The result must compile.
 * A second apply is recorded too: what it still changes shows up in the snapshot.
 * <p>After an intended behaviour change: {@code ./gradlew :cli:test -Pe2e.update}, then review the diff of e2e/expected.
 */
class E2eGoldenTest {
    private static final Path BEFORE = Path.of(System.getProperty("logast.e2e.before"));
    private static final Path EXPECTED = Path.of(System.getProperty("logast.e2e.expected"));
    private static final String CLASSPATH = System.getProperty("logast.e2e.classpath");
    private static final boolean UPDATE = Boolean.getBoolean("logast.e2e.update");
    /** Generated each run or machine-specific: not part of the snapshot. */
    private static final Set<String> SKIP = Set.of(".logast/orders.json");

    @TempDir
    Path tmp;

    @Test
    void migrationMatchesTheGoldenSnapshot() throws IOException {
        Path app = tmp.resolve("app");
        copy(BEFORE, app);

        String first = run(app, "apply", app.toString(), "--auto", "--write", "--classpath-file", CLASSPATH);
        String second = run(app, "apply", app.toString(), "--auto", "--write", "--classpath-file", CLASSPATH);
        Files.writeString(app.resolve("OUTPUT.txt"), "=== logast apply . --auto --write\n" + first
                + "\n=== the same again (what one run left behind)\n" + second);

        compile(app);

        Map<String, String> actual = files(app);
        if (UPDATE) {
            deleteTree(EXPECTED);
            for (var e : actual.entrySet()) {
                Path p = EXPECTED.resolve(e.getKey());
                Files.createDirectories(p.getParent());
                Files.writeString(p, e.getValue());
            }
            return;
        }
        Map<String, String> expected = files(EXPECTED);
        List<String> problems = new ArrayList<>();
        new TreeSet<>(expected.keySet()).stream().filter(k -> !actual.containsKey(k)).forEach(k -> problems.add("missing: " + k));
        new TreeSet<>(actual.keySet()).stream().filter(k -> !expected.containsKey(k)).forEach(k -> problems.add("new file: " + k));
        for (String k : new TreeSet<>(actual.keySet())) {
            if (expected.containsKey(k) && !expected.get(k).equals(actual.get(k))) {
                problems.add(diff(k, expected.get(k), actual.get(k)));
            }
        }
        assertThat(problems).as("e2e/expected differs (run ./gradlew :cli:test -Pe2e.update if the change is intended, "
                + "then review `git diff e2e/expected`)").isEmpty();
    }

    /**
     * A person rewords every line logast asked about (keeping the form it requires), then applies again: the result
     * must settle (a further apply changes nothing) and still pass the migration invariants.
     */
    @Test
    void rewordingTheGeneratedLinesConverges() throws IOException {
        Path app = tmp.resolve("app");
        copy(BEFORE, app);
        run(app, "apply", app.toString(), "--auto", "--write", "--classpath-file", CLASSPATH);

        Path fills = app.resolve(".logast/fills.json");
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var root = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(fills.toFile());
        int n = 0;
        for (var it = root.fields(); it.hasNext(); ) {
            var e = it.next();
            if (e.getKey().startsWith("_") || !(e.getValue() instanceof com.fasterxml.jackson.databind.node.ObjectNode entry)) {
                continue;
            }
            for (var f = entry.fieldNames(); f.hasNext(); ) {
                String slot = f.next();
                if (slot.startsWith("_") || slot.equals("args")) {
                    continue;
                }
                String hint = entry.path("_" + slot).asText("");
                java.util.regex.Matcher form = java.util.regex.Pattern.compile("must read as: (\\{subject} \\w+)").matcher(hint);
                entry.put(slot, form.find() ? form.group(1).replace("{subject}", "Reworded step " + (++n)) : "Reworded line " + (++n));
            }
        }
        mapper.writerWithDefaultPrettyPrinter().writeValue(fills.toFile(), root);

        String second = run(app, "apply", app.toString(), "--auto", "--write", "--classpath-file", CLASSPATH);
        String third = run(app, "apply", app.toString(), "--auto", "--write", "--classpath-file", CLASSPATH);
        assertThat(second).as("the reworded lines are applied").doesNotContain("REJECTED").doesNotContain("CHECK FAILED");
        assertThat(third).as("nothing left to change after the rewording:\n" + third).contains("applied (0)").doesNotContain("CHECK FAILED");
        compile(app);
    }

    /** A person rewrites a generated line in the code, in their own words: logast must not add another one. */
    @Test
    void freelyRewordedLifecycleLinesAreRecognisedByTheirRole() throws IOException {
        Path app = tmp.resolve("app");
        copy(BEFORE, app);
        run(app, "apply", app.toString(), "--auto", "--write", "--classpath-file", CLASSPATH);
        Path job = app.resolve("src/main/java/com/example/e2e/coupon/CouponCleanupJob.java");
        String text = Files.readString(job);
        assertThat(text).contains("\"Coupon cleanup started\"", "\"Coupon cleanup completed [couponCount={}]\"");
        Files.writeString(job, text.replace("\"Coupon cleanup started\"", "\"Nightly sweep begins\"")
                .replace("\"Coupon cleanup completed [couponCount={}]\"", "\"Nightly sweep done, closed {} coupons\""));

        run(app, "apply", app.toString(), "--auto", "--write", "--classpath-file", CLASSPATH);
        String after = Files.readString(job);
        assertThat(after).doesNotContain("Coupon cleanup started").doesNotContain("Coupon cleanup completed");
        assertThat(after.split("log\\.info\\(", -1).length - 1).as(after).isEqualTo(2);   // still one start, one end
    }

    /**
     * A service migrated by an earlier logast version has its scopes too low (calculate, calculateIncome). A new run
     * puts the scope at the top of the flow (processRow) and unwraps the old ones: they are nested now.
     */
    @Test
    void scopesLeftTooLowByAnEarlierRunMoveUp() throws IOException {
        Path app = tmp.resolve("app");
        copy(BEFORE, app);
        Path calc = app.resolve("src/main/java/com/example/e2e/income/IncomeCalculator.java");
        // the calculator as an earlier logast version left it: scopes in calculate and calculateIncome
        Files.writeString(calc, """
                package com.example.e2e.income;

                import com.example.e2e.logging.MdcScope;
                import java.math.BigDecimal;
                import java.util.List;
                import java.util.Optional;
                import org.slf4j.Logger;
                import org.slf4j.LoggerFactory;

                public final class IncomeCalculator {
                    private static final Logger log = LoggerFactory.getLogger(IncomeCalculator.class);

                    private IncomeCalculator() {
                    }

                    public static IncomeOutcome calculate(SalesTotals salesData, List<CouponDetails> matchingCoupons) {
                        try (var mdc = MdcScope.of("couponId", salesData.couponId())) {
                            log.debug("Calculating income [saleDay={}, matchingCoupons={}]", salesData.saleDay(), matchingCoupons.size());
                            if (matchingCoupons.isEmpty()) {
                                log.debug("Coupon not found in the coupon list");
                                return new IncomeOutcome(true, BigDecimal.ZERO);
                            }
                            return evaluateOutcome(salesData, matchingCoupons);
                        }
                    }

                    private static IncomeOutcome evaluateOutcome(SalesTotals salesData, List<CouponDetails> sortedCoupons) {
                        for (CouponDetails coupon : sortedCoupons) {
                            Optional<String> exclusion = validateCoupon(salesData, coupon);
                            if (exclusion.isEmpty()) {
                                return calculateIncome(salesData, coupon);
                            }
                            log.trace("Coupon excluded [couponId={}, reason={}]", coupon.couponId(), exclusion.get());
                        }
                        return new IncomeOutcome(true, BigDecimal.ZERO);
                    }

                    private static Optional<String> validateCoupon(SalesTotals salesData, CouponDetails coupon) {
                        for (CouponCheck check : CouponChecks.all()) {
                            Optional<String> result = check.validate(coupon, salesData);
                            if (result.isPresent()) {
                                return result;
                            }
                        }
                        return Optional.empty();
                    }

                    private static IncomeOutcome calculateIncome(SalesTotals salesData, CouponDetails coupon) {
                        try (var mdc = MdcScope.of("couponId", coupon.couponId())) {
                            BigDecimal result = salesData.totalDiscount().multiply(coupon.fundingPercentage());
                            log.debug("Income calculated [saleDay={}, result={}]", salesData.saleDay(), result);
                            return new IncomeOutcome(false, result);
                        }
                    }
                }
                """);

        // and a check with a two-key scope (couponId + agreementId), as earlier versions wrote them
        Path checks = app.resolve("src/main/java/com/example/e2e/income/CouponChecks.java");
        Files.writeString(checks, Files.readString(checks)
                .replace("import java.util.List;", "import com.example.e2e.logging.MdcScope;\nimport java.util.List;")
                .replace("""
                                    log.trace(CHECK_LOG_MESSAGE, "funding", coupon.couponId(), coupon.agreementId(), salesData.saleDay());
                                    if (coupon.fundingPercentage() == null) {
                                        log.debug("No funding found [couponId={}, agreementId={}]", coupon.couponId(), coupon.agreementId());
                                        return Optional.of("NO_FUNDING");
                                    }
                                    return Optional.empty();
                        """, """
                                    try (var mdc = MdcScope.of("couponId", coupon.couponId(), "agreementId", coupon.agreementId())) {
                                        log.trace(CHECK_LOG_MESSAGE, "funding", coupon.couponId(), coupon.agreementId(), salesData.saleDay());
                                        if (coupon.fundingPercentage() == null) {
                                            log.debug("No funding found");
                                            return Optional.of("NO_FUNDING");
                                        }
                                        return Optional.empty();
                                    }
                        """));
        assertThat(Files.readString(checks)).as("test setup").contains("MdcScope.of(\"couponId\", coupon.couponId(), \"agreementId\"");

        String out = run(app, "apply", app.toString(), "--auto", "--write", "--classpath-file", CLASSPATH);
        assertThat(out).doesNotContain("CHECK FAILED");
        assertThat(Files.readString(app.resolve("src/main/java/com/example/e2e/income/CalculationService.java")))
                .contains("MdcScope.of(\"couponId\", salesData.couponId())");
        assertThat(Files.readString(calc)).doesNotContain("\"couponId\"");
        assertThat(Files.readString(checks)).as(Files.readString(checks)).doesNotContain("\"couponId\"");
        compile(app);
    }

    /** Runs the CLI in-process; stdout with the temp path, timings and colours normalised. */
    private static String run(Path app, String... args) {
        PrintStream out = System.out;
        PrintStream err = System.err;
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(buf, true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
            new CommandLine(new Main()).execute(args);
        } finally {
            System.setOut(out);
            System.setErr(err);
        }
        return buf.toString(StandardCharsets.UTF_8)
                .replace(app.toString(), "<app>")
                .replaceAll("\u001b\\[[0-9;]*m", "")
                .replaceAll("\\d+(\\.\\d+)?s\\b", "<t>")
                .replaceAll("(?m)[ \t]+$", "");
    }

    private static Map<String, String> files(Path root) throws IOException {
        Map<String, String> out = new TreeMap<>();
        if (!Files.exists(root)) {
            return out;
        }
        try (Stream<Path> s = Files.walk(root)) {
            for (Path p : s.filter(Files::isRegularFile).toList()) {
                String rel = root.relativize(p).toString().replace('\\', '/');
                // the copied support classes are logast's own code: compiled, not compared
                if (rel.startsWith("out/") || SKIP.contains(rel) || rel.startsWith("src/main/java/com/example/e2e/logging/")) {
                    continue;
                }
                out.put(rel, Files.readString(p));
            }
        }
        return out;
    }

    /** The first differing lines of a file, enough to see what changed. */
    private static String diff(String file, String expected, String actual) {
        List<String> e = expected.lines().toList();
        List<String> a = actual.lines().toList();
        StringBuilder sb = new StringBuilder("changed: " + file + "\n");
        int shown = 0;
        for (int i = 0; i < Math.max(e.size(), a.size()) && shown < 12; i++) {
            String x = i < e.size() ? e.get(i) : "<none>";
            String y = i < a.size() ? a.get(i) : "<none>";
            if (!x.equals(y)) {
                sb.append(String.format("  line %d%n    - %s%n    + %s%n", i + 1, x, y));
                shown++;
            }
        }
        return sb.toString();
    }

    private static void compile(Path root) throws IOException {
        List<String> sources;
        try (Stream<Path> s = Files.walk(root.resolve("src/main/java"))) {
            sources = s.filter(p -> p.toString().endsWith(".java")).map(Path::toString).toList();
        }
        List<String> cp = Files.readAllLines(Path.of(CLASSPATH)).stream().filter(l -> !l.isBlank()).toList();
        List<String> args = new ArrayList<>(List.of("-d", root.resolve("out").toString(), "-proc:none",
                "-cp", String.join(java.io.File.pathSeparator, cp)));
        args.addAll(sources);
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int rc = javac.run(null, null, err, args.toArray(String[]::new));
        assertThat(rc).as("the migrated project must compile:\n" + err).isZero();
    }

    private static void copy(Path from, Path to) throws IOException {
        try (Stream<Path> s = Files.walk(from)) {
            for (Path p : s.toList()) {
                Path dst = to.resolve(from.relativize(p).toString());
                if (Files.isDirectory(p)) {
                    Files.createDirectories(dst);
                } else {
                    Files.copy(p, dst);
                }
            }
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> s = Files.walk(root)) {
            for (Path p : s.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        }
    }
}
