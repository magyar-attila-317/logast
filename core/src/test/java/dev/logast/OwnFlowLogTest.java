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

/** An entrypoint counts as logging only when its own flow logs, not shared code far below or a failure branch. */
class OwnFlowLogTest {
    @Test
    void deepSharedLogsAndCatchBlocksDoNotCount(@TempDir Path dir) throws Exception {
        Path src = Files.createDirectories(dir.resolve("src/main/java/x"));
        Files.writeString(src.resolve("Api.java"), """
                package x;

                import org.slf4j.Logger;
                import org.slf4j.LoggerFactory;
                import org.springframework.web.bind.annotation.*;

                @RestController
                public class Api {
                    private static final Logger log = LoggerFactory.getLogger(Api.class);
                    private final Service service = new Service();

                    @PostMapping("/own")
                    public void own() {
                        service.logsItself();
                    }

                    @PostMapping("/deep")
                    public void deep() {
                        service.a();
                    }

                    @PostMapping("/failure-only")
                    public void failureOnly() {
                        try {
                            service.plain();
                        } catch (RuntimeException e) {
                            log.warn("Plain call failed", e);
                            throw e;
                        }
                    }
                }

                class Service {
                    private static final Logger log = LoggerFactory.getLogger(Service.class);
                    void logsItself() { log.info("Thing completed"); }
                    void a() { b(); }
                    void b() { new Shared().c(); }
                    void plain() { }
                }

                class Shared {
                    private static final Logger log = LoggerFactory.getLogger(Shared.class);
                    void c() { log.info("Event published"); }
                }
                """);
        ScanResult r = Rules.scan(Analysis.run(dir, Fixtures.classpath(), Policy.resolve(dir, null)));
        assertThat(r.orders()).filteredOn(o -> o.fixes().contains(Rules.ENTRYPOINT_SILENT)).extracting(WorkOrder::method)
                .containsExactlyInAnyOrder("Api.deep", "Api.failureOnly");
    }
}
