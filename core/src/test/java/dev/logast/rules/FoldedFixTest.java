package dev.logast.rules;

import dev.logast.Analysis;
import dev.logast.Fixtures;
import dev.logast.orders.WorkOrder;
import dev.logast.policy.Policy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** #75: an order lists another rule under "also fixes" only while that rule is on. */
class FoldedFixTest {

    @Test
    void theStarterOrderListsAsyncMethodsOnlyWhileAsyncContextLossIsOn(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("build.gradle.kts"), """
                plugins {
                    java
                }

                dependencies {
                    implementation("org.springframework.boot:spring-boot-starter")
                }
                """);
        Path src = Files.createDirectories(dir.resolve("src/main/java/x"));
        Files.writeString(src.resolve("ParcelNotifier.java"), """
                package x;

                import org.springframework.scheduling.annotation.Async;
                import org.springframework.stereotype.Service;

                @Service
                public class ParcelNotifier {
                    @Async
                    public void notifyShipped(String parcelCode) {
                    }
                }
                """);
        Policy policy = Policy.resolve(dir, null);
        WorkOrder starter = starter(dir, policy);
        assertThat(starter.fixes()).as("the starter carries MDC into @Async methods").contains(Rules.ASYNC_CONTEXT_LOSS);
        assertThat(starter.locations()).isNotEmpty();

        WorkOrder off = starter(dir, RuleLedgerTest.disable(policy, Rules.ASYNC_CONTEXT_LOSS));
        assertThat(off.fixes()).doesNotContain(Rules.ASYNC_CONTEXT_LOSS);
        assertThat(off.locations()).isEmpty();
    }

    private static WorkOrder starter(Path dir, Policy policy) {
        List<WorkOrder> orders = Rules.scan(Analysis.run(dir, Fixtures.classpath(), policy)).orders();
        return orders.stream().filter(o -> o.rule().equals(Rules.SETUP_SUPPORT_STARTER)).findFirst().orElseThrow();
    }
}
