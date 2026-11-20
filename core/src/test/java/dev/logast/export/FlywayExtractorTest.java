package dev.logast.export;

import dev.logast.facts.Edge;
import dev.logast.facts.FactSink;
import dev.logast.facts.Node;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.DockerClientFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** #157: the tables and rows a service's migrations create, per environment, by running them in PostgreSQL. */
class FlywayExtractorTest {
    @TempDir
    Path tmp;

    @BeforeAll
    static void docker() {
        boolean available;
        try {
            available = DockerClientFactory.instance().isDockerAvailable();
        } catch (RuntimeException e) {
            available = false;
        }
        assumeTrue(available, "needs Docker (not on the macOS CI runners)");
    }

    private static final class Collect implements FactSink {
        final List<Node> nodes = new ArrayList<>();
        final List<Edge> edges = new ArrayList<>();

        @Override
        public void node(Node n) {
            nodes.add(n);
        }

        @Override
        public void edge(Edge e) {
            edges.add(e);
        }

        Node node(String id, String env) {
            return nodes.stream().filter(n -> n.id().equals(id) && java.util.Objects.equals(n.env(), env)).findFirst().orElse(null);
        }
    }

    private Path service(String baseConfig) throws IOException {
        Path root = tmp.resolve("scheduler");
        Path res = root.resolve("src/main/resources");
        Files.createDirectories(root.resolve("src/main/java/com/example"));
        Files.writeString(root.resolve("src/main/java/com/example/App.java"),
                "package com.example;\n\n@org.springframework.boot.autoconfigure.SpringBootApplication\npublic class App {\n}\n");
        Files.createDirectories(res.resolve("db/migration/common"));
        Files.createDirectories(res.resolve("db/migration/dev"));
        Files.createDirectories(res.resolve("db/migration/prod"));
        Files.writeString(res.resolve("application.yml"), baseConfig);
        Files.writeString(res.resolve("application-prod.yml"), "spring:\n  flyway:\n    placeholders:\n      batch_size: \"500\"\n");
        Files.writeString(res.resolve("db/migration/common/V1__jobs.sql"), """
                create table ${schema}.job_definition (
                    name varchar(100) primary key,
                    service varchar(100) not null,
                    profile varchar(100) not null,
                    cron varchar(50) not null
                );
                insert into ${schema}.job_definition values ('nightly-export', 'shipping', 'export', '0 2 * * *');
                insert into ${schema}.job_definition values ('settlement', 'billing', 'tally', '30 5 * * *');
                create table audit_event (id bigserial primary key, kind text, size int default ${batch_size});
                """);
        Files.writeString(res.resolve("db/migration/common/R__views.sql"),
                "create or replace view ${schema}.daily_jobs as select name, cron from ${schema}.job_definition;\n");
        Files.writeString(res.resolve("db/migration/dev/V2__dev.sql"),
                "update ${schema}.job_definition set cron = '*/10 * * * *' where name = 'nightly-export';\n");
        Files.writeString(res.resolve("db/migration/prod/V2__prod.sql"),
                "insert into ${schema}.job_definition values ('month-end', 'billing', 'month-end', '0 3 1 * *');\n");
        return root;
    }

