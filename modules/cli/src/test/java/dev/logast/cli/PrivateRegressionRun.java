package dev.logast.cli;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #10: one test per service under ~/.logast-private (or -Plogast.private.dir); none when the folder does not exist.
 * Runs only in {@code ./gradlew :cli:privateRegression} (never in test, e2e or CI); {@code -Pprivate.update} records.
 */
class PrivateRegressionRun {
    @TempDir
    Path work;

    @TestFactory
    Stream<DynamicTest> everyPrivateServiceMigratesAsRecorded() throws IOException {
        boolean update = Boolean.getBoolean("logast.private.update");
        return PrivateRegression.services(PrivateRegression.root()).stream().map(service -> DynamicTest.dynamicTest(
                service.getFileName().toString(), () -> {
                    List<String> problems = PrivateRegression.check(service, work, update);
                    assertThat(problems).as(service.getFileName() + (update ? " recorded" : " differs from expected/ "
                            + "(review it, then -Pprivate.update)")).isEmpty();
                }));
    }
}
