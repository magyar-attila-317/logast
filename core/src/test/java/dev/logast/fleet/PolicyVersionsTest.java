package dev.logast.fleet;

import dev.logast.Git;
import dev.logast.policy.Policy;
import dev.logast.policy.PolicyLoader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** #236: policy versions are tags of the fleet repo; a member pinned to one applies that version on every machine. */
class PolicyVersionsTest {
    @TempDir
    Path tmp;

    @AfterEach
    void reset() {
        System.clearProperty(FleetHome.HOME_PROPERTY);
    }

    static void git(Path dir, String... args) {
        Git.must(dir, args);
    }

    static Path fleetRepo(Path tmp) throws IOException {
        System.setProperty(FleetHome.HOME_PROPERTY, tmp.resolve("home").toString());
        Path fleet = NamedFleet.create(FleetHome.Env.current(), "shop", null);
        git(fleet, "init", "--quiet", "--initial-branch=main");
        git(fleet, "config", "user.email", "fleet@example.com");
        git(fleet, "config", "user.name", "Fleet");
        git(fleet, "config", "tag.gpgSign", "false");
        git(fleet, "config", "commit.gpgSign", "false");
        return fleet;
    }

    static void commit(Path fleet, String company) throws IOException {
        Files.writeString(fleet.resolve("company.yaml"), company);
        git(fleet, "add", "-A");
        git(fleet, "commit", "--quiet", "-m", "policy");
    }

    @Test
    void everyChangeOfCompanyYamlIsTheNextTagAndAnUnchangedOneIsNot() throws IOException {
        Path fleet = fleetRepo(tmp);
        commit(fleet, "logging:\n  keyStyle: snake\n");
        assertThat(PolicyVersions.tag(fleet)).isEqualTo("policy-1");
        assertThat(PolicyVersions.tag(fleet)).as("nothing changed: the same version").isEqualTo("policy-1");
        commit(fleet, "logging:\n  keyStyle: camel\n");
        assertThat(PolicyVersions.tag(fleet)).isEqualTo("policy-2");
        assertThat(PolicyVersions.tags(fleet)).containsExactly("policy-1", "policy-2");

        Files.writeString(fleet.resolve("company.yaml"), "logging:\n  keyStyle: dotted\n");
        assertThatThrownBy(() -> PolicyVersions.tag(fleet)).as("uncommitted: no version").hasMessageContaining("uncommitted");
    }

    @Test
    void aPinnedMemberAppliesItsVersionNotTheFleetFoldersCurrentFile() throws IOException {
        Path fleet = fleetRepo(tmp);
        commit(fleet, "logging:\n  keyStyle: snake\n");
        PolicyVersions.tag(fleet);
        commit(fleet, "logging:\n  keyStyle: camel\n");
        PolicyVersions.tag(fleet);
        Path svc = Files.createDirectories(tmp.resolve("checkout"));

        Files.writeString(svc.resolve("logast.yaml"), String.join("\n", FleetExtends.pinTo(List.of("fleet: shop"), "shop", "policy-1")) + "\n");
        assertThat(Files.readString(svc.resolve("logast.yaml"))).contains("- fleet:shop@policy-1");
        Policy p = PolicyLoader.load(svc, null);
        assertThat(p.logging().keyStyle()).as("policy-1, though the fleet folder holds policy-2").isEqualTo("snake");
        assertThat(p.warnings()).isEmpty();

        Files.writeString(svc.resolve("logast.yaml"), String.join("\n", FleetExtends.pinTo(Files.readAllLines(svc.resolve("logast.yaml")),
                "shop", "policy-2")) + "\n");
        assertThat(Files.readString(svc.resolve("logast.yaml"))).as("the pin moves; one entry").contains("- fleet:shop@policy-2")
                .doesNotContain("policy-1");
        assertThat(PolicyLoader.load(svc, null).logging().keyStyle()).isEqualTo("camel");

        Files.writeString(svc.resolve("logast.yaml"), "fleet: shop\nextends:\n  - fleet:shop@policy-9\n");
        assertThat(PolicyLoader.load(svc, null).warnings()).as("a version this machine cannot read is said, never replaced")
                .anyMatch(w -> w.contains("fleet:shop@policy-9"));
    }
}
