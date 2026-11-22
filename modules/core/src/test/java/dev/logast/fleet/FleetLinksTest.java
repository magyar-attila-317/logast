package dev.logast.fleet;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** #113: how fleet matches a call to an entrypoint of another service; #128: by its host too. */
class FleetLinksTest {
    @Test
    void pathVariablesMatchValuesTemplatesAndUnknownParts() {
        assertThat(FleetLinks.samePath("/orders/42", "/orders/{id}")).isTrue();
        assertThat(FleetLinks.samePath("/orders/{orderId}", "/orders/{id}")).isTrue();
        assertThat(FleetLinks.samePath("/orders/{?}/items", "/orders/{id}/items")).isTrue();
        assertThat(FleetLinks.samePath("/orders/42/items", "/orders/{id}")).as("one segment more").isFalse();
        assertThat(FleetLinks.samePath("/orders/42", "/invoices/{id}")).isFalse();
    }

    @Test
    void theBaseIsKeptOnlyWhenTheCodeHasItAndTheQueryIsDropped() {
        assertThat(ServiceSurface.basePath("http://billing:8080/invoices?draft=true")).containsExactly("http://billing:8080", "/invoices");
        assertThat(ServiceSurface.basePath("\u0000/entries/\u0000")).as("a base from a property: the path only")
                .containsExactly(null, "/entries/{?}");
        assertThat(ServiceSurface.basePath("/shipments/{id}/")).containsExactly(null, "/shipments/{id}");
        assertThat(ServiceSurface.basePath("\u0000")).as("nothing of the path known").isNull();
        assertThat(ServiceSurface.basePath("\u0000entries")).as("a base ending in /").containsExactly(null, "/entries");
        assertThat(ServiceSurface.basePath("/orders/\u0000")).containsExactly(null, "/orders/{?}");
    }

    @Test
    void theHostOfABaseUrlAndThePartThatNamesAService() {
        assertThat(FleetLinks.host("http://billing")).isEqualTo("billing");
        assertThat(FleetLinks.host("https://user:secret@Billing:8443/api")).isEqualTo("billing");
        assertThat(FleetLinks.host("billing:8080")).as("no scheme (OpenFeign url)").isEqualTo("billing");
        assertThat(FleetLinks.host("http://localhost:8081")).as("names no service").isNull();
        assertThat(FleetLinks.host("http://127.0.0.1:8081")).isNull();
        assertThat(FleetLinks.host("http://[::1]:8081")).isNull();
        assertThat(FleetLinks.host(null)).isNull();
        assertThat(FleetLinks.label("billing.payments.svc.cluster.local")).as("a Kubernetes service name").isEqualTo("billing");
        assertThat(FleetLinks.label(FleetLinks.host("http://ledger.finance.svc:8080/v1"))).isEqualTo("ledger");
        assertThat(FleetLinks.label("billing")).isEqualTo("billing");
        assertThat(FleetLinks.label(null)).isNull();
    }

    @Test
    void aHostNamesAServiceByItsFleetNameItsApplicationNameOrWithoutTheServiceSuffix() {
        FleetReport.Service billing = service("billing", "/src/billing-repo", "invoice-api", List.of());
        assertThat(FleetLinks.named("billing", billing)).contains("");
        assertThat(FleetLinks.named("BILLING", billing)).as("case does not matter").contains("");
        assertThat(FleetLinks.named("billing-repo", billing)).contains(" (billing's folder)");
        assertThat(FleetLinks.named("invoice-api", billing)).contains(" (spring.application.name of billing)");
        assertThat(FleetLinks.named("billing-service", billing)).as("a fallback, said so")
                .contains(" ~ billing (the names without -service / -svc)");
        assertThat(FleetLinks.named("billing-svc", billing)).isPresent();
        assertThat(FleetLinks.named("invoicing", billing)).isEmpty();
        assertThat(FleetLinks.base("orders-service")).isEqualTo("orders");
        assertThat(FleetLinks.base("orders_svc")).isEqualTo("orders");
        assertThat(FleetLinks.base("servicedesk")).isEqualTo("servicedesk");
    }

