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
    void joinWritesTheFleetLineAndExtendsEntryAndKeepsTheRestOfLogastYaml() throws IOException {
        Path fleet = NamedFleet.create(new FleetHome.Env(null, Map.of(), tmp.resolve("home")), "payments-fleet", null);
        Path svc = Files.createDirectories(tmp.resolve("checkout"));
        Files.writeString(svc.resolve("logast.yaml"), "# checkout's policy\npacks: [logging]   # only this\n");
        NamedFleet.join(fleet, "payments-fleet", svc);
        String joined = "# checkout's policy\nfleet: payments-fleet\nextends:\n  - fleet:payments-fleet    # = "
                + fleet.resolve("company.yaml") + " on this machine\npacks: [logging]   # only this\n";
        assertThat(Files.readString(svc.resolve("logast.yaml"))).as("#133: the inheritance shows under extends:").isEqualTo(joined);
        NamedFleet.join(fleet, "payments-fleet", svc);
        assertThat(Files.readString(svc.resolve("logast.yaml"))).as("a second join changes nothing").isEqualTo(joined);
        assertThat(NamedFleet.of(svc)).isEqualTo("payments-fleet");
        assertThat(NamedFleet.members(fleet)).containsEntry("checkout", svc);

        NamedFleet.leave(fleet, svc);
        assertThat(Files.readString(svc.resolve("logast.yaml"))).isEqualTo("# checkout's policy\npacks: [logging]   # only this\n");
        assertThat(NamedFleet.members(fleet)).isEmpty();
    }

    /** #133: an existing extends: (scalar, flow or block list) gets the fleet entry first; leave takes only that out. */
    @Test
    void joinPutsTheFleetFirstInAnExistingExtendsAndLeaveTakesItOut() throws IOException {
        Path fleet = NamedFleet.create(new FleetHome.Env(null, Map.of(), tmp.resolve("home")), "shop", null);
        String item = "  - fleet:shop    # = " + fleet.resolve("company.yaml") + " on this machine\n";
        Map<String, String[]> cases = Map.of(
                "scalar", new String[]{"extends: ../central/company.yaml   # shared\npacks: [logging]\n",
                        "fleet: shop\nextends:   # shared\n" + item + "  - ../central/company.yaml\npacks: [logging]\n",
                        "extends: ../central/company.yaml   # shared\npacks: [logging]\n"},
                "flow", new String[]{"extends: [a.yaml, \"b.yaml\"]\n",
                        "fleet: shop\nextends:\n" + item + "  - a.yaml\n  - b.yaml\n",
                        "extends:\n  - a.yaml\n  - b.yaml\n"},
                "block", new String[]{"# head\nextends:\n    - a.yaml   # area\nlogging:\n  keyStyle: snake\n",
                        "# head\nfleet: shop\nextends:\n" + item.replaceFirst("^  ", "    ") + "    - a.yaml   # area\nlogging:\n  keyStyle: snake\n",
                        "# head\nextends:\n    - a.yaml   # area\nlogging:\n  keyStyle: snake\n"});
        for (var c : cases.entrySet()) {
            Path svc = Files.createDirectories(tmp.resolve(c.getKey()));
            Files.writeString(svc.resolve("logast.yaml"), c.getValue()[0]);
            NamedFleet.join(fleet, "shop", svc);
            assertThat(Files.readString(svc.resolve("logast.yaml"))).as(c.getKey()).isEqualTo(c.getValue()[1]);
            NamedFleet.join(fleet, "shop", svc);
            assertThat(Files.readString(svc.resolve("logast.yaml"))).as(c.getKey() + ": joined twice").isEqualTo(c.getValue()[1]);
            NamedFleet.leave(fleet, svc);
            String left = Files.readString(svc.resolve("logast.yaml"));
            assertThat(left).as(c.getKey() + ": left").isEqualTo(c.getValue()[2].replace("extends: ../central/company.yaml   # shared",
                    "extends:   # shared\n  - ../central/company.yaml"));
        }
        Path only = Files.createDirectories(tmp.resolve("only"));
        NamedFleet.join(fleet, "shop", only);
        NamedFleet.leave(fleet, only);
        assertThat(Files.readString(only.resolve("logast.yaml"))).as("an extends: left empty goes").isEmpty();
    }

    /** #133: scan and apply refresh the comment after `- fleet:<name>` when the fleet resolves elsewhere now. */
    @Test
    void theResolvedPathCommentIsRefreshed() throws IOException {
        System.setProperty(FleetHome.HOME_PROPERTY, tmp.resolve("home").toString());
        Path svc = Files.createDirectories(tmp.resolve("svc"));
        Path company = FleetExtends.company("shop");
        Files.writeString(svc.resolve("logast.yaml"), "fleet: shop\nextends:\n  - fleet:shop    # = /elsewhere/company.yaml on this machine\n"
                + "  - fleet:other   # mine\npacks: [logging]   # = not mine to touch\n");
        assertThat(FleetExtends.refresh(svc.resolve("logast.yaml"))).isTrue();
        String fresh = "fleet: shop\nextends:\n  - fleet:shop    # = " + company + " on this machine\n"
                + "  - fleet:other   # mine\npacks: [logging]   # = not mine to touch\n";
        assertThat(Files.readString(svc.resolve("logast.yaml"))).as("only that comment; a comment of the user's own stays").isEqualTo(fresh);
        assertThat(FleetExtends.refresh(svc.resolve("logast.yaml"))).as("unchanged: not written").isFalse();
    }

    /** #133: `extends: [fleet:<name>]` is the fleet's company.yaml; with `fleet:` too it is layered once. */
    @Test
    void aFleetReferenceInExtendsIsLayeredOnceAndAnUnknownOneWarns() throws IOException {
        System.setProperty(FleetHome.HOME_PROPERTY, tmp.resolve("home").toString());
        Path fleet = NamedFleet.create(FleetHome.Env.current(), "shop", null);
        Files.writeString(fleet.resolve("company.yaml"), "pii: [loyaltyCard]\nvocabulary:\n  payment:\n    mdc: { key: paymentId }\n");
        Files.writeString(tmp.resolve("area.yaml"), "pii: [tableNumber]\n");
        Path svc = Files.createDirectories(tmp.resolve("checkout"));
        Files.writeString(svc.resolve("logast.yaml"), "extends:\n  - fleet:shop\n  - ../area.yaml\n");
        Policy p = PolicyLoader.load(svc, null);
        assertThat(p.vocabulary()).as("extends: fleet:shop is the fleet's company.yaml").containsKey("payment");
        assertThat(p.warnings()).isEmpty();
        assertThat(PolicyLoader.vocabularyLayers(svc.resolve("logast.yaml")).parents()).containsExactly("company.yaml", "area.yaml");

        Files.writeString(svc.resolve("logast.yaml"), "fleet: shop\nextends:\n  - ../area.yaml\n  - fleet:shop\n");
        assertThat(PolicyLoader.vocabularyLayers(svc.resolve("logast.yaml")).parents()).as("layered once, before the other extends")
                .containsExactly("company.yaml", "area.yaml");
        assertThat(PolicyLoader.parentSettings(svc.resolve("logast.yaml"))).contains("vocabulary");
        assertThat(PolicyLoader.load(svc, null).pii()).containsSubsequence("loyaltyCard", "tableNumber");

        Files.writeString(svc.resolve("logast.yaml"), "extends: [fleet:unknown]\n");
        Policy unknown = PolicyLoader.load(svc, null);
        assertThat(unknown.vocabulary()).doesNotContainKey("payment");
        assertThat(unknown.warnings()).anyMatch(w -> w.contains("extends: fleet:unknown is not known on this machine"));
    }

    /** #133: join's tidy: the service's own copies of company.yaml's settings go, its overrides stay unless it follows. */
    @Test
    void tidyRemovesWhatCompanyYamlSetsAlreadyAndKeepsOverrides() throws IOException {
        System.setProperty(FleetHome.HOME_PROPERTY, tmp.resolve("home").toString());
        Path fleet = NamedFleet.create(FleetHome.Env.current(), "shop", null);
        Path company = fleet.resolve("company.yaml");
        Files.writeString(company, """
                packs: [logging, tracing]
                logging:
                  structuredFormat: ecs
                  keyStyle: camel
                vocabulary:
                  payment:
                    mdc: { key: paymentId }
                """);
        Path svc = Files.createDirectories(tmp.resolve("orders"));
        String own = """
                # orders' policy
                packs: [logging, tracing]   # the company's packs

                logging:
                  # how logs are written
                  structuredFormat: ecs
                support:
                  mode: vendor   # own choice
                metrics:
                  # the style below
                  keyStyle: x
                vocabulary:
                  payment:
                    mdc:
                      key: paymentId
                  order:
                    mdc:
                      key: orderId
                """;
        Files.writeString(svc.resolve("logast.yaml"), own.replace("structuredFormat: ecs", "structuredFormat: ecs\n  keyStyle: snake"));
        FleetTidy.Plan plan = FleetTidy.plan(svc, company);
        assertThat(plan.same()).extracting(FleetTidy.Setting::path).containsExactly("logging.structuredFormat", "packs");
        assertThat(plan.overrides()).containsExactly(new FleetTidy.Setting("logging.keyStyle", "snake", "camel"));
        assertThat(plan.overviewLine("orders", "shop")).isEqualTo("orders: 2 settings company.yaml already sets "
                + "(logging.structuredFormat, packs) · 1 vocabulary entry company.yaml defines (payment) · 1 own override "
                + "(logging.keyStyle: snake) → `logast fleet tidy shop`");

        FleetTidy.Result r = FleetTidy.apply(svc, company, false);
        assertThat(r.removed()).containsExactly("logging.structuredFormat: ecs (company.yaml sets the same)",
                "packs: [logging, tracing] (company.yaml sets the same)", "vocabulary payment (company.yaml defines it)");
        assertThat(r.kept()).extracting(FleetTidy.Setting::path).containsExactly("logging.keyStyle");
        String tidied = """
                # orders' policy

                logging:
                  # how logs are written
                  keyStyle: snake
                support:
                  mode: vendor   # own choice
                metrics:
                  # the style below
                  keyStyle: x
                vocabulary:
                  order:
                    mdc:
                      key: orderId
                """;
        assertThat(Files.readString(svc.resolve("logast.yaml"))).as("only those lines; comments elsewhere stay").isEqualTo(tidied);
        assertThat(FleetTidy.apply(svc, company, false).removed()).as("a second tidy removes nothing").isEmpty();

        FleetTidy.Result follow = FleetTidy.apply(svc, company, true);
        assertThat(follow.removed()).containsExactly("logging.keyStyle: snake (company: camel)");
        assertThat(follow.kept()).isEmpty();
        assertThat(Files.readString(svc.resolve("logast.yaml"))).as("the emptied logging: goes, with what stood in it")
                .isEqualTo(tidied.replace("logging:\n  # how logs are written\n  keyStyle: snake\n", ""));
    }

    /** #132: an added member is registered apart from the joined ones; joining moves it, remove takes only added ones. */
    @Test
    void addRegistersAMemberWithoutTouchingItsRepoAndJoinMovesIt() throws IOException {
        Path fleet = NamedFleet.create(new FleetHome.Env(null, Map.of(), tmp.resolve("home")), "shop", null);
        Path orders = Files.createDirectories(tmp.resolve("orders"));
        Path stock = Files.createDirectories(tmp.resolve("stock"));
        assertThat(NamedFleet.add(fleet, orders)).isEqualTo(NamedFleet.Added.ADDED);
        assertThat(NamedFleet.add(fleet, orders)).isEqualTo(NamedFleet.Added.AGAIN);
        assertThat(NamedFleet.add(fleet, stock)).isEqualTo(NamedFleet.Added.ADDED);
        assertThat(orders.resolve("logast.yaml")).as("nothing written into the repo").doesNotExist();
        assertThat(NamedFleet.added(fleet)).containsOnlyKeys("orders", "stock");
        assertThat(NamedFleet.joined(fleet)).isEmpty();
        assertThat(NamedFleet.members(fleet)).containsEntry("orders", orders).containsEntry("stock", stock);

        NamedFleet.join(fleet, "shop", stock);
        assertThat(NamedFleet.added(fleet)).containsOnlyKeys("orders");
        assertThat(NamedFleet.joined(fleet)).containsOnlyKeys("stock");
        assertThat(NamedFleet.add(fleet, stock)).as("a joined member stays joined").isEqualTo(NamedFleet.Added.JOINED_ALREADY);
        assertThat(NamedFleet.remove(fleet, "stock")).as("joined: `fleet leave`, not remove").isNull();

        Files.createDirectories(NamedFleet.outputDir(fleet, "orders").resolve("reports"));
        Files.writeString(NamedFleet.outputDir(fleet, "orders").resolve("reports/orders.json"), "{}");
        assertThat(NamedFleet.remove(fleet, orders.toString())).as("by root").isEqualTo("orders");
        assertThat(NamedFleet.outputDir(fleet, "orders")).doesNotExist();
        assertThat(NamedFleet.members(fleet)).containsOnlyKeys("stock");
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
