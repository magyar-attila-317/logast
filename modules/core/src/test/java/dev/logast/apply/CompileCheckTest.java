package dev.logast.apply;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The apply round's compile gate (#44): new errors are caught, errors the code already had are not. */
class CompileCheckTest {
    @TempDir
    Path work;

    private Path write(String rel, String text) throws IOException {
        Path p = work.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.writeString(p, text);
        return p;
    }

    @Test
    void anEditThatRedeclaresALocalIsANewError() throws IOException {
        write("src/main/java/a/A.java", """
                package a;
                class A {
                    String m(String id) {
                        String result = id.strip();
                        Runnable r = () -> System.out.println(result);
                        return result;
                    }
                }
                """);
        String after = """
                package a;
                class A {
                    String m(String id) {
                        String result = id.strip();
                        java.util.function.BiConsumer<String, Throwable> c = (result, error) -> { };
                        return result;
                    }
                }
                """;
        Map<String, String> errors = new CompileCheck(List.of()).newErrors(work, Map.of("src/main/java/a/A.java", after));
        assertThat(errors).containsKey("src/main/java/a/A.java");
        assertThat(errors.get("src/main/java/a/A.java")).contains("result");
    }

    @Test
    void errorsTheCodeAlreadyHadDoNotCount() throws IOException {
        // an unresolved type (a jar missing from the classpath) and a Lombok logger that is never generated here
        write("src/main/java/a/B.java", """
                package a;
                class B {
                    void m(missing.Client client) {
                        log.info("Call started");
                        client.call();
                    }
                }
                """);
        String after = """
                package a;
                class B {
                    void m(missing.Client client) {
                        log.info("Call started");
                        client.call();
                        log.info("Call completed");
                    }
                }
                """;
        assertThat(new CompileCheck(List.of()).newErrors(work, Map.of("src/main/java/a/B.java", after))).isEmpty();
    }

    /**
     * #284: javac stops before its flow analysis when attribution found errors, so on a partial classpath (an unresolved
     * type, ignored as environmental) an unreachable statement the edit adds went unnoticed.
     */
    @Test
    void anUnreachableLineIsAnErrorEvenWhenATypeIsMissing() throws IOException {
        write("src/main/java/a/D.java", """
                package a;
                class D {
                    String m(missing.Repo repo, String x) {
                        return repo.save(x).trim();
                    }
                }
                """);
        String after = """
                package a;
                class D {
                    String m(missing.Repo repo, String x) {
                        return repo.save(x).trim();
                        System.out.println("saved");
                    }
                }
                """;
        assertThat(new CompileCheck(List.of()).newErrors(work, Map.of("src/main/java/a/D.java", after)))
                .hasEntrySatisfying("src/main/java/a/D.java", e -> assertThat(e).contains("unreachable"));
    }

    @Test
    void otherSourcesOfTheProjectResolve() throws IOException {
        write("src/main/java/a/Helper.java", "package a;\npublic class Helper { public static String name() { return \"x\"; } }\n");
        write("src/main/java/a/C.java", "package a;\nclass C { }\n");
        String after = "package a;\nclass C { String n() { return Helper.name(); } }\n";
        assertThat(new CompileCheck(List.of()).newErrors(work, Map.of("src/main/java/a/C.java", after))).isEmpty();
    }

    /**
     * #305: javac runs annotation processors only on the files it is given, not on those it reads from the source path:
     * a fix that calls a Lombok getter of another class must not read as a new error.
     */
    @Test
    void aLombokGetterOfAnotherClassResolves() throws IOException {
        List<Path> classpath = java.util.Arrays.stream(System.getProperty("logast.test.lombok").split(java.io.File.pathSeparator))
                .map(Path::of).toList();
        write("src/main/java/a/ThingMoved.java", """
                package a;

                import lombok.Getter;

                @Getter
                public class ThingMoved {
                    private final String thingId;

                    public ThingMoved(String thingId) {
                        this.thingId = thingId;
                    }
                }
                """);
        String before = """
                package a;

                class Listener {
                    void on(ThingMoved event) {
                        System.out.println("moved");
                    }
                }
                """;
        write("src/main/java/a/Listener.java", before);
        String after = before.replace("System.out.println(\"moved\");", "System.out.println(event.getThingId());");
        assertThat(new CompileCheck(classpath).newErrors(work, Map.of("src/main/java/a/Listener.java", after)))
                .as("the getter Lombok generates on the source path").isEmpty();
        String typo = before.replace("System.out.println(\"moved\");", "System.out.println(event.getThingName());");
        assertThat(new CompileCheck(classpath).newErrors(work, Map.of("src/main/java/a/Listener.java", typo)))
                .as("a getter nothing generates is still an error").containsKey("src/main/java/a/Listener.java");
    }

