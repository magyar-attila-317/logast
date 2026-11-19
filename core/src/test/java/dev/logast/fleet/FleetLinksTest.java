package dev.logast.fleet;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** #113: how fleet matches a call to an entrypoint of another service. */
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
}