    @Test
    void eachEnvironmentGetsItsTablesAndTheRowsAskedFor() throws IOException {
        Path root = service("spring:\n  flyway:\n    placeholders:\n      schema: orchestration\n      batch_size: \"100\"\n");
        assertThat(FlywayExtractor.runs(root)).extracting(FlywayExtractor.Run::env).containsExactly("dev", "prod");

        Collect c = new Collect();
        FlywayExtractor fx = new FlywayExtractor("scheduler", List.of("orchestration.job_definition"), null);
        fx.extract(root, c);

        assertThat(fx.problems()).isEmpty();
        Node table = c.node("table:orchestration.job_definition", "prod");
        assertThat(table).as("the placeholder's schema, per environment").isNotNull();
        assertThat(table.evidence().file()).isEqualTo("src/main/resources/db/migration/common/V1__jobs.sql");
        assertThat(table.evidence().line()).isEqualTo(1);
        assertThat(table.attrs().get("columns")).asList().contains("cron character varying");
        assertThat(c.node("table:public.audit_event", "dev").evidence().line()).isEqualTo(9);
        assertThat(c.nodes).as("views are not tables").noneMatch(n -> n.id().contains("daily_jobs"));
        assertThat(c.nodes).as("flyway's own history is left out").noneMatch(n -> n.id().contains("flyway_schema_history"));

        assertThat(c.node("row:orchestration.job_definition:nightly-export", "dev").attrs())
                .as("dev's update over common's insert").containsEntry("cron", "*/10 * * * *").containsEntry("profile", "export");
        assertThat(c.node("row:orchestration.job_definition:nightly-export", "prod").attrs()).containsEntry("cron", "0 2 * * *");
        assertThat(c.node("row:orchestration.job_definition:month-end", "prod")).as("prod's own row").isNotNull();
        assertThat(c.node("row:orchestration.job_definition:month-end", "dev")).isNull();
        assertThat(c.nodes).as("rows only of the tables asked for").noneMatch(n -> n.id().startsWith("row:public.audit_event"));
        assertThat(c.edges).extracting(e -> e.from() + " -" + e.kind() + "-> " + e.to() + " [" + e.env() + "]")
                .contains("service:scheduler -has-> table:orchestration.job_definition [prod]",
                        "table:orchestration.job_definition -has-> row:orchestration.job_definition:month-end [prod]");
    }

    @Test
    void anEnvironmentWhoseMigrationsFailIsReportedAndTheOthersStillCount() throws IOException {
        Path root = service("spring:\n  flyway:\n    placeholders:\n      schema: orchestration\n");   // no batch_size outside prod
        Collect c = new Collect();
        FlywayExtractor fx = new FlywayExtractor("scheduler", List.of(), null);
        fx.extract(root, c);

        assertThat(fx.problems()).singleElement().asString()
                .startsWith("dev: ").contains("V1__jobs.sql", "placeholders without a value, run with their own name: batch_size");
        assertThat(c.node("table:orchestration.job_definition", "prod")).as("prod has the value").isNotNull();
        assertThat(c.node("table:orchestration.job_definition", "dev")).isNull();
        assertThat(c.nodes).allSatisfy(n -> assertThat(n.attrs()).doesNotContainKey("placeholdersWithoutValue"));
    }

    /** #179: migrations the service's code runs itself, into each tenant's schema, beside Spring Boot's. */
    @Test
    void migrationsTheCodeRunsPerTenant() throws IOException {
        Path root = tmp.resolve("multitenant");
        Path res = root.resolve("src/main/resources");
        Files.createDirectories(res.resolve("db/migration/global"));
        Files.createDirectories(res.resolve("db/migration/tenant"));
        Files.writeString(res.resolve("application.yaml"), "spring:\n  flyway:\n    locations: classpath:db/migration/global\n");
        Files.writeString(res.resolve("db/migration/global/V1__tenants.sql"), "create table tenants (id bigint primary key, schema_name text);\n");
        Files.writeString(res.resolve("db/migration/tenant/V1__orders.sql"), "create table orders (id bigint primary key);\n");
        Files.writeString(res.resolve("db/migration/tenant/V2__items.sql"), "create table order_items (id bigint primary key, order_id bigint);\n");
        Path pkg = Files.createDirectories(root.resolve("src/main/java/com/example/tenancy"));
        Files.writeString(pkg.resolve("App.java"), "package com.example.tenancy;\n\n@org.springframework.boot.autoconfigure.SpringBootApplication\npublic class App {\n}\n");
        Files.writeString(pkg.resolve("TenantMigrations.java"), """
                package com.example.tenancy;

                import javax.sql.DataSource;
                import org.flywaydb.core.Flyway;

                class TenantMigrations {
                    static final String LOCATION = "classpath:db/migration/tenant";

                    void migrate(DataSource dataSource, String schemaName) {
                        Flyway.configure()
                                .dataSource(dataSource)
                                .defaultSchema(schemaName)
                                .schemas(schemaName)
                                .locations(LOCATION)
                                .load()
                                .migrate();
                    }
                }
                """);
        List<Path> cp = new ArrayList<>(dev.logast.Fixtures.classpath());
        for (String e : System.getProperty("java.class.path").split(java.io.File.pathSeparator)) {
            if (e.contains("flyway-core")) {
                cp.add(Path.of(e));
            }
        }
        dev.logast.Analysis a = dev.logast.Logast.analyse(root, cp, dev.logast.policy.Policy.defaults());
        List<ProgrammaticFlyway.CodeRun> code = ProgrammaticFlyway.find(a.index);
        assertThat(code).singleElement().satisfies(r -> {
            assertThat(r.location()).isEqualTo("classpath:db/migration/tenant");
            assertThat(r.schema()).as("chosen at runtime").isNull();
            assertThat(r.file()).endsWith("TenantMigrations.java");
        });

        Collect c = new Collect();
        FlywayExtractor fx = new FlywayExtractor("tenancy", List.of(), null).withCodeRuns(code);
        fx.extract(root, c);
        assertThat(fx.problems()).isEmpty();
        assertThat(c.nodes).extracting(Node::id).contains("table:public.tenants", "table:tenant.orders", "table:tenant.order_items");
        Node orders = c.node("table:tenant.orders", null);
        assertThat(orders.attrs()).containsEntry("perTenant", true).hasEntrySatisfying("migratedBy", v -> assertThat(v.toString()).contains("TenantMigrations.java"));
        assertThat(c.node("table:public.tenants", null).attrs()).doesNotContainKey("perTenant");
    }

