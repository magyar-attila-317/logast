package dev.logast.export;

import dev.logast.facts.Edge;
import dev.logast.facts.FactSink;
import dev.logast.facts.Node;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** #162: the libraries a (multi-module) repo publishes. */
class GradleProjectsTest {
    @TempDir
    Path tmp;

    @Test
    void publishedModulesAreLibrariesTheRepoDefines() throws IOException {
        Path root = tmp.resolve("platform-libs");
        for (String m : List.of("app", "logging-starter", "test-support", "docs")) {
            Files.createDirectories(root.resolve(m));
        }
        Files.writeString(root.resolve("settings.gradle.kts"), """
                rootProject.name = "platform"
                include("app", "logging-starter")
                include(":test-support")
                include("docs")
                """);
        Files.writeString(root.resolve("gradle.properties"), "group=com.example.platform\nversion=3.2.0\n");
        Files.writeString(root.resolve("build.gradle.kts"), "plugins { base }\n");
        Files.writeString(root.resolve("app/build.gradle.kts"), "plugins { id(\"org.springframework.boot\") }\n");
        Files.writeString(root.resolve("logging-starter/build.gradle.kts"), "plugins {\n    `java-library`\n    `maven-publish`\n}\n");
        Files.writeString(root.resolve("test-support/build.gradle.kts"), """
                plugins { `java-library` }
                group = "com.example.testing"
                version = "1.0.1"
                base { archivesName.set("platform-test-support") }
                """);
        Files.writeString(root.resolve("docs/build.gradle.kts"), "plugins { `java-library` }\nversion = \"${rootProject.version}\"\n");

        List<Node> nodes = new ArrayList<>();
        List<Edge> edges = new ArrayList<>();
        GradleProjects.extract(root, "platform-libs", null, new FactSink() {
            @Override
            public void node(Node n) {
                nodes.add(n);
            }

            @Override
            public void edge(Edge e) {
                edges.add(e);
            }
        });
        assertThat(nodes).extracting(n -> n.id() + " " + n.attrs().get("version")).containsExactly(
                "repo:platform-libs null",
                "library:com.example.platform:logging-starter 3.2.0",
                "library:com.example.testing:platform-test-support 1.0.1",
                "library:com.example.platform:docs 3.2.0");
        assertThat(nodes).as("an application module publishes nothing").noneMatch(n -> n.id().endsWith(":app"));
        assertThat(edges).extracting(e -> e.from() + " -" + e.kind() + "-> " + e.to())
                .contains("repo:platform-libs -defines-> library:com.example.platform:logging-starter");
        assertThat(nodes.get(1).evidence().file()).isEqualTo("logging-starter/build.gradle.kts");
        assertThat(nodes.get(1).attrs()).containsEntry("module", ":logging-starter");
    }
}