    @Test
    void theHostDecidesBetweenServicesWithTheSamePath() {
        FleetReport.Service billing = service("billing", null, null, List.of(entry("POST", "/invoices", "billing", null)));
        FleetReport.Service invoicing = service("invoicing", null, "invoicing-service", List.of(entry("POST", "/invoices", "invoicing", null)));
        FleetReport.Service ledger = service("ledger", null, null, List.of(entry("GET", "/entries/{id}", "ledger", null)));
        FleetReport.Service orders = caller(
                call("POST", "/invoices", "http://billing:8080", 1),
                call("POST", "/invoices", null, 2),
                call("POST", "/invoices", "http://invoicing-service.payments.svc.cluster.local", 3),
                call("GET", "/entries/{?}", "http://archive", 4),
                call("GET", "/entries/{?}", "http://billing", 5),
                call("GET", "/entries/{?}", "http://localhost:8080", 6),
                call("POST", "/invoices", "http://orders", 7));
        FleetLinks.Result r = FleetLinks.of(List.of(orders, billing, invoicing, ledger));

        assertThat(r.trace()).as("7: the caller's own name, a call inside the service")
                .extracting(t -> t.call().line() + " " + t.match() + " " + t.callees() + ": " + t.note()).containsExactly(
                        "1 confirmed billing: confirmed: host billing (invoicing has this path too)",
                        "2 ambiguous billing or invoicing: ambiguous: billing or invoicing have this path (base URL not in the code)",
                        "3 confirmed invoicing: confirmed: host invoicing-service.payments.svc.cluster.local"
                                + " (spring.application.name of invoicing) (billing has this path too)",
                        "6 pathOnly ledger: path only: host localhost names no service");
        FleetLinks.Trace ambiguous = r.trace().get(1);
        assertThat(ambiguous.callee()).isNull();
        assertThat(ambiguous.candidates()).extracting(FleetLinks.Candidate::service).containsExactly("billing", "invoicing");
        assertThat(r.otherHost()).extracting(t -> t.call().line() + " " + t.match() + ": " + t.note()).containsExactly(
                "4 differentHost: different host: archive names no service of the fleet; ledger has this path"
                        + " (a stale URL, or a service outside the fleet?)",
                "5 differentHost: different host: billing is billing, which has no such path; ledger has it (a stale URL?)");
        assertThat(FleetLinks.text(r)).contains(
                "    orders -> billing or invoicing  POST /invoices  (F.java:2 -> billing B.java:1, invoicing B.java:1)",
                "Same path, other host: 2 calls (not counted as links)", "  orders -> http://archive  GET /entries/{?}");
    }

    @Test
    void anAmbiguousCallBreaksAtTheCalleeOnlyWhenEveryCandidateDoes() {
        ServiceSurface.Break unobserved = new ServiceSurface.Break("the entrypoint is not observed", null, null, "observe the entrypoint");
        FleetReport.Service billing = service("billing", null, null, List.of(entry("GET", "/receipts/{id}", "billing", unobserved)));
        FleetReport.Service invoicing = service("invoicing", null, null, List.of(entry("GET", "/receipts/{id}", "invoicing", null)));
        FleetReport.Service orders = caller(call("GET", "/receipts/7", null, 1));
        FleetLinks.Result partly = FleetLinks.of(List.of(orders, billing, invoicing));
        assertThat(partly.trace()).hasSize(1);
        assertThat(partly.broken()).as("one candidate observes it: not a break of a specific callee").isEmpty();
        assertThat(FleetLinks.text(partly)).contains("the trace depends on which service the call reaches",
                "breaks at the callee if it is billing: the entrypoint is not observed");

        FleetReport.Service invoicingToo = service("invoicing", null, null, List.of(entry("GET", "/receipts/{id}", "invoicing", unobserved)));
        FleetLinks.Result all = FleetLinks.of(List.of(orders, billing, invoicingToo));
        assertThat(all.broken()).singleElement().satisfies(t -> assertThat(t.breaks()).containsExactly("callee"));
    }

    private static FleetReport.Service caller(ServiceSurface.Call... calls) {
        return new FleetReport.Service("orders", null, 1, 0, Map.of(), Map.of(), Map.of(), 100, List.of(), List.of(), null,
                new ServiceSurface(List.of(calls), List.of(), List.of(), null));
    }

    private static FleetReport.Service service(String name, String root, String application, List<ServiceSurface.Entry> entries) {
        return new FleetReport.Service(name, root, 1, entries.size(), Map.of(), Map.of(), Map.of(), 100, List.of(), List.of(), null,
                new ServiceSurface(List.of(), entries, List.of(), application));
    }

    private static ServiceSurface.Entry entry(String verb, String path, String service, ServiceSurface.Break b) {
        return new ServiceSurface.Entry("http", verb, path, verb + " " + path, service + ".handle", "B.java", 1, b);
    }

    private static ServiceSurface.Call call(String verb, String path, String base, int line) {
        return new ServiceSurface.Call("http", verb, path, base, "RestClient", "Client.call", "F.java", line, null);
    }
}
