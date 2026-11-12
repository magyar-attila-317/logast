package dev.logast;

import dev.logast.orders.WorkOrder;
import dev.logast.rules.RuntimeVerifier;
import dev.logast.rules.RuntimeVerifier.Event;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RuntimeVerifierTest {
    private static final String TEST = "[engine:junit-jupiter]/[class:com.x.FlowTest]/[method:flow()]";
    private static final String NOTIFY = "com.example.shop.notification.NotificationService";
    private static final String MAILER = "com.example.shop.notification.Mailer";

    @Test
    void contextLostOnAnotherThreadIsReportedAtTheLogStatement() {
        List<Event> events = List.of(
                new Event(TEST, "main", "Sending confirmation for order {}", "Sending confirmation for order o-1", NOTIFY, 25,
                        Map.of("traceId", "abc", "order.id", "o-1")),
                new Event(TEST, "pool-1", "Sending mail to {}", "Sending mail to null", MAILER, 12, Map.of()));
        RuntimeVerifier.Result r = RuntimeVerifier.verify(Fixtures.analysis(), events);
        assertThat(r.exercised()).isEqualTo(2);
        assertThat(r.orders()).extracting(WorkOrder::rule)
                .containsExactlyInAnyOrder("RUNTIME_TRACE_LOST", "RUNTIME_MDC_LOST", "RUNTIME_NULL_VALUE");
        assertThat(r.orders()).allMatch(o -> o.file().endsWith("Mailer.java") && o.line() == 12);
    }

    @Test
    void sameThreadOrNoPriorContextIsNotALoss() {
        List<Event> events = List.of(
                // a unit test that never had context: nothing to lose
                new Event(TEST, "main", "Sending mail to {}", "Sending mail to a@b", MAILER, 12, Map.of()),
                // context ended on the same thread (scope closed): normal
                new Event(TEST + "2", "main", "Sending confirmation for order {}", "x", NOTIFY, 25, Map.of("order.id", "o-1")),
                new Event(TEST + "2", "main", "Sending mail to {}", "Sending mail to a@b", MAILER, 12, Map.of()));
        assertThat(RuntimeVerifier.verify(Fixtures.analysis(), events).orders()).isEmpty();
    }
}
