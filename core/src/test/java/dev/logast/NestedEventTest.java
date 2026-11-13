package dev.logast;

import dev.logast.policy.Policy;
import dev.logast.rules.Rules;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/** A business event two calls below an observed listener is part of the listener's timer: no nested observation. */
class NestedEventTest {
    @Test
    void overridesBelowAnObservedListenerAreNotWrappedAgain(@TempDir Path dir) throws Exception {
        Path src = Files.createDirectories(dir.resolve("src/main/java/x"));
        Files.writeString(dir.resolve("logast.yaml"), "packs: [logging, tracing, metrics]\nglossary: { terms: [export] }\n");
        Files.writeString(src.resolve("Jobs.java"), """
                package x;

                import org.slf4j.Logger;
                import org.slf4j.LoggerFactory;
                import org.springframework.boot.context.event.ApplicationReadyEvent;
                import org.springframework.context.event.EventListener;

                class TpiJobExecutor {
                    private final TpiJob job;

                    TpiJobExecutor(TpiJob job) {
                        this.job = job;
                    }

                    @EventListener(ApplicationReadyEvent.class)
                    public void onReady() {
                        job.execute();
                    }
                }

                abstract class TpiJob {
                    void execute() {
                        doExecute();
                    }

                    abstract void doExecute();
                }

                class PreloadJob extends TpiJob {
                    private static final Logger log = LoggerFactory.getLogger(PreloadJob.class);

                    @Override
                    void doExecute() {
                        log.info("Preload export started");
                        log.info("Preload export completed");
                    }
                }

                class CalculationJob extends TpiJob {
                    @Override
                    void doExecute() {
                    }
                }
                """);
        var r = Rules.scan(Analysis.run(dir, Fixtures.classpath(), Policy.resolve(dir, null)));
        assertThat(r.orders()).filteredOn(o -> o.rule().equals(Rules.ENTRYPOINT_NOT_OBSERVED)).extracting(o -> o.method())
                .containsExactly("TpiJobExecutor.onReady");
        assertThat(r.orders()).noneMatch(o -> o.rule().equals(Rules.BUSINESS_EVENT_UNMEASURED));
    }
}
