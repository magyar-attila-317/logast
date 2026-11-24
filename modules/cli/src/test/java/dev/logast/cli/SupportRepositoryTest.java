package dev.logast.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** #295: support publish, run in a service, records where the starter is, so apply adds that repository. */
class SupportRepositoryTest {
    @Test
    void publishRecordsTheRepositoryInTheServicesPolicy(@TempDir Path dir) throws Exception {
        Path policy = dir.resolve("logast.yaml");
        assertThat(SupportCmd.recordRepository(policy, "https://maven.pkg.github.com/acme/shop")).as("no logast.yaml").isNull();
        Files.writeString(policy, "packs: [logging]\n");
        assertThat(SupportCmd.recordRepository(policy, "https://maven.pkg.github.com/acme/shop")).contains("support.repository");
        assertThat(Files.readString(policy)).contains("support:\n  mode: dependency\n  repository: https://maven.pkg.github.com/acme/shop\n");
        Files.writeString(policy, "support:\n  mode: dependency\n");
        SupportCmd.recordRepository(policy, "https://repo.example.org/maven");
        assertThat(Files.readString(policy)).isEqualTo("support:\n  repository: https://repo.example.org/maven\n  mode: dependency\n");
        assertThat(SupportCmd.recordRepository(policy, "https://other.example.org")).as("one is named: the team chose").isNull();
    }
}