    /** #320: a static import from a test library the main classpath lacks is the environment, not a new error. */
    @Test
    void aStaticImportFromAMissingLibraryIsNoError() throws IOException {
        write("src/main/java/a/Ledger.java", "package a;\n\npublic class Ledger {\n}\n");
        String test = """
                package a;

                import static java.util.concurrent.*;

                class LedgerTest {
                    void books() {
                        new Ledger();
                    }
                }
                """;
        assertThat(new CompileCheck(List.of()).newErrors(work, Map.of("src/test/java/a/LedgerTest.java", test)))
                .as("a class javac cannot find, read as a package: no verdict on it").isEmpty();
        // what javac reports for "import static org.mockito.Mockito.*" without mockito on the classpath
        assertThat(CompileCheck.environmental("compiler.err.static.imp.only.classes.and.interfaces",
                "static import only from classes and interfaces")).isTrue();
    }

    /** #305: a chain of Lombok getters resolves link by link: each pass finds the next class to compile with Lombok. */
    @Test
    void aLongChainOfLombokGettersResolves() throws IOException {
        List<Path> classpath = java.util.Arrays.stream(System.getProperty("logast.test.lombok").split(java.io.File.pathSeparator))
                .map(Path::of).toList();
        String[] names = {"Alert", "Shift", "Venue", "Region", "Tenant"};
        for (int i = 0; i < names.length; i++) {
            String field = i + 1 < names.length ? "private " + names[i + 1] + " " + names[i + 1].toLowerCase() + ";" : "private String code;";
            write("src/main/java/a/" + names[i] + ".java", "package a;\n\n@lombok.Getter\npublic class " + names[i] + " {\n    "
                    + field + "\n}\n");
        }
        String test = """
                package a;

                class AlertTest {
                    String code(Alert alert) {
                        return alert.getShift().getVenue().getRegion().getTenant().getCode();
                    }
                }
                """;
        assertThat(new CompileCheck(classpath).newErrors(work, Map.of("src/test/java/a/AlertTest.java", test)))
                .as("five Lombok classes, one per link").isEmpty();
    }

    /**
     * #305: a constructor Lombok generates (@RequiredArgsConstructor) on a class from the source path: the error names
     * it as "constructor X in class Y", and the class is compiled with Lombok too, so a test that calls it still compiles.
     */
    @Test
    void aLombokConstructorOfAnotherClassResolves() throws IOException {
        List<Path> classpath = java.util.Arrays.stream(System.getProperty("logast.test.lombok").split(java.io.File.pathSeparator))
                .map(Path::of).toList();
        write("src/main/java/a/Ledger.java", """
                package a;

                public class Ledger {
                }
                """);
        write("src/main/java/a/Recorder.java", """
                package a;

                import lombok.RequiredArgsConstructor;

                @RequiredArgsConstructor
                public class Recorder {
                    private final Ledger ledger;

                    public void record(String id) {
                        System.out.println(id + ledger);
                    }
                }
                """);
        String test = """
                package a;

                class RecorderTest {
                    void records() {
                        new Recorder(new Ledger()).record("o-1");
                    }
                }
                """;
        assertThat(new CompileCheck(classpath).newErrors(work, Map.of("src/test/java/a/RecorderTest.java", test)))
                .as("the constructor Lombok generates on the source path").isEmpty();
    }

    /**
     * #76: Lombok runs with the project's lombok.config, so a fix that logs through Lombok's default name where the
     * config renames the field is a new error, and the configured name is none. (A lower-case name: an unresolved
     * capitalised name like LOG reads as an unknown class and counts as environmental.)
     */
    @Test
    void lombokRunsWithTheProjectsLombokConfig() throws IOException {
        List<Path> classpath = java.util.Arrays.stream(System.getProperty("logast.test.lombok").split(java.io.File.pathSeparator))
                .map(Path::of).toList();
        write("src/main/java/a/lombok.config", "lombok.log.fieldName = logger\n");
        String before = """
                package a;

                import lombok.extern.slf4j.Slf4j;

                @Slf4j
                class D {
                    void m(Runnable step) {
                        try {
                            step.run();
                        } catch (IllegalStateException e) {
                        }
                    }
                }
                """;
        write("src/main/java/a/D.java", before);
        String wrongName = before.replace("catch (IllegalStateException e) {\n", "catch (IllegalStateException e) {\n log.warn(\"Step failed\", e);\n");
        String configuredName = wrongName.replace("log.warn", "logger.warn");

        Map<String, String> errors = new CompileCheck(classpath).newErrors(work, Map.of("src/main/java/a/D.java", wrongName));
        assertThat(errors).as("Lombok's default name where lombok.config renames the field").containsKey("src/main/java/a/D.java");
        assertThat(errors.get("src/main/java/a/D.java")).contains("variable log");
        CompileCheck check = new CompileCheck(classpath);
        assertThat(check.newErrors(work, Map.of("src/main/java/a/D.java", configuredName)))
                .as("the name lombok.config gives").isEmpty();
        assertThat(check.lombokProblem()).as("a working Lombok is trusted (#81)").isNull();
    }

