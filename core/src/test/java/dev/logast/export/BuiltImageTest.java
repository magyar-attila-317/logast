package dev.logast.export;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** #161: the image a service's build pushes: what links its code to its deployments. */
class BuiltImageTest {
    @TempDir
    Path tmp;

    @Test
    void theImageABuildPushes() throws IOException {
        Files.writeString(tmp.resolve("build.gradle.kts"), """
                plugins { id("com.google.cloud.tools.jib") version "3.4.5" }
                jib {
                    from { image = "eclipse-temurin:21-jre" }
                    to {
                        image = "registry.example.com/shop/orders"
                        tags = setOf("latest")
                    }
                }
                """);
        assertThat(JavaExtractor.builtImage(tmp)).as("to, not from").isEqualTo("registry.example.com/shop/orders");
        Files.writeString(tmp.resolve("build.gradle.kts"), "tasks.bootBuildImage {\n    imageName.set(\"registry.example.com/shop/billing:1\")\n}\n");
        assertThat(JavaExtractor.builtImage(tmp)).isEqualTo("registry.example.com/shop/billing:1");
        Files.writeString(tmp.resolve("build.gradle.kts"), "jib { to { image = \"registry/${project.name}\" } }\n");
        assertThat(JavaExtractor.builtImage(tmp)).as("a computed name is not guessed").isNull();
    }
}
