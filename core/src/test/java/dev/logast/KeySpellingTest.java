package dev.logast;

import dev.logast.orders.ScanResult;
import dev.logast.orders.WorkOrder;
import dev.logast.policy.Policy;
import dev.logast.rules.Rules;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** A key already scoped in an older spelling counts as the canonical key: renamed, never scoped a second time. */
class KeySpellingTest {
    @Test
    void oldSpellingIsRenamedNotScopedAgain(@TempDir Path dir) throws Exception {
        Path src = Files.createDirectories(dir.resolve("src/main/java/x"));
        Files.writeString(src.resolve("IncomeController.java"), """
                package x;

                import org.springframework.web.bind.annotation.GetMapping;
                import org.springframework.web.bind.annotation.RequestParam;
                import org.springframework.web.bind.annotation.RestController;

                @RestController
                public class IncomeController {
                    @GetMapping("/incomes")
                    public String incomes(@RequestParam Long incomeTypeId) {
                        try (var mdc = MdcScope.of("income.type.id", incomeTypeId)) {
                            return "x" + incomeTypeId;
                        }
                    }
                }
                """);
        Files.writeString(dir.resolve("logast.yaml"), """
                logging: { keyStyle: camel }
                vocabulary:
                  incomeType: { mdc: { key: income.type.id, sources: { params: [incomeTypeId] } } }
                """);
        Policy policy = Policy.resolve(dir, null);
        ScanResult r = Rules.scan(Analysis.run(dir, Fixtures.classpath(), policy));
        assertThat(r.orders()).extracting(WorkOrder::rule).doesNotContain(Rules.MISSING_DOMAIN_MDC).contains(Rules.MDC_KEY_ALIAS);
    }
}
