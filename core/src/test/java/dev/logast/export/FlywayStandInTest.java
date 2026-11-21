package dev.logast.export;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** #205: which other service's table a migration's missing relation means (no Docker needed). */
class FlywayStandInTest {
    private static final Map<String, List<String>> TABLES = Map.of("catalog.items", List.of("id bigint"), "billing.users", List.of("id bigint"));

    @Test
    void aQualifiedNameAsItIsABareOneOnlyInTheRunsOwnSchema() {
        assertThat(FlywayExtractor.madeElsewhere(TABLES, "catalog.items", "public")).isEqualTo("catalog.items");
        assertThat(FlywayExtractor.madeElsewhere(TABLES, "items", "catalog")).isEqualTo("catalog.items");
        // a table of that name in another schema may be another one entirely: no stand-in
        assertThat(FlywayExtractor.madeElsewhere(TABLES, "users", "public")).isNull();
        assertThat(FlywayExtractor.madeElsewhere(TABLES, "other.items", "public")).isNull();
    }
}
