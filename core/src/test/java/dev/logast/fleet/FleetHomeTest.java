package dev.logast.fleet;

import dev.logast.policy.Policy;
import dev.logast.policy.PolicyLoader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** #124: where named fleets live, membership, and a member's policy extending its fleet's company.yaml. */
class FleetHomeTest {
    @TempDir
    Path tmp;

    @AfterEach
    void clear() {
        System.clearProperty(FleetHome.HOME_PROPERTY);
        System.clearProperty(FleetHome.DIR_PROPERTY);
    }

    @Test
    void theFleetsFolderComesFromTheOptionThenTheEnvironmentThenTheUserConfigThenTheDefault() throws IOException {
        Path home = tmp.resolve("home");
        FleetHome.Env none = new FleetHome.Env(null, Map.of(), home);
        assertThat(FleetHome.fleetsDir(none)).isEqualTo(new FleetHome.Location(home.resolve(".local/share/logast/fleets"), "default"));

        Files.createDirectories(home.resolve(".config/logast"));
        Files.writeString(home.resolve(".config/logast/config.yaml"), "fleets:\n  dir: ~/sync/fleets\n");
        assertThat(FleetHome.fleetsDir(none).dir()).as("the user config, ~ expanded").isEqualTo(home.resolve("sync/fleets"));
        assertThat(FleetHome.fleetsDir(none).source()).startsWith("fleets.dir in ");

        FleetHome.Env env = new FleetHome.Env(null, Map.of(FleetHome.ENV, tmp.resolve("from-env").toString()), home);
        assertThat(FleetHome.fleetsDir(env)).as("the environment before the user config")
                .isEqualTo(new FleetHome.Location(tmp.resolve("from-env"), FleetHome.ENV));

        FleetHome.Env option = new FleetHome.Env(tmp.resolve("from-option").toString(), env.env(), home);
        assertThat(FleetHome.fleetsDir(option)).as("the option before everything")
                .isEqualTo(new FleetHome.Location(tmp.resolve("from-option"), "--fleets-dir"));
        assertThat(FleetHome.fleetDir(option, "payments-fleet").dir()).isEqualTo(tmp.resolve("from-option/payments-fleet"));
    }

    @Test
    void xdgFoldersAreRespected() throws IOException {
        Path home = tmp.resolve("home");
        Map<String, String> xdg = Map.of("XDG_CONFIG_HOME", tmp.resolve("cfg").toString(), "XDG_DATA_HOME", tmp.resolve("data").toString());
        FleetHome.Env env = new FleetHome.Env(null, xdg, home);
        assertThat(env.configFile()).isEqualTo(tmp.resolve("cfg/logast/config.yaml"));
        assertThat(FleetHome.fleetsDir(env).dir()).isEqualTo(tmp.resolve("data/logast/fleets"));
        Files.createDirectories(tmp.resolve("cfg/logast"));
        Files.writeString(tmp.resolve("cfg/logast/config.yaml"), "fleets: { dir: " + tmp.resolve("configured") + " }\n");
        assertThat(FleetHome.fleetsDir(env).dir()).isEqualTo(tmp.resolve("configured"));
    }

    @Test
    void aFleetCreatedInItsOwnFolderIsRememberedAndFoundByName() throws IOException {
        Path home = tmp.resolve("home");
        FleetHome.Env env = new FleetHome.Env(null, Map.of(), home);
        Files.createDirectories(home.resolve(".config/logast"));
        Files.writeString(home.resolve(".config/logast/config.yaml"), "other: kept\n");
        Path synced = tmp.resolve("synced/payments-fleet");
        assertThat(NamedFleet.create(env, "payments-fleet", synced)).isEqualTo(synced);
        assertThat(FleetHome.fleetDir(env, "payments-fleet").dir()).as("remembered, before the fleets folder").isEqualTo(synced);
        assertThat(Files.readString(env.configFile())).contains("other: kept", "payments-fleet: " + synced);
        assertThat(FleetHome.fleetDir(env, "billing-fleet").dir()).isEqualTo(home.resolve(".local/share/logast/fleets/billing-fleet"));

        NamedFleet.create(env, "billing-fleet", null);
        assertThat(NamedFleet.list(env)).containsOnlyKeys("billing-fleet", "payments-fleet");
        assertThat(FleetHome.remembered(env)).as("a fleet in the fleets folder needs no entry").containsOnlyKeys("payments-fleet");

        // --fleets-dir for one run: the next run would not find it there, so it is remembered too
        NamedFleet.create(new FleetHome.Env(tmp.resolve("once").toString(), Map.of(), home), "audit-fleet", null);
        assertThat(FleetHome.fleetDir(env, "audit-fleet").dir()).isEqualTo(tmp.resolve("once/audit-fleet"));
    }

    @Test
    void joinWritesOnlyTheFleetLineAndKeepsTheRestOfLogastYaml() throws IOException {
        Path fleet = NamedFleet.create(new FleetHome.Env(null, Map.of(), tmp.resolve("home")), "payments-fleet", null);
        Path svc = Files.createDirectories(tmp.resolve("checkout"));
        Files.writeString(svc.resolve("logast.yaml"), "# checkout's policy\npacks: [logging]   # only this\n");
        NamedFleet.join(fleet, "payments-fleet", svc);
        assertThat(Files.readString(svc.resolve("logast.yaml")))
                .isEqualTo("# checkout's policy\nfleet: payments-fleet\npacks: [logging]   # only this\n");
        assertThat(NamedFleet.of(svc)).isEqualTo("payments-fleet");
        assertThat(NamedFleet.members(fleet)).containsEntry("checkout", svc);

        NamedFleet.leave(fleet, svc);
        assertThat(Files.readString(svc.resolve("logast.yaml"))).isEqualTo("# checkout's policy\npacks: [logging]   # only this\n");
        assertThat(NamedFleet.members(fleet)).isEmpty();
    }

    @Test
    void aMembersPolicyExtendsTheFleetsCompanyYamlFoundByName() throws IOException {
        System.setProperty(FleetHome.HOME_PROPERTY, tmp.resolve("home").toString());
        Path fleet = NamedFleet.create(FleetHome.Env.current(), "payments-fleet", null);
        Files.writeString(fleet.resolve("company.yaml"), """
                vocabulary:
                  payment:
                    mdc: { key: paymentId, aliases: [payment_id] }
                """);
        Path svc = Files.createDirectories(tmp.resolve("checkout"));
        Files.writeString(svc.resolve("logast.yaml"), "fleet: payments-fleet\n");
        Policy p = PolicyLoader.load(svc, null);
        assertThat(p.vocabulary()).as("the fleet's company.yaml is a layer").containsKey("payment");
        assertThat(p.warnings()).isEmpty();
        assertThat(PolicyLoader.vocabularyLayers(svc.resolve("logast.yaml")).parents()).containsExactly("company.yaml");

        Files.writeString(svc.resolve("logast.yaml"), "fleet: unknown-fleet\n");
        Policy unknown = PolicyLoader.load(svc, null);
        assertThat(unknown.vocabulary()).doesNotContainKey("payment");
        assertThat(unknown.warnings()).anyMatch(w -> w.contains("fleet: unknown-fleet is not known on this machine"));
    }
}
