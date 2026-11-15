package dev.logast;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** libraries.txt names every jar by its coordinate, from Gradle's cache or a Maven repository. */
class ShapeLibrariesTest {
    @Test
    void gradleCachePathsGiveCoordinates() {
        assertThat(ShapeExport.gradleCoordinate("/u/.gradle/caches/modules-2/files-2.1/org.projectlombok/lombok/1.18.46/a5cf/lombok-1.18.46.jar"))
                .isEqualTo("org.projectlombok:lombok:1.18.46");
        assertThat(ShapeExport.gradleCoordinate("/u/libs/lombok.jar")).isNull();
    }

    @Test
    void mavenRepositoryPathsGiveCoordinates() {
        assertThat(ShapeExport.mavenCoordinate("/u/.m2/repository/com/acme/platform/acme-calendar/0.3.0/acme-calendar-0.3.0.jar"))
                .isEqualTo("com.acme.platform:acme-calendar:0.3.0");
        assertThat(ShapeExport.mavenCoordinate("/u/.m2/repository/org/slf4j/slf4j-api/2.0.17/slf4j-api-2.0.17-sources.jar"))
                .isEqualTo("org.slf4j:slf4j-api:2.0.17");
        assertThat(ShapeExport.mavenCoordinate("/u/libs/acme.jar")).isNull();
    }
}
