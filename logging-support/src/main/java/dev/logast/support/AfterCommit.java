package dev.logast.support;

import io.micrometer.observation.Observation;
import org.slf4j.MDC;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Map;

/**
 * A log line about a database change that is written only once the change is real.
 * <pre>{@code
 * int rows = jdbcTemplate.update(CLOSE_ITEMS, orderId);
 * AfterCommit.log(() -> log.info("Order items updated [rows={}]", rows));
 * }</pre>
 * Inside a transaction the line waits for the commit and is dropped on rollback (the failure itself is logged where
 * the exception is handled), so the log never claims a change that was rolled back. Outside a transaction it is
 * written at once. The MDC entries and the trace of the moment of the call are put back for the line.
 */
public final class AfterCommit {
    private AfterCommit() {
    }

    public static void log(Runnable line) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            line.run();
            return;
        }
        Map<String, String> mdc = MDC.getCopyOfContextMap();
        Observation observation = ObservationRegistryHolder.get().getCurrentObservation();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                Map<String, String> before = MDC.getCopyOfContextMap();
                Observation.Scope scope = observation == null ? null : observation.openScope();
                try {
                    if (mdc == null) {
                        MDC.clear();
                    } else {
                        MDC.setContextMap(mdc);
                    }
                    line.run();
                } catch (RuntimeException e) {
                    // a log line must never fail the code that committed
                } finally {
                    if (scope != null) {
                        scope.close();
                    }
                    if (before == null) {
                        MDC.clear();
                    } else {
                        MDC.setContextMap(before);
                    }
                }
            }
        });
    }
}
