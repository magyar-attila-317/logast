package dev.logast;

import dev.logast.orders.ScanResult;
import dev.logast.orders.WorkOrder;
import dev.logast.policy.Policy;
import dev.logast.rules.Rules;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** #278: an MDC value taken from what the caller sent is reported unless something checks it first. */
class UnvalidatedMdcValueTest {
    @Test
    void callerChosenMdcValuesNeedACheck(@TempDir Path dir) throws Exception {
        Path src = Files.createDirectories(dir.resolve("src/main/java/x"));
        Files.writeString(src.resolve("CorrelationFilter.java"), """
                package x;

                import jakarta.servlet.http.HttpServletRequest;
                import java.util.UUID;
                import java.util.regex.Pattern;
                import org.slf4j.MDC;
                import org.springframework.web.bind.annotation.RequestHeader;

                public class CorrelationFilter {
                    private static final Pattern ID = Pattern.compile("[A-Za-z0-9-]{1,64}");

                    void raw(HttpServletRequest request) {
                        String id = request.getHeader("X-Correlation-Id");
                        if (id == null || id.isBlank()) {
                            id = UUID.randomUUID().toString();
                        }
                        MDC.put("correlationId", id);
                    }

                    void direct(HttpServletRequest request) {
                        MDC.put("client", request.getHeader("X-Client"));
                    }

                    void bound(@RequestHeader("X-Tenant") String tenant) {
                        MDC.put("tenant", tenant);
                    }

                    void checked(HttpServletRequest request) {
                        String id = request.getHeader("X-Correlation-Id");
                        if (id == null || !ID.matcher(id).matches()) {
                            id = UUID.randomUUID().toString();
                        }
                        MDC.put("correlationId", id);
                    }

                    void parsed(HttpServletRequest request) {
                        String id = request.getHeader("X-Request-Id");
                        MDC.put("requestId", UUID.fromString(id).toString());
                    }

                    void generated() {
                        MDC.put("correlationId", UUID.randomUUID().toString());
                    }
                }
                """);
        ScanResult r = Rules.scan(Analysis.run(dir, Fixtures.classpath(), Policy.resolve(dir, null)));
        List<String> methods = r.orders().stream().filter(o -> o.rule().equals(Rules.MDC_VALUE_UNVALIDATED))
                .map(WorkOrder::method).sorted().toList();
        assertThat(methods).containsExactly("CorrelationFilter.bound", "CorrelationFilter.direct", "CorrelationFilter.raw");
        assertThat(r.orders()).filteredOn(o -> o.rule().equals(Rules.MDC_VALUE_UNVALIDATED)).allSatisfy(o -> {
            assertThat(o.edits()).as("report-only").isEmpty();
        });
    }
}
