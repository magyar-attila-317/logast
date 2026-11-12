package dev.logast;

import dev.logast.graph.CallGraph;
import dev.logast.model.Entrypoint;
import dev.logast.model.MethodInfo;
import dev.logast.parse.Pos;
import dev.logast.parse.SourceFileInfo;
import org.junit.jupiter.api.Test;
import org.openrewrite.java.JavaIsoVisitor;
import org.openrewrite.java.tree.J;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GraphTest {
    private final Analysis a = Fixtures.analysis();

    private MethodInfo method(String label) {
        return a.index.methods().stream().filter(m -> m.label().equals(label)).findFirst().orElseThrow();
    }

    @Test
    void parsesEverythingWithTypes() {
        var stats = a.stats();
        assertThat(stats.parseErrors()).isEmpty();
        assertThat(stats.unresolvedCalls()).as(stats.unresolvedSample().toString()).isZero();
        assertThat(stats.nonEditableFiles()).isEmpty();
    }

    @Test
    void findsEntrypoints() {
        assertThat(a.entrypoints).extracting(Entrypoint::label).containsExactly(
                "@EventListener CacheWarmup.onReady(ApplicationReadyEvent)",
                "@EventListener CacheWarmup.warmUp(ApplicationReadyEvent)",
                "@EventListener ShipmentListener.onShipped(ShipmentEvent)",
                "@InboxHandler InboxProcessor.handle",
                "@KafkaListener shipment-acks",
                "@Scheduled ReconciliationJob.reconcile",
                "DELETE /orders/{orderId}",
                "GET /orders/{orderId}",
                "GET /orders/{orderId}/lines",
                "GET /reports/daily",
                "POST /orders");
        // custom @InboxHandler, the Kafka listener (observation-enabled not set yet) and listeners on Spring's own
        // startup events start work unobserved; a listener on an application event runs in the publisher's trace
        assertThat(a.entrypoints).filteredOn(e -> !e.observedByFramework()).extracting(Entrypoint::label)
                .containsExactly("@EventListener CacheWarmup.onReady(ApplicationReadyEvent)",
                        "@EventListener CacheWarmup.warmUp(ApplicationReadyEvent)",
                        "@InboxHandler InboxProcessor.handle", "@KafkaListener shipment-acks");
    }

    @Test
    void interfaceCallsResolveToImplementations() {
        List<CallGraph.Edge> fromCreate = a.graph.out(method("OrderController.create"));
        assertThat(fromCreate).extracting(e -> e.to().label()).contains("DefaultOrderService.place");

        List<CallGraph.Edge> gateway = a.graph.out(method("PaymentClient.charge"));
        assertThat(gateway).extracting(e -> e.to().label())
                .containsExactlyInAnyOrder("CardGateway.charge", "FakeGateway.charge");
        assertThat(gateway).allMatch(CallGraph.Edge::ambiguous);
    }

    @Test
    void methodReferencesLambdasAndRecursion() {
        assertThat(a.graph.out(method("DefaultOrderService.place"))).extracting(e -> e.to().label())
                .contains("DefaultOrderService.validateLine", "PriceCalculator.total", "PaymentClient.charge");
        assertThat(a.graph.out(method("DefaultOrderService.cancel"))).extracting(e -> e.to().label())
                .contains("PaymentClient.refund");
        assertThat(a.graph.out(method("PriceCalculator.sum"))).extracting(e -> e.to().label())
                .containsExactly("PriceCalculator.sum");
    }

    @Test
    void asyncEdges() {
        var edge = a.graph.out(method("DefaultOrderService.place")).stream()
                .filter(e -> e.to().label().equals("NotificationService.sendConfirmation")).findFirst().orElseThrow();
        assertThat(edge.async()).isEqualTo(CallGraph.Async.MANAGED);
        var mail = a.graph.out(method("NotificationService.sendConfirmation")).stream()
                .filter(e -> e.to().label().equals("Mailer.send")).findFirst().orElseThrow();
        assertThat(mail.async()).isEqualTo(CallGraph.Async.RAW);
    }

    @Test
    void conditionalFiltersGuaranteeNothing() {
        assertThat(a.httpFilterKeys).contains("request.id").doesNotContain("tenant.id"); // TenantFilter has shouldNotFilter
    }

    @Test
    void mdcContextAlongPaths() {
        assertThat(a.paths.mdcAtEntry(method("OrderController.create")))
                .containsExactly("request.id", "spanId", "traceId");
        assertThat(a.paths.mdcAtEntry(method("DefaultOrderService.cancel"))).contains("order.id");
        // reached from create (no order.id) and cancel (order.id): intersection drops it
        assertThat(a.paths.mdcAtEntry(method("PaymentClient.charge"))).doesNotContain("order.id");
        // @Async without TaskDecorator loses everything
        assertThat(a.paths.mdcAtEntry(method("NotificationService.sendConfirmation"))).isEmpty();
        assertThat(a.paths.paths(method("PaymentClient.charge"), 3))
                .containsExactly("POST /orders → OrderController.create → DefaultOrderService.place → PaymentClient.charge");
    }

    @Test
    void positionsMatchSourceText() {
        for (SourceFileInfo f : a.project.files()) {
            new JavaIsoVisitor<Integer>() {
                @Override
                public J.MethodInvocation visitMethodInvocation(J.MethodInvocation m, Integer p) {
                    assertThat(norm(f.slice(Pos.start(m), Pos.end(m)))).isEqualTo(norm(m.printTrimmed(getCursor().getParentOrThrow())));
                    return super.visitMethodInvocation(m, p);
                }
            }.visit(f.cu(), 0);
        }
    }

    private static String norm(String s) {
        return s.replaceAll("//[^\n]*", "").replaceAll("\\s+", "");
    }

    @Test
    void explainRendersTree() {
        String out = new Explainer(a, java.util.Map.of()).explain("POST /orders", 10);
        System.out.println(out);
        assertThat(out).contains("PriceCalculator.sum", "↻ recursive", "(1 of 2 impls)", "⇢async/managed");
    }
}
