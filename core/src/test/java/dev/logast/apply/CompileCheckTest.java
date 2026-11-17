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

    @Test
    void otherSourcesOfTheProjectResolve() throws IOException {
        write("src/main/java/a/Helper.java", "package a;\npublic class Helper { public static String name() { return \"x\"; } }\n");
        write("src/main/java/a/C.java", "package a;\nclass C { }\n");
        String after = "package a;\nclass C { String n() { return Helper.name(); } }\n";
        assertThat(new CompileCheck(List.of()).newErrors(work, Map.of("src/main/java/a/C.java", after))).isEmpty();
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
        assertThat(new CompileCheck(classpath).newErrors(work, Map.of("src/main/java/a/D.java", configuredName)))
                .as("the name lombok.config gives").isEmpty();
    }
}
