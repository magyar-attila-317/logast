package dev.logast.support;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ObservedContextTest {
    private final List<String> events = new ArrayList<>();
    private final ObservationRegistry registry = ObservationRegistry.create();

    {
        registry.observationConfig().observationHandler(new ObservationHandler<>() {
            @Override
            public void onStart(Observation.Context c) {
                events.add("start " + c.getName() + " " + c.getHighCardinalityKeyValues().stream().map(kv -> kv.getKey() + "=" + kv.getValue()).toList());
            }

            @Override
            public void onError(Observation.Context c) {
                events.add("error " + c.getError().getMessage());
            }

            @Override
            public void onStop(Observation.Context c) {
                events.add("stop " + c.getName());
            }

            @Override
            public boolean supportsContext(Observation.Context c) {
                return true;
            }
        });
    }

    private void work(boolean fail) {
        ObservedContext observed = ObservedContext.entrypoint("shipment-listener.on-shipped", registry)
                .mdc("order.id", "o-1").start();
        try (observed) {
            assertThat(MDC.get("order.id")).isEqualTo("o-1");
            assertThat(registry.getCurrentObservation()).isSameAs(observed.observation());
            if (fail) {
                throw new IllegalStateException("boom");
            }
        } catch (Throwable t) {
            observed.error(t);
            throw t;
        } finally {
            observed.stop();
        }
    }

    @Test
    void generatedPatternRecordsLifecycleAndRestoresMdc() {
        work(false);
        assertThat(events).containsExactly("start shipment-listener.on-shipped [order.id=o-1]", "stop shipment-listener.on-shipped");
        assertThat(MDC.get("order.id")).isNull();
        assertThat(registry.getCurrentObservation()).isNull();
    }

    @Test
    void errorIsRecordedBeforeStop() {
        assertThatThrownBy(() -> work(true)).hasMessage("boom");
        assertThat(events).containsExactly("start shipment-listener.on-shipped [order.id=o-1]", "error boom",
                "stop shipment-listener.on-shipped");
    }

    @Test
    void executorWrapperCarriesMdcAndObservation() throws Exception {
        ExecutorService executor = ContextExecutors.wrap(Executors.newSingleThreadExecutor());
        AtomicReference<String> seen = new AtomicReference<>();
        AtomicReference<Observation> seenObs = new AtomicReference<>();
        ObservedContext observed = ObservedContext.entrypoint("job", registry).mdc("job.name", "reconciliation").start();
        try (observed) {
            executor.submit(() -> {
                seen.set(MDC.get("job.name"));
                seenObs.set(registry.getCurrentObservation());
            }).get();
        } finally {
            observed.stop();
        }
        executor.submit(() -> assertThat(MDC.get("job.name")).isNull()).get();
        executor.shutdown();
        assertThat(seen.get()).isEqualTo("reconciliation");
        assertThat(seenObs.get()).isSameAs(observed.observation());
    }

    @Test
    void pooledWorkerNeverKeepsAnotherTasksContext() throws Exception {
        ExecutorService raw = Executors.newFixedThreadPool(2);
        ExecutorService executor = ContextExecutors.wrap(raw);
        List<java.util.concurrent.Future<String>> results = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            try (var mdc = MdcScope.of("order.id", "o-" + i)) {
                String expected = "o-" + i;
                results.add(executor.submit(() -> {
                    String seen = MDC.get("order.id");
                    if (Math.random() < 0.5) {
                        MDC.put("leaky", "x"); // task code that forgets to clean up
                    }
                    return expected.equals(seen) ? "ok" : "saw " + seen + " expected " + expected;
                }));
            }
        }
        for (var f : results) {
            assertThat(f.get()).isEqualTo("ok");
        }
        // unwrapped submit on the same workers: nothing of the wrapped tasks may remain
        for (int i = 0; i < 4; i++) {
            assertThat(raw.submit(() -> MDC.getCopyOfContextMap()).get()).isNullOrEmpty();
        }
        raw.shutdown();
    }
}
