package dev.logast;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** #5: the Java version a service builds for, and the warning when logast runs on an older one. */
class JavaTargetTest {
    @TempDir
    Path tmp;

    private Path build(String name, String text) throws IOException {
        Path dir = Files.createDirectories(tmp.resolve(name));
        Files.writeString(dir.resolve(name.endsWith("groovy") ? "build.gradle" : "build.gradle.kts"), text);
        return dir;
    }

    @Test
    void readsTheVersionFromEveryUsualSpelling() throws IOException {
        assertThat(JavaTarget.of(build("toolchain", "java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }")))
                .hasValue(21);
        assertThat(JavaTarget.of(build("kotlin", "kotlin { jvmToolchain(25) }"))).hasValue(25);
        assertThat(JavaTarget.of(build("release", "tasks.withType<JavaCompile> { options.release.set(17) }"))).hasValue(17);
        assertThat(JavaTarget.of(build("groovy", "sourceCompatibility = JavaVersion.VERSION_21\n"))).hasValue(21);
        assertThat(JavaTarget.of(build("quoted-groovy", "sourceCompatibility = '17'\n"))).hasValue(17);
        assertThat(JavaTarget.of(build("none", "plugins { java }\n"))).isEmpty();
    }

    @Test
    void warnsOnlyWhenTheServiceIsNewerThanTheRunningJdk() throws IOException {
        int running = Runtime.version().feature();
        assertThat(JavaTarget.warning(build("newer", "kotlin { jvmToolchain(" + (running + 1) + ") }")))
                .startsWith("the service builds for Java " + (running + 1) + ", logast runs on Java " + running)
                .endsWith("run logast on Java " + (running + 1) + " or newer (25 recommended)");
        assertThat(JavaTarget.warning(build("same", "kotlin { jvmToolchain(" + running + ") }"))).isNull();
        assertThat(JavaTarget.warning(build("unknown", "plugins { java }\n"))).isNull();
    }
}