    /** #180: the locations set per profile (common, plus the environment's folder) give a run per environment. */
    @Test
    void locationsSetPerProfileGiveARunPerEnvironment() throws IOException {
        Path root = tmp.resolve("scheduler2");
        Path res = root.resolve("src/main/resources");
        for (String d : List.of("common", "dev", "prod")) {
            Files.createDirectories(res.resolve("db/migration/" + d));
        }
        Files.createDirectories(root.resolve("src/main/java/com/example"));
        Files.writeString(root.resolve("src/main/java/com/example/App.java"),
                "package com.example;\n\n@org.springframework.boot.autoconfigure.SpringBootApplication\npublic class App {\n}\n");
        Files.writeString(res.resolve("application.yml"), "spring:\n  flyway:\n    locations: classpath:db/migration/common\n");
        Files.writeString(res.resolve("application-dev.yml"),
                "spring:\n  flyway:\n    locations: classpath:db/migration/common,classpath:db/migration/dev\n");
        Files.writeString(res.resolve("application-prod.yml"),
                "spring:\n  flyway:\n    locations: classpath:db/migration/common, classpath:db/migration/prod\n");
        Files.writeString(res.resolve("application-local.yml"), "server:\n  port: 8081\n");
        Files.writeString(res.resolve("db/migration/common/V1__jobs.sql"),
                "create table job_definition (name varchar(100) primary key, cron varchar(50));\n"
                        + "insert into job_definition values ('export', '0 2 * * *');\n");
        Files.writeString(res.resolve("db/migration/dev/V2__dev.sql"), "update job_definition set cron = '*/5 * * * *';\n");
        Files.writeString(res.resolve("db/migration/prod/V2__prod.sql"), "insert into job_definition values ('month-end', '0 3 1 * *');\n");

        assertThat(FlywayExtractor.runs(root)).extracting(r -> String.valueOf(r.env()))
                .as("the base locations (no profile), then each profile with its own; a profile without its own locations is none")
                .containsExactly("null", "dev", "prod");
        Collect c = new Collect();
        FlywayExtractor fx = new FlywayExtractor("scheduler", List.of("job_definition"), null);
        fx.extract(root, c);
        assertThat(fx.problems()).isEmpty();
        assertThat(c.node("row:public.job_definition:export", "dev").attrs()).containsEntry("cron", "*/5 * * * *");
        assertThat(c.node("row:public.job_definition:export", "prod").attrs()).containsEntry("cron", "0 2 * * *");
        assertThat(c.node("row:public.job_definition:month-end", "prod")).isNotNull();
        assertThat(c.nodes).noneMatch(n -> n.id().equals("row:public.job_definition:month-end") && "dev".equals(n.env()));
        assertThat(c.node("row:public.job_definition:export", null).attrs()).as("common alone, as without a profile").containsEntry("cron", "0 2 * * *");
    }
}