    /**
     * #81: a Lombok jar on the classpath that does not work inside the gate. {@code @Slf4j} then creates no
     * {@code log}, so the first log line a fix adds must not count as a new error; the gate says so once.
     */
    @Test
    void lombokThatDoesNotRunInTheGateIsNotTrusted() throws IOException {
        Path broken = work.resolve("lombok-0.jar");
        try (var jar = new java.util.jar.JarOutputStream(Files.newOutputStream(broken))) {
            jar.putNextEntry(new java.util.jar.JarEntry("META-INF/MANIFEST.MF"));
            jar.write("Manifest-Version: 1.0\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            jar.closeEntry();
        }
        List<Path> classpath = new java.util.ArrayList<>(List.of(broken));
        java.util.Arrays.stream(System.getProperty("logast.test.lombok").split(java.io.File.pathSeparator)).map(Path::of)
                .filter(p -> !p.getFileName().toString().startsWith("lombok")).forEach(classpath::add);
        String before = """
                package a;

                @lombok.extern.slf4j.Slf4j
                class F {
                    void m(Runnable step) {
                        try {
                            step.run();
                        } catch (IllegalStateException e) {
                        }
                    }
                }
                """;
        write("src/main/java/a/F.java", before);
        String logged = before.replace("catch (IllegalStateException e) {\n", "catch (IllegalStateException e) {\n log.warn(\"Step failed\", e);\n");
        CompileCheck check = new CompileCheck(classpath);
        assertThat(check.newErrors(work, Map.of("src/main/java/a/F.java", logged)))
                .as("Lombok does not run in the gate: the logger it would create is no new error").isEmpty();
        assertThat(check.lombokProblem()).as("the gate says why it does not trust Lombok").isNotNull();
    }

    /**
     * #81: a classpath built for a newer Java than logast runs on (class file "has wrong version"): javac stops before
     * annotation processing, so Lombok's loggers are missing everywhere. Such a compile gives no verdict; the gate says
     * once why instead of rejecting every fix that adds a log line.
     */
    @Test
    void aClasspathForANewerJavaGivesNoVerdict() throws Exception {
        // a library class whose class file claims a Java version newer than the one running this test
        Path lib = Files.createDirectories(work.resolve("lib-src/lib"));
        Files.writeString(lib.resolve("Contract.java"), "package lib;\n\npublic class Contract {\n    public static int code() {\n        return 1;\n    }\n}\n");
        Path classes = Files.createDirectories(work.resolve("lib-classes"));
        assertThat(javax.tools.ToolProvider.getSystemJavaCompiler().run(null, null, null, "-d", classes.toString(),
                lib.resolve("Contract.java").toString())).isZero();
        Path cls = classes.resolve("lib/Contract.class");
        byte[] b = Files.readAllBytes(cls);
        int newer = Runtime.version().feature() + 44 + 4;   // one newer is only a warning; the service had four (69 on 65)
        b[6] = (byte) (newer >> 8);
        b[7] = (byte) newer;
        Files.write(cls, b);
        List<Path> classpath = new java.util.ArrayList<>(List.of(classes));
        java.util.Arrays.stream(System.getProperty("logast.test.lombok").split(java.io.File.pathSeparator)).map(Path::of)
                .forEach(classpath::add);
        // reached through another source file javac loads from the source path, as on the service
        write("src/main/java/a/Publisher.java", "package a;\n\nimport lib.Contract;\n\nclass Publisher {\n    int publish() {\n        return Contract.code();\n    }\n}\n");
        String before = """
                package a;

                import lombok.extern.slf4j.Slf4j;

                @Slf4j
                class J {
                    int m() {
                        return new Publisher().publish();
                    }
                }
                """;
        write("src/main/java/a/J.java", before);
        String logged = before.replace("return new Publisher().publish();", "log.info(\"Published\");\n        return new Publisher().publish();");
        CompileCheck check = new CompileCheck(classpath);
        assertThat(check.newErrors(work, Map.of("src/main/java/a/J.java", logged)))
                .as("no verdict from a compile that cannot read the classpath").isEmpty();
        assertThat(check.newErrors(work, Map.of("src/main/java/a/J.java", logged.replace("log.info", "zzz.info"))))
                .as("not even for a real error: javac cannot be trusted on this classpath").isEmpty();
        assertThat(check.problem()).as("the gate says why").contains("newer Java");
    }
}
