package dev.logast.export;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.DockerClientFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** #222: a Node.js repo's TypeORM migrations (SQL in queryRunner.query) give its tables, citing the .ts file and line. */
class TypeOrmMigrationsTest {
    @TempDir
    Path tmp;

    private Path repo() throws IOException {
        Path root = tmp.resolve("cache-svc");
        Path mig = Files.createDirectories(root.resolve("src/shared/db/migrations"));
        Files.writeString(root.resolve("package.json"), """
                {"name": "@acme/cache-svc", "dependencies": {"typeorm": "0.3.20", "pg": "8.11.0"}}
                """);
        Files.writeString(mig.resolve("1700000000000-ProductCache.ts"), """
                import { MigrationInterface, QueryRunner } from 'typeorm';

                export class ProductCacheInit1700000000000 implements MigrationInterface {
                    public async up(queryRunner: QueryRunner): Promise<void> {
                        await queryRunner.query(
                            `CREATE TABLE IF NOT EXISTS cache.products (
                                unique_key   TEXT NOT NULL,
                                gtin         TEXT,
                                division_no  INTEGER,
                                country_code TEXT,
                                PRIMARY KEY (unique_key, country_code)
                            )`
                        );
                        await queryRunner.query('CREATE INDEX IF NOT EXISTS idx_product_gtin ON cache.products (gtin)');
                    }

                    public async down(queryRunner: QueryRunner): Promise<void> {
                        await queryRunner.query(`DROP TABLE cache.products`);
                    }
                }
                """);
        Files.writeString(mig.resolve("1710000000000-ProductBrand.ts"), """
                import { MigrationInterface, QueryRunner } from 'typeorm';

                export class ProductBrand1710000000000 implements MigrationInterface {
                    public async up(queryRunner: QueryRunner): Promise<void> {
                        await queryRunner.query(`ALTER TABLE cache.products ADD COLUMN IF NOT EXISTS brand TEXT`);
                    }

                    public async down(queryRunner: QueryRunner): Promise<void> {
                        await queryRunner.query(`ALTER TABLE cache.products DROP COLUMN brand`);
                    }
                }
                """);
        Files.createDirectories(root.resolve("node_modules/typeorm/migrations"));
        Files.writeString(root.resolve("node_modules/typeorm/migrations/1-Ignored.ts"),
                "class X implements MigrationInterface { async up(q) { await q.query(`CREATE TABLE cache.not_ours (x int)`); } }");
        return root;
    }

    @Test
    void theSqlOfUpIsReadInOrderOnItsLines() throws IOException {
        List<TypeOrmMigrations.Migration> m = TypeOrmMigrations.find(repo());
        assertThat(m).extracting(TypeOrmMigrations.Migration::rel).containsExactly(
                "src/shared/db/migrations/1700000000000-ProductCache.ts", "src/shared/db/migrations/1710000000000-ProductBrand.ts");
        assertThat(m.getFirst().statements()).extracting(TypeOrmMigrations.Statement::line).containsExactly(6, 14);
        assertThat(m.getFirst().statements().getFirst().sql()).startsWith("CREATE TABLE IF NOT EXISTS cache.products (").doesNotContain("DROP");
        assertThat(TypeOrmMigrations.serviceName(tmp.resolve("cache-svc"))).isEqualTo("cache-svc");
    }

    @Test
    void itsTablesAreFactsCitingTheMigration() throws IOException {
        boolean docker;
        try {
            docker = DockerClientFactory.instance().isDockerAvailable();
        } catch (RuntimeException e) {
            docker = false;
        }
        assumeTrue(docker, "needs Docker");
        Path root = repo();
        Path out = tmp.resolve("out");
        RepoExport.Result r = RepoExport.export(root, "cache-svc", "test",
                new RepoExport.Options(x -> List.of(), new Export.FlywayOptions(List.of(), null), List.of(), List.of()), out);
        assertThat(r.problems()).isEmpty();
        String facts = String.join("\n", Files.readAllLines(r.files().getFirst()));
        assertThat(facts).contains("\"id\":\"service:cache-svc\"", "\"runtime\":\"node\"");
        String table = facts.lines().filter(l -> l.contains("\"id\":\"table:cache.products\"")).findFirst().orElseThrow();
        assertThat(table).as(facts).contains("unique_key text", "division_no integer", "brand text",
                "\"file\":\"src/shared/db/migrations/1700000000000-ProductCache.ts\",\"line\":6");
        assertThat(facts).contains("\"from\":\"service:cache-svc\",\"kind\":\"has\",\"to\":\"table:cache.products\"")
                .doesNotContain("not_ours");
    }
}
